package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdEventDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.PublicCdn;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Records and serves household activity events. Replaces the frontend's
 * synthesis of "system event" rows in the household chat — see
 * {@link io.sitprep.sitprepapi.domain.HouseholdEvent} javadoc for the
 * vocabulary.
 *
 * <p>The {@code record*} methods are designed to be called from existing
 * services after their primary mutation completes — wiring is "fire and
 * forget" from the caller's POV (failures are logged, not rethrown, so an
 * event recorder bug never breaks a status update).</p>
 */
@Service
public class HouseholdEventService {

    /** Marker on the Group entity that distinguishes household groups. */
    public static final String HOUSEHOLD_GROUP_TYPE = "Household";

    private static final Logger log = LoggerFactory.getLogger(HouseholdEventService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final HouseholdEventRepo eventRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final WebSocketMessageSender ws;
    private final ObjectMapper objectMapper;

    public HouseholdEventService(HouseholdEventRepo eventRepo,
                                 UserInfoRepo userInfoRepo,
                                 GroupRepo groupRepo,
                                 WebSocketMessageSender ws,
                                 ObjectMapper objectMapper) {
        this.eventRepo = eventRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.ws = ws;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // Public read API
    // ---------------------------------------------------------------------

    /**
     * Sentinels for "no time bound" — the repo query takes required
     * Instant params (Postgres can't infer types of bare nullable
     * params on the left of IS NULL). See sibling fix in
     * NotificationInboxService.
     */
    private static final Instant FAR_PAST = Instant.EPOCH;
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T23:59:59Z");

    public List<HouseholdEventDto> list(String householdId, Instant since, Instant before) {
        if (householdId == null || householdId.isBlank()) return List.of();
        Instant sinceBound  = (since  != null) ? since  : FAR_PAST;
        Instant beforeBound = (before != null) ? before : FAR_FUTURE;
        List<HouseholdEvent> rows = eventRepo.findRange(householdId, sinceBound, beforeBound);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(HouseholdEvent::getActorEmail)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = emails.isEmpty()
                ? Map.of()
                : userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                    .collect(Collectors.toMap(
                            u -> u.getUserEmail().toLowerCase(Locale.ROOT),
                            Function.identity(),
                            (a, b) -> a));

        return rows.stream().map(e -> toDto(e, userByEmail)).toList();
    }

    // ---------------------------------------------------------------------
    // Public write API — fire-and-forget recorders
    // ---------------------------------------------------------------------

    /**
     * Record a status change for one user across every household they
     * belong to. The frontend's chat surface lives on the household page,
     * so the same status update flows into 0, 1, or N household feeds
     * depending on the user's group memberships.
     */
    public void recordStatusChangedForActor(String actorEmail, String newStatus) {
        if (actorEmail == null || actorEmail.isBlank() || newStatus == null) return;
        List<String> households = householdsForMember(actorEmail);
        if (households.isEmpty()) return;
        Map<String, Object> payload = Map.of("status", newStatus);
        for (String hh : households) {
            recordSafely(hh, "status-changed", actorEmail, payload);
        }
    }

    /**
     * Record a check-in start/end on a specific household (the alert
     * toggle on the Group entity). One event per call.
     */
    public void recordCheckinStarted(String householdId, String actorEmail) {
        recordSafely(householdId, "checkin-started", actorEmail, Map.of());
    }

    public void recordCheckinEnded(String householdId, String actorEmail) {
        recordSafely(householdId, "checkin-ended", actorEmail, Map.of());
    }

    public void recordWithClaim(String householdId, String actorEmail, String subjectEmail) {
        Map<String, Object> payload = subjectEmail == null
                ? Map.of() : Map.of("subjectEmail", subjectEmail);
        recordSafely(householdId, "with-claim", actorEmail, payload);
    }

    public void recordWithRelease(String householdId, String actorEmail, String subjectEmail) {
        Map<String, Object> payload = subjectEmail == null
                ? Map.of() : Map.of("subjectEmail", subjectEmail);
        recordSafely(householdId, "with-release", actorEmail, payload);
    }

    public void recordMemberAdded(String householdId, String actorEmail, String subjectEmail) {
        recordSafely(householdId, "member-added", actorEmail,
                subjectEmail == null ? Map.of() : Map.of("subjectEmail", subjectEmail));
    }

    public void recordMemberRemoved(String householdId, String actorEmail, String subjectEmail) {
        recordSafely(householdId, "member-removed", actorEmail,
                subjectEmail == null ? Map.of() : Map.of("subjectEmail", subjectEmail));
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void recordSafely(String householdId, String kind,
                              String actorEmail, Map<String, Object> payload) {
        if (householdId == null || householdId.isBlank() || kind == null) return;
        try {
            HouseholdEvent e = new HouseholdEvent();
            e.setHouseholdId(householdId);
            e.setKind(kind);
            e.setAt(Instant.now());
            e.setActorEmail(actorEmail == null ? null : actorEmail.toLowerCase(Locale.ROOT));
            e.setPayloadJson(payload == null || payload.isEmpty()
                    ? null : objectMapper.writeValueAsString(payload));
            HouseholdEvent saved = eventRepo.save(e);

            HouseholdEventDto dto = toDto(saved, resolveActorMap(saved.getActorEmail()));
            broadcastAfterCommit(householdId, dto);
        } catch (Exception ex) {
            // Recorder failures should never break the caller's primary
            // mutation. Log and move on.
            log.warn("Failed to record household event {}/{} actor={}: {}",
                    householdId, kind, actorEmail, ex.getMessage());
        }
    }

    private Map<String, UserInfo> resolveActorMap(String actorEmail) {
        if (actorEmail == null) return Map.of();
        return userInfoRepo.findByUserEmail(actorEmail)
                .map(u -> Map.of(actorEmail.toLowerCase(Locale.ROOT), u))
                .orElse(Map.of());
    }

    private List<String> householdsForMember(String email) {
        if (email == null || email.isBlank()) return List.of();
        return groupRepo.findByMemberEmail(email).stream()
                .filter(g -> HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType()))
                .map(Group::getGroupId)
                .filter(Objects::nonNull)
                .toList();
    }

    private void broadcastAfterCommit(String householdId, HouseholdEventDto dto) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    ws.sendHouseholdEvent(householdId, dto);
                }
            });
        } else {
            ws.sendHouseholdEvent(householdId, dto);
        }
    }

    private HouseholdEventDto toDto(HouseholdEvent e, Map<String, UserInfo> userByEmail) {
        UserInfo actor = e.getActorEmail() == null
                ? null : userByEmail.get(e.getActorEmail().toLowerCase(Locale.ROOT));
        String actorName = actor == null ? null : actor.getUserFirstName();
        String actorImg = actor == null ? null : PublicCdn.toPublicUrl(actor.getProfileImageURL());

        Map<String, Object> payload;
        if (e.getPayloadJson() == null || e.getPayloadJson().isBlank()) {
            payload = Map.of();
        } else {
            try {
                payload = objectMapper.readValue(e.getPayloadJson(), MAP_TYPE);
            } catch (Exception ex) {
                log.warn("Bad payloadJson on event {}: {}", e.getId(), ex.getMessage());
                payload = Map.of();
            }
        }

        return new HouseholdEventDto(
                e.getId(),
                e.getHouseholdId(),
                e.getKind(),
                e.getAt(),
                e.getActorEmail(),
                actorName,
                actorImg,
                payload
        );
    }
}
