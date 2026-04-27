package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.*;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final WebSocketMessageSender ws;

    public PlanActivationService(
            PlanActivationRepo activationRepo,
            PlanActivationAckRepo ackRepo,
            UserInfoRepo userInfoRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo,
            WebSocketMessageSender ws
    ) {
        this.activationRepo = activationRepo;
        this.ackRepo = ackRepo;
        this.userInfoRepo = userInfoRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.ws = ws;
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

        return new ActivationCreatedDto(saved.getId(), saved.getExpiresAt());
    }

    // ---------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<ActivationDetailDto> getActivation(String activationId) {
        return activationRepo.findById(activationId).map(this::toDetailDto);
    }

    @Transactional(readOnly = true)
    public List<AckDto> getAcks(String activationId) {
        return ackRepo.findByActivationIdOrderByAckedAtAsc(activationId).stream()
                .map(this::toAckDto)
                .toList();
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

        PlanActivationAck saved = ackRepo.save(ack);
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
                c.getAddress(), c.getRadioChannel(), c.getMedicalInfo()
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
