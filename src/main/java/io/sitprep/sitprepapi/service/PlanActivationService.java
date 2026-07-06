package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.*;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Plan-activation write + read. An activation is an opaque-id row plus three
 * small join tables for the recipient sets. Acks are a separate table keyed
 * by (activationId, recipientEmail) so re-taps overwrite. Snapshots for
 * meetingPlace / evacPlan / emergencyContactGroups are resolved at read time
 * from the owner's current plan — this is deliberate so the recipient sees
 * edits made after activation (e.g. owner corrects a shelter address).
 */
@Service
public class PlanActivationService {

    private static final Logger log = LoggerFactory.getLogger(PlanActivationService.class);

    /** Activations auto-close after this so stale plans don't confuse recipients. */
    private static final Duration DEFAULT_TTL = Duration.ofHours(72);

    private final PlanActivationRepo activationRepo;
    private final PlanActivationAckRepo ackRepo;
    private final UserInfoRepo userInfoRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final OriginLocationRepo originLocationRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final WebSocketMessageSender ws;
    private final GroupRepo groupRepo;
    private final NotificationService notificationService;
    private final HouseholdAccessService householdAccess;

    public PlanActivationService(
            PlanActivationRepo activationRepo,
            PlanActivationAckRepo ackRepo,
            UserInfoRepo userInfoRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            OriginLocationRepo originLocationRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo,
            WebSocketMessageSender ws,
            GroupRepo groupRepo,
            NotificationService notificationService,
            HouseholdAccessService householdAccess
    ) {
        this.activationRepo = activationRepo;
        this.ackRepo = ackRepo;
        this.userInfoRepo = userInfoRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.originLocationRepo = originLocationRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.ws = ws;
        this.groupRepo = groupRepo;
        this.notificationService = notificationService;
        this.householdAccess = householdAccess;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    @Transactional
    public ActivationCreatedDto createActivation(CreateActivationRequest req) {
        String ownerEmail = Optional.ofNullable(req.ownerEmail())
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("ownerEmail is required"));

        PlanActivation a = new PlanActivation();
        a.setOwnerEmail(ownerEmail);

        userInfoRepo.findByUserEmailIgnoreCase(ownerEmail).ifPresent(u -> {
            a.setOwnerUserId(u.getId());
            a.setOwnerName(joinName(u.getUserFirstName(), u.getUserLastName()));
        });

        // SECURITY (IDOR guard): the referenced meeting place / evacuation plan
        // must belong to the owner (or the owner's household). Meeting/evac ids
        // are sequential @GeneratedValue(IDENTITY) Longs, so without this an
        // authenticated attacker could activate a plan that references a VICTIM's
        // id and then read the victim's shelter/meeting coordinates back through
        // the activation-map endpoint (the map assembler resolves whatever id was
        // stored). Reject cross-tenant references at the write boundary.
        assertMeetingPlaceOwned(req.meetingPlaceId(), ownerEmail);
        assertEvacPlanOwned(req.evacPlanId(), ownerEmail);

        a.setMeetingPlaceId(req.meetingPlaceId());
        a.setEvacPlanId(req.evacPlanId());
        a.setMeetingMode(req.meetingMode());
        a.setEvacMode(req.evacMode());
        a.setMessagePreview(req.messagePreview());

        if (req.location() != null) {
            a.setLat(req.location().lat());
            a.setLng(req.location().lng());
        }

        Instant now = Instant.now();
        a.setActivatedAt(now);
        a.setExpiresAt(now.plus(DEFAULT_TTL));

        if (req.recipients() != null) {
            if (req.recipients().householdMemberIds() != null) {
                a.getHouseholdMemberIds().addAll(req.recipients().householdMemberIds());
            }
            if (req.recipients().contactIds() != null) {
                a.getContactIds().addAll(req.recipients().contactIds());
            }
            if (req.recipients().contactGroupIds() != null) {
                a.getContactGroupIds().addAll(req.recipients().contactGroupIds());
            }
        }

        PlanActivation saved = activationRepo.save(a);
        log.info("Activation created id={} owner={} expiresAt={}",
                saved.getId(), ownerEmail, saved.getExpiresAt());

        // Push the owner's authenticated household members so they can open
        // the plan + check in. Fires AFTER commit so the row is durable
        // before we notify; failures are logged, never block the create.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    notifyHouseholdOfActivation(ownerEmail, saved.getId());
                } catch (Exception e) {
                    log.error("Plan activation push fan-out failed owner={} activation={}",
                            ownerEmail, saved.getId(), e);
                }
            }
        });

        return new ActivationCreatedDto(saved.getId(), saved.getExpiresAt());
    }

    /**
     * Fan-out an activation push to the owner's household members (decision
     * 2026-05-22: activation does BOTH a push to authed members AND the
     * owner's share sheet). Resolves the household group from the owner,
     * batch-loads members + FCM tokens, and delivers a presence-aware push
     * per member via {@link NotificationService#deliverPresenceAware} with
     * the {@code PLAN_ACTIVATION_RECEIVED} category (Lane A, quiet-hours
     * bypass). Self-excludes the owner. Tokenless members are skipped
     * gracefully by the notification layer.
     */
    private void notifyHouseholdOfActivation(String ownerEmail, String activationId) {
        Group household = groupRepo.findByMemberEmail(ownerEmail).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .findFirst()
                .orElse(null);
        if (household == null || household.getMemberEmails() == null || household.getMemberEmails().isEmpty()) {
            return;
        }

        String ownerName = userInfoRepo.findByUserEmailIgnoreCase(ownerEmail)
                .map(u -> joinName(u.getUserFirstName(), u.getUserLastName()))
                .filter(s -> s != null && !s.isBlank())
                .orElse("Your household");

        String title = "🚨 Emergency plan activated";
        String body = ownerName + " activated the family plan. Open it and check in when you're safe.";
        String targetUrl = "/deployedplan?activationId=" + activationId;

        List<UserInfo> members = userInfoRepo.findByUserEmailIn(household.getMemberEmails());
        for (UserInfo m : members) {
            if (m.getUserEmail() == null) continue;
            if (m.getUserEmail().equalsIgnoreCase(ownerEmail)) continue; // don't notify the activator
            notificationService.deliverPresenceAware(
                    m.getUserEmail(), title, body, ownerName,
                    "/images/plan-icon.png", "plan_activation", activationId,
                    targetUrl, null, m.getFcmtoken(),
                    PushPolicyService.Category.PLAN_ACTIVATION_RECEIVED
            );
        }
        log.info("Plan activation {} pushed to {} household member(s)", activationId, members.size());
    }

    // ---------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------

    /**
     * Audience-aware snapshot (SEC-3, docs/map/MAP_PRIVACY_AND_SECURITY_REVIEW.md).
     * The AUTHENTICATED owner / household gets the full detail (ack roll-up with
     * live coordinates + full emergency contacts). Any other caller — including a
     * logged-out recipient on the shared link — gets the RECIPIENT view: the plan
     * destinations they need, but NO other recipient's check-in (empty acks) and
     * emergency contacts stripped to name/role/phone (no address / medical / email).
     * This closes the legacy leak where any link holder saw every recipient's live
     * location + full contact PII.
     */
    @Transactional(readOnly = true)
    public Optional<ActivationDetailDto> getActivation(String activationId, String callerEmail) {
        return activationRepo.findById(activationId)
                .map(a -> isAuthorizedReader(a, callerEmail) ? toDetailDto(a) : toRecipientDetailDto(a));
    }

    /**
     * Full ack roll-up (every recipient's status + live coordinates) — OWNER /
     * household ONLY. A recipient link holder is not the audience for other
     * people's live locations. 404 unknown, 403 unauthorized.
     */
    @Transactional(readOnly = true)
    public List<AckDto> getAcks(String activationId, String callerEmail) {
        PlanActivation a = activationRepo.findById(activationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation not found"));
        if (!isAuthorizedReader(a, callerEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ack roll-up is owner-only");
        }
        return ackRepo.findByActivationIdOrderByAckedAtAsc(activationId).stream()
                .map(this::toAckDto)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Map (deployed-plan emergency map — MapPoiDto; docs/map/MAP_API_CONTRACT.md)
    // ---------------------------------------------------------------------

    /**
     * AUTHENTICATED owner / household view of a deployed plan as map points:
     * meeting place + shelter/destination + the owner rally point + home origin.
     * The caller must be a verified user who is the owner, a household co-member
     * ({@link HouseholdAccessService#canReadPlanDataFor}), or an explicitly-
     * targeted household recipient ({@code householdMemberIds}). 404 unknown,
     * 410 expired, 403 unauthorized. Never returns ack coordinates or contact PII.
     */
    @Transactional(readOnly = true)
    public List<MapPoiDto> getActivationMap(String activationId, String callerEmail) {
        PlanActivation a = requireActiveActivation(activationId);
        if (!isAuthorizedReader(a, callerEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Plan is not shared with you");
        }
        return assembleMapPois(a, true);
    }

    /**
     * PUBLIC (link-possession) recipient view — deliberately data-minimized to
     * ONLY the destinations the recipient is directed to (meeting place +
     * shelter). Excludes the owner's home/origin, every OTHER recipient's live
     * check-in coordinates, and the emergency-contact PII that the legacy
     * un-authed {@code GET /{id}} snapshot still exposes. This is the source the
     * guest emergency map reads, so the shipped map never depends on that leaky
     * snapshot. 404 unknown, 410 expired.
     */
    @Transactional(readOnly = true)
    public List<MapPoiDto> getRecipientMap(String activationId) {
        PlanActivation a = requireActiveActivation(activationId);
        return assembleMapPois(a, false);
    }

    private PlanActivation requireActiveActivation(String activationId) {
        PlanActivation a = activationRepo.findById(activationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation not found"));
        if (Instant.now().isAfter(a.getExpiresAt())) {
            throw new ActivationExpiredException(activationId);
        }
        return a;
    }

    private boolean isAuthorizedReader(PlanActivation a, String callerEmail) {
        if (callerEmail == null) return false;
        if (a.getOwnerEmail() != null && a.getOwnerEmail().equalsIgnoreCase(callerEmail)) return true;
        if (householdAccess.canReadPlanDataFor(callerEmail, a.getOwnerEmail())) return true;
        // Explicitly-targeted household recipient (by UserInfo id).
        if (a.getHouseholdMemberIds() != null && !a.getHouseholdMemberIds().isEmpty()) {
            String callerId = userInfoRepo.findByUserEmailIgnoreCase(callerEmail)
                    .map(UserInfo::getId).orElse(null);
            return callerId != null && a.getHouseholdMemberIds().contains(callerId);
        }
        return false;
    }

    /**
     * Assemble the plan's map points. {@code includePrivate=false} yields the
     * recipient-safe set (meeting + shelter only); {@code true} adds the owner
     * rally point + home origin for the owner/household view. Non-finite
     * coordinates are dropped.
     */
    private List<MapPoiDto> assembleMapPois(PlanActivation a, boolean includePrivate) {
        List<MapPoiDto> pois = new ArrayList<>();

        if (a.getMeetingPlaceId() != null) {
            meetingPlaceRepo.findById(a.getMeetingPlaceId()).ifPresent(m -> {
                if (finite(m.getLat(), m.getLng())) {
                    pois.add(planPoi("activation:meeting:" + m.getId(), "amenity", "meetup",
                            m.getName() != null ? m.getName() : "Meeting place",
                            m.getLat(), m.getLng(), m.getAddress()));
                }
            });
        }

        if (a.getEvacPlanId() != null) {
            evacuationPlanRepo.findById(a.getEvacPlanId()).ifPresent(e -> {
                if (finite(e.getLat(), e.getLng())) {
                    String name = e.getShelterName() != null ? e.getShelterName()
                            : e.getDestination() != null ? e.getDestination() : "Shelter";
                    pois.add(planPoi("activation:shelter:" + e.getId(), "shelter", "shelter-primary",
                            name, e.getLat(), e.getLng(), e.getShelterAddress()));
                }
            });
        }

        if (includePrivate) {
            if (finite(a.getLat(), a.getLng())) {
                String ownerName = a.getOwnerName() != null ? a.getOwnerName() : "Owner";
                pois.add(planPoi("activation:owner:" + a.getId(), "agency", "owner",
                        ownerName + " location", a.getLat(), a.getLng(), null));
            }
            originLocationRepo.findByOwnerEmailIgnoreCase(a.getOwnerEmail()).stream()
                    .filter(o -> finite(o.getLat(), o.getLng()))
                    .findFirst()
                    .ifPresent(o -> pois.add(planPoi("activation:origin:" + o.getId(), "amenity", "origin",
                            o.getName() != null ? o.getName() : "Starting point",
                            o.getLat(), o.getLng(), o.getAddress())));
        }

        return pois;
    }

    /** One deployed-plan map point in the canonical MapPoiDto shape. */
    private static MapPoiDto planPoi(String id, String family, String placeLabel,
                                     String name, Double lat, Double lng, String address) {
        return new MapPoiDto(
                id, family, "proprietary:activation", name, lat, lng, null,
                null, null, null, null, null,     // verified..ownerUserId
                null, null, address, placeLabel,  // postId, kind, description(=address), placeLabel
                null, null, null, null            // category, website, externalMapUrl, attribution
        );
    }

    private static boolean finite(Double lat, Double lng) {
        return lat != null && lng != null
                && !lat.isNaN() && !lng.isNaN()
                && !lat.isInfinite() && !lng.isInfinite();
    }

    // ── IDOR guards (activation-create) ──────────────────────────────────
    private void assertMeetingPlaceOwned(Long meetingPlaceId, String ownerEmail) {
        if (meetingPlaceId == null) return;
        meetingPlaceRepo.findById(meetingPlaceId).ifPresent(mp -> {
            if (!householdAccess.canReadPlanDataFor(ownerEmail, mp.getOwnerEmail())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Referenced meeting place does not belong to you");
            }
        });
    }

    private void assertEvacPlanOwned(Long evacPlanId, String ownerEmail) {
        if (evacPlanId == null) return;
        evacuationPlanRepo.findById(evacPlanId).ifPresent(ep -> {
            if (!householdAccess.canReadPlanDataFor(ownerEmail, ep.getOwnerEmail())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Referenced evacuation plan does not belong to you");
            }
        });
    }

    // ---------------------------------------------------------------------
    // Ack (upsert)
    // ---------------------------------------------------------------------

    /**
     * Upsert an ack for (activationId, recipientEmail). Returns the saved
     * record. Throws {@link ActivationExpiredException} if the activation is
     * past its expiry. Broadcasts to {@code /topic/activations/{id}/acks}
     * after commit.
     */
    @Transactional
    public AckDto recordAck(String activationId, AckRequest req) {
        if (req == null) throw new IllegalArgumentException("ack body required");

        String recipientEmail = Optional.ofNullable(req.recipientEmail())
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("recipientEmail required"));

        String status = Optional.ofNullable(req.status())
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> s.equals("safe") || s.equals("help") || s.equals("pickup"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "status must be one of: safe, help, pickup"));

        PlanActivation activation = activationRepo.findById(activationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Activation not found: " + activationId));

        if (Instant.now().isAfter(activation.getExpiresAt())) {
            throw new ActivationExpiredException(activationId);
        }

        PlanActivationAck ack = ackRepo
                .findByActivationIdAndRecipientEmailIgnoreCase(activationId, recipientEmail)
                .orElseGet(PlanActivationAck::new);

        ack.setActivationId(activationId);
        ack.setRecipientEmail(recipientEmail);
        // Only overwrite display name on first insert or if the new one is non-blank.
        if (req.recipientName() != null && !req.recipientName().isBlank()) {
            ack.setRecipientName(req.recipientName().trim());
        } else if (ack.getRecipientName() == null) {
            // Best-effort lookup from UserInfo so the owner-side roll-up can show a name.
            userInfoRepo.findByUserEmailIgnoreCase(recipientEmail).ifPresent(u ->
                    ack.setRecipientName(joinName(u.getUserFirstName(), u.getUserLastName())));
        }
        ack.setStatus(status);
        ack.setLat(req.lat());
        ack.setLng(req.lng());
        ack.setAckedAt(Instant.now());

        // Fast double-tap can race the upsert: thread A reads (miss), thread B reads (miss),
        // both INSERT, second hits the (activation_id, lower(recipient_email)) unique constraint.
        // Treat the duplicate as success — their ack is already recorded — and re-read the row.
        PlanActivationAck saved;
        try {
            saved = ackRepo.save(ack);
        } catch (DataIntegrityViolationException dive) {
            saved = ackRepo
                    .findByActivationIdAndRecipientEmailIgnoreCase(activationId, recipientEmail)
                    .orElseThrow(() -> dive); // DIVE without an existing row is a real error
        }
        AckDto dto = toAckDto(saved);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendActivationAck(activationId, dto);
                } catch (Exception e) {
                    log.error("WS broadcast failed for ack activationId={} recipient={}",
                            activationId, recipientEmail, e);
                }
            }
        });

        return dto;
    }

    // ---------------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------------

    private ActivationDetailDto toDetailDto(PlanActivation a) {
        MeetingPlaceSnapshotDto mp = null;
        if (a.getMeetingPlaceId() != null) {
            mp = meetingPlaceRepo.findById(a.getMeetingPlaceId())
                    .map(this::toMeetingPlaceSnapshot)
                    .orElse(null);
        }

        EvacuationPlanSnapshotDto ep = null;
        if (a.getEvacPlanId() != null) {
            ep = evacuationPlanRepo.findById(a.getEvacPlanId())
                    .map(this::toEvacPlanSnapshot)
                    .orElse(null);
        }

        List<EmergencyContactGroupSnapshotDto> ecgs;
        if (a.getContactGroupIds() == null || a.getContactGroupIds().isEmpty()) {
            ecgs = List.of();
        } else {
            ecgs = emergencyContactGroupRepo.findAllById(a.getContactGroupIds()).stream()
                    .map(this::toContactGroupSnapshot)
                    .toList();
        }

        List<AckDto> acks = ackRepo.findByActivationIdOrderByAckedAtAsc(a.getId()).stream()
                .map(this::toAckDto)
                .toList();

        boolean closed = Instant.now().isAfter(a.getExpiresAt());
        LocationDto location = (a.getLat() == null && a.getLng() == null)
                ? null : new LocationDto(a.getLat(), a.getLng());

        return new ActivationDetailDto(
                a.getId(),
                a.getOwnerUserId(),
                a.getOwnerName(),
                a.getActivatedAt(),
                a.getExpiresAt(),
                closed,
                a.getMeetingMode(),
                a.getEvacMode(),
                a.getMessagePreview(),
                location,
                mp,
                ep,
                ecgs,
                acks
        );
    }

    private MeetingPlaceSnapshotDto toMeetingPlaceSnapshot(MeetingPlace m) {
        return new MeetingPlaceSnapshotDto(
                m.getId(), m.getName(), m.getLocation(), m.getAddress(),
                m.getPhoneNumber(), m.getAdditionalInfo(), m.getLat(), m.getLng()
        );
    }

    private EvacuationPlanSnapshotDto toEvacPlanSnapshot(EvacuationPlan e) {
        return new EvacuationPlanSnapshotDto(
                e.getId(), e.getName(), e.getOrigin(), e.getDestination(),
                e.getShelterName(), e.getShelterAddress(), e.getShelterPhoneNumber(),
                e.getLat(), e.getLng(), e.getTravelMode(), e.getShelterInfo()
        );
    }

    private EmergencyContactGroupSnapshotDto toContactGroupSnapshot(EmergencyContactGroup g) {
        List<EmergencyContactSnapshotDto> contacts = g.getContacts() == null ? List.of()
                : g.getContacts().stream().map(this::toContactSnapshot).toList();
        return new EmergencyContactGroupSnapshotDto(g.getId(), g.getName(), contacts);
    }

    private EmergencyContactSnapshotDto toContactSnapshot(EmergencyContact c) {
        return new EmergencyContactSnapshotDto(
                c.getId(), c.getName(), c.getRole(), c.getPhone(), c.getEmail(),
                c.getAddress(), c.getRadioChannel(), c.getMedicalInfo(),
                c.getSubjectType(), c.getSubjectId(), c.getSubjectName()
        );
    }

    /**
     * Recipient-safe projection (SEC-3): the plan destinations, but acks empty
     * (a link holder is not shown other recipients' status/live location) and
     * emergency contacts stripped to name/role/phone (no address / medical /
     * email / radio / subject PII).
     */
    private ActivationDetailDto toRecipientDetailDto(PlanActivation a) {
        MeetingPlaceSnapshotDto mp = a.getMeetingPlaceId() == null ? null
                : meetingPlaceRepo.findById(a.getMeetingPlaceId()).map(this::toMeetingPlaceSnapshot).orElse(null);
        EvacuationPlanSnapshotDto ep = a.getEvacPlanId() == null ? null
                : evacuationPlanRepo.findById(a.getEvacPlanId()).map(this::toEvacPlanSnapshot).orElse(null);

        List<EmergencyContactGroupSnapshotDto> ecgs;
        if (a.getContactGroupIds() == null || a.getContactGroupIds().isEmpty()) {
            ecgs = List.of();
        } else {
            ecgs = emergencyContactGroupRepo.findAllById(a.getContactGroupIds()).stream()
                    .map(this::toContactGroupSnapshotMinimal)
                    .toList();
        }

        boolean closed = Instant.now().isAfter(a.getExpiresAt());
        LocationDto location = (a.getLat() == null && a.getLng() == null)
                ? null : new LocationDto(a.getLat(), a.getLng());

        return new ActivationDetailDto(
                a.getId(), a.getOwnerUserId(), a.getOwnerName(),
                a.getActivatedAt(), a.getExpiresAt(), closed,
                a.getMeetingMode(), a.getEvacMode(), a.getMessagePreview(),
                location, mp, ep, ecgs,
                List.of()   // acks — a recipient never sees the roll-up
        );
    }

    private EmergencyContactGroupSnapshotDto toContactGroupSnapshotMinimal(EmergencyContactGroup g) {
        List<EmergencyContactSnapshotDto> contacts = g.getContacts() == null ? List.of()
                : g.getContacts().stream().map(this::toContactSnapshotMinimal).toList();
        return new EmergencyContactGroupSnapshotDto(g.getId(), g.getName(), contacts);
    }

    /** Name / role / phone ONLY — drops address, email, radio, medical, subject PII. */
    private EmergencyContactSnapshotDto toContactSnapshotMinimal(EmergencyContact c) {
        return new EmergencyContactSnapshotDto(
                c.getId(), c.getName(), c.getRole(), c.getPhone(),
                null, null, null, null, null, null, null
        );
    }

    private AckDto toAckDto(PlanActivationAck a) {
        return new AckDto(
                a.getId(),
                a.getRecipientEmail(),
                a.getRecipientName(),
                a.getStatus(),
                a.getLat(),
                a.getLng(),
                a.getAckedAt()
        );
    }

    private static String joinName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        if (f.isEmpty() && l.isEmpty()) return null;
        if (l.isEmpty()) return f;
        if (f.isEmpty()) return l;
        return f + " " + l;
    }

    /** Signalled to the resource layer so it can return 410 Gone. */
    public static class ActivationExpiredException extends RuntimeException {
        public ActivationExpiredException(String activationId) {
            super("Activation expired: " + activationId);
        }
    }
}
