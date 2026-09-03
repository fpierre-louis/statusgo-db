package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.*;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
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
    private final EmergencyContactRepo emergencyContactRepo;
    private final WebSocketMessageSender ws;
    private final GroupRepo groupRepo;
    private final NotificationService notificationService;
    private final HouseholdAccessService householdAccess;
    private final HouseholdResolver householdResolver;
    private final GoBagService goBagService;
    private final HouseholdEventService householdEventService;
    private final GroupService groupService;

    public PlanActivationService(
            PlanActivationRepo activationRepo,
            PlanActivationAckRepo ackRepo,
            UserInfoRepo userInfoRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            OriginLocationRepo originLocationRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo,
            EmergencyContactRepo emergencyContactRepo,
            WebSocketMessageSender ws,
            GroupRepo groupRepo,
            NotificationService notificationService,
            HouseholdAccessService householdAccess,
            HouseholdResolver householdResolver,
            GoBagService goBagService,
            HouseholdEventService householdEventService,
            GroupService groupService
    ) {
        this.activationRepo = activationRepo;
        this.ackRepo = ackRepo;
        this.userInfoRepo = userInfoRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.originLocationRepo = originLocationRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.emergencyContactRepo = emergencyContactRepo;
        this.ws = ws;
        this.groupRepo = groupRepo;
        this.notificationService = notificationService;
        this.householdAccess = householdAccess;
        this.householdResolver = householdResolver;
        this.goBagService = goBagService;
        this.householdEventService = householdEventService;
        this.groupService = groupService;
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
        a.setOperationalMode(normalizeOperationalMode(
                req.operationalMode(),
                req.evacPlanId() != null,
                req.meetingPlaceId() != null,
                req.meetingMode()));
        a.setMovementDirective(normalizeMovementDirective(req.movementDirective()));
        if (req.governingAlert() != null) {
            a.setGoverningAlertSource(trimToNull(req.governingAlert().source()));
            a.setGoverningAlertId(trimToNull(req.governingAlert().id()));
            a.setGoverningAlertEvent(trimToNull(req.governingAlert().event()));
            a.setGoverningAlertHeadline(trimToNull(req.governingAlert().headline()));
            a.setGoverningAlertLifecycleState(trimToNull(req.governingAlert().lifecycleState()));
        }
        a.setMessagePreview(req.messagePreview());

        if (req.location() != null) {
            GeoUtil.requireValidLatLng(req.location().lat(), req.location().lng());
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

        // ── LAUNCHING A PLAN STARTS THE CHECK-IN TOO ──────────────────────
        //
        // The two states stay INDEPENDENT — a household can need one without
        // the other, and chaining them the other way would gate the emergency
        // ping on plan setup, putting somebody with no saved meeting spots on
        // an empty picker mid-emergency. This is the one direction where the
        // coupling is what the owner means: it is the difference between "we
        // told you where to go" and "we told you where to go AND we know who
        // got there".
        //
        // In the SAME transaction as the row above, which is the whole point.
        // Two client calls work on a good connection and, on a bad one,
        // produce exactly the half-state this closes: people evacuating with
        // nobody asked whether they are safe, or asked with nowhere to go.
        // `setAlert` is @Transactional and joins this one; it returns early
        // when the alert is already Active, so a second launch is free.
        alsoStartCheckIn(ownerEmail);

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
                // The frame and the log row, beside the push that was already
                // here. Each is wrapped on its own so one failing does not
                // take the others with it — a household that got the push and
                // no frame is better off than one that got neither.
                announce(saved, "started", ownerEmail);
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
    /**
     * The household this activation belongs to, or null.
     *
     * <p>Extracted from {@link #notifyHouseholdOfActivation}, which is the only
     * place it existed. Every lifecycle side effect needs the same lookup, and
     * three copies of "first Household group this owner is a member of" is how
     * the push and the log start disagreeing about whose household it was.</p>
     */
    private Group householdOf(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) return null;
        return groupRepo.findByMemberEmail(ownerEmail).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Flip the launcher's household into an active check-in.
     *
     * <p>Never fails the activation. A household that got its destination and
     * no ping is worse off than one that got both; a household that got
     * NEITHER because the ping threw is worse off than either.</p>
     */
    private void alsoStartCheckIn(String ownerEmail) {
        try {
            Group hh = householdOf(ownerEmail);
            if (hh != null && hh.getGroupId() != null) {
                groupService.setAlert(hh.getGroupId(), true, ownerEmail);
            }
        } catch (Exception e) {
            log.error("Activation could not start the check-in for owner={}", ownerEmail, e);
        }
    }

    /**
     * Broadcast the lifecycle frame and write the log row.
     *
     * <p>Each side effect is caught separately and NONE of them can fail the
     * write that preceded them. An activation that ended and could not say so
     * is bad; an activation that failed to end because the socket was down
     * would be worse.</p>
     */
    private void announce(PlanActivation a, String state, String byEmail) {
        try {
            ws.sendActivationLifecycle(a.getId(), new ActivationLifecycleFrame(
                    "activation.lifecycle", a.getId(), state, byEmail, Instant.now()));
        } catch (Exception e) {
            log.error("Activation lifecycle frame failed id={} state={}", a.getId(), state, e);
        }
        try {
            Group hh = householdOf(a.getOwnerEmail());
            if (hh != null) {
                if ("ended".equals(state)) {
                    householdEventService.recordActivationEnded(hh.getGroupId(), byEmail, a.getId());
                } else {
                    householdEventService.recordActivationStarted(hh.getGroupId(), byEmail, a.getId());
                }
            }
        } catch (Exception e) {
            log.error("Activation event log failed id={} state={}", a.getId(), state, e);
        }
    }

    /** The frame, the log row, and the push that the end never had. */
    private void announceEnd(PlanActivation a, String endedBy) {
        announce(a, "ended", endedBy);
        try {
            notifyHouseholdOfEnd(a, endedBy);
        } catch (Exception e) {
            log.error("Activation end push fan-out failed id={}", a.getId(), e);
        }
    }

    /**
     * Tell the household it is over.
     *
     * <p>Deliberately the SAME shape as the activation push — same batching,
     * same presence-aware delivery, same never-blocks-the-write contract. It
     * uses the ALL-CLEAR wording the app already shows on a calm board, so the
     * push and the screen the reader lands on say the same thing.</p>
     *
     * <p>The person who ended it is skipped, exactly as the activator is on
     * create: they are looking at the result already.</p>
     */
    private void notifyHouseholdOfEnd(PlanActivation a, String endedBy) {
        Group household = householdOf(a.getOwnerEmail());
        if (household == null || household.getMemberEmails() == null || household.getMemberEmails().isEmpty()) {
            return;
        }
        String enderName = endedBy == null ? null : userInfoRepo.findByUserEmailIgnoreCase(endedBy)
                .map(u -> joinName(u.getUserFirstName(), u.getUserLastName()))
                .filter(str -> str != null && !str.isBlank())
                .orElse(null);

        String title = "All clear";
        String body = enderName != null
                ? enderName + " ended the plan. Your household is no longer evacuating."
                : "The plan has ended. Your household is no longer evacuating.";
        String targetUrl = "/deployedplan?activationId=" + a.getId();

        List<UserInfo> members = userInfoRepo.findByUserEmailIn(household.getMemberEmails());
        for (UserInfo m : members) {
            if (m.getUserEmail() == null) continue;
            if (endedBy != null && m.getUserEmail().equalsIgnoreCase(endedBy)) continue;
            notificationService.deliverPresenceAware(
                    m.getUserEmail(), title, body, enderName == null ? "Your household" : enderName,
                    "/images/plan-icon.png", "plan_activation_ended", a.getId(),
                    targetUrl, null, m.getFcmtoken(),
                    PushPolicyService.Category.PLAN_ACTIVATION_RECEIVED
            );
        }
        log.info("Activation {} end pushed to {} household member(s)", a.getId(), members.size());
    }

    private void notifyHouseholdOfActivation(String ownerEmail, String activationId) {
        Group household = householdOf(ownerEmail);
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
                .map(a -> isAuthorizedReader(a, callerEmail)
                        ? toDetailDto(a, canEnd(a, callerEmail))
                        : toRecipientDetailDto(a));
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

    // ---------------------------------------------------------------------
    // End
    // ---------------------------------------------------------------------

    /**
     * The household says it is over.
     *
     * <p>Until this existed the only thing that stopped an activation was its
     * 72-hour {@code expiresAt}, so "we are done evacuating" was a sentence a
     * household could not say. The most recent production row is the defect:
     * activated 2026-08-29 17:31, still EVACUATING on every household surface
     * until the timer ran out three days later.</p>
     *
     * <h4>Who may call it</h4>
     * The owner, or a household co-member ({@link HouseholdAccessService#canReadPlanDataFor}).
     * NOT a link holder, and that asymmetry is deliberate: {@link #getActivation}
     * serves anyone holding the link because reading is recoverable, but a link
     * can be forwarded, screenshotted or pasted into a group chat, and "anyone
     * with the link may declare the evacuation over" is the mirror image of the
     * failure this method exists to fix. A co-member gets it because the owner may
     * be the person who is unreachable — which is the case where it matters most.
     *
     * <h4>What it deliberately does NOT do</h4>
     * <ul>
     *   <li><b>It does not stop acks.</b> The owner ends it because everyone THEY
     *       CAN SEE is safe; the straggler who has not replied is exactly the
     *       person whose "I need help" must still land. {@link #recordAck} checks
     *       expiry only, and that is not an oversight.</li>
     *   <li><b>It does not 410 the link.</b> {@link #getActivation} keeps serving
     *       the detail with {@code closed=true} and an {@code endedAt}, so a
     *       recipient who opens it ten minutes late learns what happened rather
     *       than that something did.</li>
     * </ul>
     *
     * <p>Idempotent: ending an already-ended activation returns the existing
     * record without moving {@code endedAt}. Two household members tapping End at
     * once must not produce two different "over at" times for one event.</p>
     *
     * @return the activation's detail as the caller is entitled to see it
     */
    @Transactional
    public ActivationDetailDto endActivation(String activationId, String callerEmail) {
        PlanActivation a = activationRepo.findById(activationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation not found"));

        String caller = callerEmail == null ? null : callerEmail.trim().toLowerCase(Locale.ROOT);
        if (!canEnd(a, caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only this household can end its own activation");
        }

        if (a.getEndedAt() == null) {
            a.setEndedAt(Instant.now());
            a.setEndedByEmail(caller);
            a = activationRepo.save(a);
            log.info("Activation ended id={} owner={} by={}",
                    a.getId(), a.getOwnerEmail(), caller);

            // ── ENDING USED TO BROADCAST NOTHING ─────────────────────────
            //
            // This method wrote `endedAt` and returned. The person who tapped
            // it saw the response; every other household member kept reading
            // EVACUATING until their device happened to refetch /me, and
            // nothing caused it to. That silence is what left a household in
            // EVACUATING for three days on 2026-08-29 — the End button closed
            // the state, not the silence.
            //
            // After commit, for the reason create already does it: the row has
            // to be durable before anyone is told about it.
            final PlanActivation ended = a;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { announceEnd(ended, caller); }
                });
            } else {
                // A helper that explodes depending on how it was reached is a
                // trap for the next caller, and the write has already happened.
                announceEnd(ended, caller);
            }
        }

        return toDetailDto(a, true);   // it just ended it; it can.
    }

    /**
     * Broadcast the endings the 72-hour timer produces, so a timeout looks like
     * an ending to every client instead of a row that quietly stops matching.
     *
     * <h4>Why this exists at all</h4>
     * Every other way an activation ends says so: create pushes and broadcasts,
     * {@link #endActivation} broadcasts, pushes and logs. The timer did neither.
     * It ended an activation by ceasing to satisfy {@code expiresAt > now}, and
     * a client only found out if something else happened to make it refetch
     * {@code /me}. Nothing did.
     *
     * <h4>What it deliberately does NOT do</h4>
     * <ul>
     *   <li><b>It does not set {@code endedAt}.</b> See
     *       {@link PlanActivation#expiryHandledAt} — that field means a person
     *       said so, and the recipient surface renders "Your household ended
     *       this" from it.</li>
     *   <li><b>It does not push.</b> See {@link #announceExpiry}.</li>
     *   <li><b>It does not delete.</b> That is the sweep's other pass, fourteen
     *       days later, and the grace window is deliberate.</li>
     * </ul>
     *
     * <p>Idempotent through {@code expiryHandledAt}: a second tick over the same
     * row broadcasts nothing. Without that the hourly job would put one more
     * "the plan ended" row into the household's history every hour for two
     * weeks.</p>
     *
     * @return how many timed-out activations were announced
     */
    @Transactional
    public int handleExpiredActivations(int batchSize) {
        if (batchSize <= 0) return 0;
        Instant now = Instant.now();
        List<PlanActivation> due = activationRepo.findExpiredNotHandled(now, PageRequest.of(0, batchSize));
        if (due.isEmpty()) return 0;

        final List<PlanActivation> handled = new ArrayList<>(due.size());
        for (PlanActivation a : due) {
            a.setExpiryHandledAt(now);
            handled.add(activationRepo.save(a));
        }
        log.info("Activation expiry: {} activation(s) timed out", handled.size());

        // After commit, for the same reason create and endActivation do it: the
        // mark has to be durable before anyone is told, or a crash between the
        // two broadcasts an ending the database does not agree happened.
        //
        // The else-branch is not defensive padding. The sweep can call this
        // outside a transaction, and registerSynchronization THROWS when no
        // synchronization is active — a helper that explodes depending on how it
        // was reached is a trap for the next caller.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    handled.forEach(PlanActivationService.this::announceExpiry);
                }
            });
        } else {
            handled.forEach(this::announceExpiry);
        }
        return handled.size();
    }

    /**
     * End EVERY live activation in one household, in one transaction — the
     * write behind "All clear".
     *
     * <h4>Why {@link #endActivation} is not enough</h4>
     * An activation is keyed on the OWNER's email, not the household's, so two
     * people launching a plan produces two rows.
     * {@code MeService.resolveActiveActivationIdForHome} then takes
     * {@code max(activatedAt)} across every household member — so ending the
     * newest one lets the resolver fall back to the older one, Home stays
     * EVACUATING, and the person who just declared it over watches it come
     * back. A per-row endpoint cannot fix that from the client either: the
     * client does not know how many rows there are.
     *
     * <p>One transaction, so the household cannot end up half stood-down.</p>
     *
     * <h4>ONE {@code endedAt} across every row</h4>
     * All clear is one statement about the household, not N separate endings.
     * Two rows closed by one tap that disagree by 40ms would be a distinction
     * with no referent — and the household timeline would show two times for
     * one event.
     *
     * <h4>One push, N frames</h4>
     * The frames are per activation because the topic is
     * {@code /topic/activations/{id}/plan} — a recipient watching a shared link
     * has an id and nothing else, so every row has to speak on its own channel.
     * The PUSH is sent once. The household is being told one thing, and two
     * "All clear" notifications for one tap is the app narrating its own
     * schema.
     *
     * <p><b>Authorization is the caller's household membership</b>, checked at
     * the resource. Same bar as {@link #endActivation}'s co-member branch and
     * for the same reason: the owner may be the person who is unreachable.</p>
     *
     * <p>Idempotent — a household with nothing live returns a zero count and
     * broadcasts nothing.</p>
     */
    @Transactional
    public HouseholdActivationsEndedDto endHouseholdActivations(String householdId, String callerEmail) {
        Group household = groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));

        String caller = callerEmail == null ? null : callerEmail.trim().toLowerCase(Locale.ROOT);
        Instant now = Instant.now();

        // The SAME candidate set MeService resolves Home's activation from —
        // owner plus every member — because the whole defect is those two
        // reading a different set. A row this misses stays live and Home keeps
        // showing it.
        Set<String> owners = new LinkedHashSet<>();
        addOwnerEmail(owners, household.getOwnerEmail());
        if (household.getMemberEmails() != null) {
            household.getMemberEmails().forEach(raw -> addOwnerEmail(owners, raw));
        }

        // The lowercased SET above is the dedupe, and it is the only one needed:
        // an activation has exactly one owner, and `findActiveByOwnerEmail`
        // matches on LOWER(ownerEmail), so one row can be returned by one email
        // only. A second dedupe keyed on the activation id was here and is gone
        // — re-arming it proved nothing could reach it, which makes it a comment
        // pretending to be a guard.
        List<PlanActivation> live = new ArrayList<>();
        for (String owner : owners) {
            live.addAll(activationRepo.findActiveByOwnerEmail(owner, now));
        }
        if (live.isEmpty()) {
            return new HouseholdActivationsEndedDto(householdId, 0, List.of(), null);
        }

        Instant endedAt = Instant.now();
        final List<PlanActivation> ended = new ArrayList<>(live.size());
        for (PlanActivation a : live) {
            a.setEndedAt(endedAt);
            a.setEndedByEmail(caller);
            ended.add(activationRepo.save(a));
        }
        log.info("All clear: household={} ended {} activation(s) by={}",
                householdId, ended.size(), caller);

        // The other half of the same statement, and in the same transaction
        // for the same reason. The client used to make this a SECOND call —
        // which on a dropped connection left the household with no evacuation
        // and an open check-in.
        groupService.setAlert(householdId, false, caller);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { announceHouseholdEnd(ended, caller); }
            });
        } else {
            announceHouseholdEnd(ended, caller);
        }

        return new HouseholdActivationsEndedDto(
                householdId, ended.size(),
                ended.stream().map(PlanActivation::getId).toList(),
                endedAt);
    }

    /** Every row gets its own frame and log line; the household gets ONE push. */
    private void announceHouseholdEnd(List<PlanActivation> ended, String caller) {
        for (PlanActivation a : ended) {
            announce(a, "ended", caller);
        }
        // The newest, because that is the one Home was showing and the one the
        // notification's deep link should open.
        ended.stream()
                .max(Comparator.comparing(PlanActivation::getActivatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .ifPresent(newest -> {
                    try {
                        notifyHouseholdOfEnd(newest, caller);
                    } catch (Exception e) {
                        log.error("All clear push fan-out failed household activation={}", newest.getId(), e);
                    }
                });
    }

    private static void addOwnerEmail(Set<String> into, String raw) {
        if (raw == null) return;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!v.isEmpty()) into.add(v);
    }

    /**
     * The frame and the log row for a timer ending — {@link #announce}, not
     * {@link #announceEnd}, and the difference is the push.
     *
     * <p>{@code announceEnd}'s copy is "All clear — your household is no longer
     * evacuating." A clock running out is not the household saying all clear,
     * and {@code PLAN_ACTIVATION_RECEIVED} is a Lane A category that BYPASSES
     * QUIET HOURS — so a 72-hour timer expiring at 3am would wake every member
     * to report a timer. That is manufactured urgency about a non-event.</p>
     *
     * <p>The actor is null, which is the household event log's own documented
     * signal for "the timer did this rather than a person"; the chat renders
     * that row without naming anyone.</p>
     */
    private void announceExpiry(PlanActivation a) {
        announce(a, "ended", null);
    }

    /**
     * Is this activation over — by EITHER route?
     *
     * <p>One predicate, because the same expression was written in three places
     * ({@code toDetailDto}, {@code toRecipientDetailDto}, {@code toActiveSituation})
     * and a fourth reader that checked only expiry would keep an ended activation
     * alive on exactly one surface. That disagreement between surfaces is the
     * whole defect this change closes; reproducing it inside the fix would be
     * a poor joke.</p>
     */
    /**
     * May {@code caller} end this activation?
     *
     * <p>The owner or a household co-member — NOT the third branch of
     * {@link #isAuthorizedReader} (an explicitly-targeted recipient by id), and
     * not a link holder. Shipped to the client as
     * {@code ActivationDetailDto.viewerCanEnd} so the frontend renders a
     * capability the server computed rather than inferring household membership
     * from the shape of the payload it got back.</p>
     */
    private boolean canEnd(PlanActivation a, String callerEmail) {
        if (callerEmail == null) return false;
        String caller = callerEmail.trim().toLowerCase(Locale.ROOT);
        if (caller.isEmpty()) return false;
        return caller.equalsIgnoreCase(a.getOwnerEmail())
                || householdAccess.canReadPlanDataFor(caller, a.getOwnerEmail());
    }

    private static boolean isClosed(PlanActivation a) {
        return a.getEndedAt() != null || Instant.now().isAfter(a.getExpiresAt());
    }

    private PlanActivation requireActiveActivation(String activationId) {
        PlanActivation a = activationRepo.findById(activationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activation not found"));
        if (Instant.now().isAfter(a.getExpiresAt())) {
            throw new ActivationExpiredException(activationId);
        }
        return a;
    }

    /**
     * WS SUBSCRIBE gate (SEC follow-up 2026-07-07): may {@code callerEmail}
     * stream this activation's ack frames? The acks topic carries recipient
     * PII + live coordinates, so it mirrors the REST acks endpoint's
     * owner/household/targeted-member authorization ({@link #isAuthorizedReader})
     * instead of the link-possession contract. Missing activations deny;
     * expiry is not checked here (an expired stream just goes quiet — the
     * sweep hard-deletes it later, after which this denies).
     */
    @Transactional(readOnly = true)
    public boolean canReadActivationAcks(String activationId, String callerEmail) {
        if (activationId == null || callerEmail == null || callerEmail.isBlank()) return false;
        return activationRepo.findById(activationId)
                .map(a -> isAuthorizedReader(a, callerEmail.trim().toLowerCase(Locale.ROOT)))
                .orElse(false);
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
                null,                             // groupType — activation places are not circles
                null, null, address, placeLabel,  // postId, kind, description(=address), placeLabel
                null, null, null, null,           // category, website, externalMapUrl, attribution
                null                              // logoImageUrl — not a circle
        );
    }

    private static boolean finite(Double lat, Double lng) {
        return lat != null && lng != null
                && !lat.isNaN() && !lng.isNaN()
                && !lat.isInfinite() && !lng.isInfinite();
    }

    private static String normalizeOperationalMode(
            String raw,
            boolean hasEvacDestination,
            boolean hasMeetingPlace,
            String meetingMode
    ) {
        String key = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (Set.of("NORMAL", "PREPARING", "GATHERING", "SHELTERING", "EVACUATING", "RECOVERY").contains(key)) {
            return key;
        }
        if (hasEvacDestination) return "EVACUATING";
        if (hasMeetingPlace) return "GATHERING";
        String meet = meetingMode == null ? "" : meetingMode.trim().toLowerCase(Locale.ROOT);
        if ("stay-home".equals(meet) || "stay_home".equals(meet)) return "SHELTERING";
        return "PREPARING";
    }

    private static String normalizeMovementDirective(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "evacuate" -> "evacuate";
            case "shelter_in_place" -> "shelter_in_place";
            case "avoid_area" -> "avoid_area";
            case "follow_official_instruction" -> "follow_official_instruction";
            default -> "none";
        };
    }

    private static String resolveEffectiveMode(String requested, String movementDirective) {
        return switch (normalizeMovementDirective(movementDirective)) {
            case "evacuate" -> "EVACUATING";
            case "shelter_in_place" -> "SHELTERING";
            default -> normalizeOperationalMode(requested, false, false, null);
        };
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

        // This endpoint is un-authed (link possession), so the write boundary
        // must not trust the payload: when the owner targeted specific
        // recipients, recipientEmail must resolve to one of them (owner
        // included) — otherwise anyone holding a leaked link could inject a
        // fake "I'm safe at X" for an arbitrary identity during a live
        // emergency. Untargeted activations (bare share link, no recipient
        // sets) keep the open link-possession contract.
        requireRecipientAllowed(activation, recipientEmail);

        // AN ACK IS A SAFETY STATUS FIRST AND A LOCATION SECOND.
        //
        // This used to call GeoUtil.requireValidLatLng, which throws — and
        // therefore 400s the whole request — on a half-null pair, a non-finite
        // value, or an out-of-range one. That meant a coordinate problem cost
        // the person's entire "I'm safe", which is the wrong failure
        // direction: the status is the payload that matters in an emergency
        // and the coordinate is an enrichment on top of it.
        //
        // Now the coordinates degrade and the status always lands. A pair that
        // does not validate is stored as no-location, exactly like a recipient
        // who declined to share. Note this is not a weakening of the write
        // boundary — nothing invalid is persisted either way, and bounds
        // checking never defended against a *plausible* fake location anyway;
        // requireRecipientAllowed above is the gate that does that work.
        boolean plottable = GeoUtil.validLatLng(req.lat(), req.lng());

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
        ack.setLat(plottable ? req.lat() : null);
        ack.setLng(plottable ? req.lng() : null);
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

    /**
     * When the activation carries targeted recipients, the acking identity
     * must be one of them. 403 otherwise. Activations created with NO
     * explicit recipient targeting (bare share links) accept any identity —
     * the link is the audience there. Recipients resolve to emails via: the
     * owner, targeted household members (UserInfo ids), directly-targeted
     * emergency contacts, and every contact inside targeted contact
     * groups. Contacts stored without an email (phone-only) cannot be
     * matched and are effectively excluded from email acks.
     *
     * IMPORTANT (2026-07-07): contactGroupIds do NOT flip an activation to
     * "targeted". The FE auto-attaches every saved contact group so the
     * recipient view can render its "Key contacts" card — treating them as
     * targeting 403-blocked household co-members (the FE never sends
     * householdMemberIds) and every guest device-id identity: the exact
     * people the activation push tells to check in. Targeting is explicit —
     * householdMemberIds / contactIds only.
     */
    private void requireRecipientAllowed(PlanActivation a, String recipientEmail) {
        boolean targeted =
                (a.getHouseholdMemberIds() != null && !a.getHouseholdMemberIds().isEmpty())
                || (a.getContactIds() != null && !a.getContactIds().isEmpty());
        if (!targeted) return;

        if (!resolveRecipientEmails(a).contains(recipientEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This plan was not shared with that recipient");
        }
    }

    /** Lowercased email set the activation was addressed to (owner included). */
    private Set<String> resolveRecipientEmails(PlanActivation a) {
        Set<String> allowed = new HashSet<>();
        if (a.getOwnerEmail() != null) allowed.add(a.getOwnerEmail().toLowerCase(Locale.ROOT));

        if (a.getHouseholdMemberIds() != null && !a.getHouseholdMemberIds().isEmpty()) {
            userInfoRepo.findAllById(a.getHouseholdMemberIds()).forEach(u -> {
                if (u.getUserEmail() != null && !u.getUserEmail().isBlank()) {
                    allowed.add(u.getUserEmail().trim().toLowerCase(Locale.ROOT));
                }
            });
        }
        if (a.getContactIds() != null && !a.getContactIds().isEmpty()) {
            emergencyContactRepo.findAllById(a.getContactIds()).forEach(c -> {
                if (c.getEmail() != null && !c.getEmail().isBlank()) {
                    allowed.add(c.getEmail().trim().toLowerCase(Locale.ROOT));
                }
            });
        }
        if (a.getContactGroupIds() != null && !a.getContactGroupIds().isEmpty()) {
            emergencyContactGroupRepo.findAllById(a.getContactGroupIds()).forEach(g -> {
                if (g.getContacts() == null) return;
                g.getContacts().forEach(c -> {
                    if (c.getEmail() != null && !c.getEmail().isBlank()) {
                        allowed.add(c.getEmail().trim().toLowerCase(Locale.ROOT));
                    }
                });
            });
        }
        return allowed;
    }

    // ---------------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------------

    private ActivationDetailDto toDetailDto(PlanActivation a, boolean viewerCanEnd) {
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

        boolean closed = isClosed(a);
        LocationDto location = (a.getLat() == null && a.getLng() == null)
                ? null : new LocationDto(a.getLat(), a.getLng());

        // "Grab before you go" — household audience only. Resolve the owner's
        // base household and attach its go-bag snapshots. Best-effort: a bad
        // lookup must not sink the whole detail read.
        List<GoBagSnapshotDto> goBags = List.of();
        try {
            String householdId = householdResolver.baseHouseholdIdFor(a.getOwnerEmail());
            if (householdId != null) {
                goBags = goBagService.snapshotsForHousehold(householdId);
            }
        } catch (Exception e) {
            log.warn("activation {} go-bag snapshot failed: {}", a.getId(), e.getMessage());
        }

        AckRollupDto ackRollup = computeAckRollup(acks);
        return new ActivationDetailDto(
                a.getId(),
                a.getOwnerUserId(),
                a.getOwnerName(),
                a.getActivatedAt(),
                a.getExpiresAt(),
                closed,
                a.getEndedAt(),
                viewerCanEnd,
                a.getMeetingMode(),
                a.getEvacMode(),
                a.getMessagePreview(),
                location,
                mp,
                ep,
                ecgs,
                goBags,
                acks,
                ackRollup,
                toActiveSituation(a, mp, ep, ackRollup)
        );
    }

    /**
     * Reduce the ack list to a status rollup — mirrors the FE reduce in
     * {@code OwnerAckRollup} (safe / help / pickup, everything else "other").
     * The live board recomputes this over the STOMP-streamed list; this is
     * the authoritative snapshot for non-live consumers.
     */
    private static AckRollupDto computeAckRollup(List<AckDto> acks) {
        if (acks == null || acks.isEmpty()) {
            return new AckRollupDto(0, 0, 0, 0, 0);
        }
        int safe = 0, help = 0, pickup = 0, other = 0;
        for (AckDto a : acks) {
            String k = a.status() == null ? "" : a.status().trim().toLowerCase(Locale.ROOT);
            switch (k) {
                case "safe" -> safe++;
                case "help" -> help++;
                case "pickup" -> pickup++;
                default -> other++;
            }
        }
        return new AckRollupDto(acks.size(), safe, help, pickup, other);
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

        boolean closed = isClosed(a);
        LocationDto location = (a.getLat() == null && a.getLng() == null)
                ? null : new LocationDto(a.getLat(), a.getLng());

        return new ActivationDetailDto(
                a.getId(), a.getOwnerUserId(), a.getOwnerName(),
                a.getActivatedAt(), a.getExpiresAt(), closed, a.getEndedAt(),
                false,      // viewerCanEnd — a link holder never ends a household's activation
                a.getMeetingMode(), a.getEvacMode(), a.getMessagePreview(),
                location, mp, ep, ecgs,
                List.of(),  // goBags — a link holder never sees bag storage locations
                List.of(),  // acks — a recipient never sees the roll-up
                null,       // ackRollup — owner/household audience only
                toActiveSituation(a, mp, ep, null)
        );
    }

    private ActiveSituationDto toActiveSituation(
            PlanActivation a,
            MeetingPlaceSnapshotDto mp,
            EvacuationPlanSnapshotDto ep,
            AckRollupDto rollup
    ) {
        String requested = normalizeOperationalMode(
                a.getOperationalMode(),
                a.getEvacPlanId() != null,
                a.getMeetingPlaceId() != null,
                a.getMeetingMode());
        GoverningAlertDto governingAlert = activeGoverningAlert(a);
        String movement = governingAlert == null ? "none" : normalizeMovementDirective(a.getMovementDirective());
        String effective = resolveEffectiveMode(requested, movement);
        Instant now = Instant.now();
        String primary;
        String kind;
        String suppressed = null;
        String reason = null;

        if ("avoid_area".equals(movement)) {
            primary = "Avoid the affected area";
            kind = "avoid";
            if (mp != null || ep != null) {
                suppressed = "Plan navigation";
                reason = "Official movement guidance says to avoid the affected area.";
            }
        } else if ("follow_official_instruction".equals(movement)) {
            primary = "Follow official instructions";
            kind = "official";
            if (mp != null || ep != null) {
                suppressed = "Plan navigation";
                reason = "Official movement guidance must be read before following saved destinations.";
            }
        } else if ("EVACUATING".equals(effective)) {
            primary = ep != null
                    ? "Go to the evacuation destination"
                    : "Follow official evacuation instructions";
            kind = "evacuate";
            if (mp != null) {
                suppressed = "Navigate to meeting place";
                reason = "Official movement guidance is evacuation-first.";
            }
        } else if ("SHELTERING".equals(effective)) {
            primary = "Shelter in place";
            kind = "shelter";
            if (mp != null) {
                suppressed = "Navigate to meeting place";
                reason = "Official movement guidance says to shelter in place.";
            }
        } else if ("GATHERING".equals(effective)) {
            primary = mp != null ? "Go to the active meeting place" : "Stay where you are";
            kind = mp != null ? "meet" : "stay";
        } else if ("PREPARING".equals(effective)) {
            primary = "Get ready and watch for updates";
            kind = "prepare";
        } else if ("RECOVERY".equals(effective)) {
            primary = "Check in and start recovery steps";
            kind = "recover";
        } else {
            primary = "Stay available for updates";
            kind = "normal";
        }

        return new ActiveSituationDto(
                a.getId(),
                isClosed(a) ? "closed" : "active",
                a.getEndedAt(),
                a.getActivatedAt(),
                a.getActivatedAt(),
                requested,
                effective,
                movement,
                mp,
                ep,
                governingAlert,
                rollup,
                primary,
                kind,
                suppressed,
                reason
        );
    }

    private GoverningAlertDto activeGoverningAlert(PlanActivation a) {
        String lifecycle = trimToNull(a.getGoverningAlertLifecycleState());
        String normalized = lifecycle == null ? null : lifecycle.trim().toLowerCase(Locale.ROOT);
        if (isTerminalAlertLifecycle(normalized)) {
            return null;
        }
        if (trimToNull(a.getGoverningAlertId()) == null
                && trimToNull(a.getGoverningAlertHeadline()) == null
                && trimToNull(a.getGoverningAlertEvent()) == null) {
            return null;
        }
        return new GoverningAlertDto(
                trimToNull(a.getGoverningAlertSource()),
                trimToNull(a.getGoverningAlertId()),
                trimToNull(a.getGoverningAlertEvent()),
                trimToNull(a.getGoverningAlertHeadline()),
                lifecycle
        );
    }

    private static boolean isTerminalAlertLifecycle(String lifecycle) {
        return "expired".equals(lifecycle) || "ended".equals(lifecycle)
                || "cancelled".equals(lifecycle) || "canceled".equals(lifecycle)
                || "superseded".equals(lifecycle);
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
