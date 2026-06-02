package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdEventDto;
import io.sitprep.sitprepapi.dto.WeeklyCheckInResultDto;
import io.sitprep.sitprepapi.dto.WeeklyCheckInSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.PublicCdn;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * Record a system-fired check-in reminder. The reminder service
     * (see {@code GroupCheckInReminderService}) dispatches up to 5
     * reminders during the 48h check-in window. {@code slotIndex} is
     * 0..4 corresponding to 30min / 4h / 12h / 24h / 36h. Stored
     * with {@code actorEmail = null} since the system fired it.
     */
    public void recordCheckinReminder(String householdId, int slotIndex) {
        recordSafely(householdId, "checkin-reminder", null,
                Map.of("slotIndex", slotIndex));
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
    // §6 of docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md — member micro-actions
    //
    // Members confirm "I know my meeting spot / who to call / my evac
    // route" — gives the non-admin side of the household a daily
    // 30-second action with social meaning (the admin sees the
    // confirmation in their activity feed). Closes the member-activation
    // gap: §6 should move the "non-admins taking ≥1 weekly action" rate
    // from near-zero to >30%.
    //
    // Unlike the fire-and-forget recorders above, member confirmations
    // are USER-INITIATED — the FE expects an event back so it can
    // optimistically render the "Confirmed ✓" state. So these throw on
    // unknown kinds instead of silently dropping, and return the saved
    // DTO instead of void.
    // ---------------------------------------------------------------------

    public static final String KIND_MEMBER_CONFIRMED_MEETING = "member-confirmed-meeting";
    public static final String KIND_MEMBER_CONFIRMED_CONTACTS = "member-confirmed-contacts";
    public static final String KIND_MEMBER_CONFIRMED_EVAC = "member-confirmed-evac";

    private static final Set<String> ALLOWED_MEMBER_CONFIRMATION_KINDS = Set.of(
            KIND_MEMBER_CONFIRMED_MEETING,
            KIND_MEMBER_CONFIRMED_CONTACTS,
            KIND_MEMBER_CONFIRMED_EVAC
    );

    @Transactional
    public HouseholdEventDto recordMemberConfirmation(
            String householdId,
            String kind,
            String actorEmail
    ) {
        if (householdId == null || householdId.isBlank()) {
            throw new IllegalArgumentException("householdId is required");
        }
        if (kind == null || !ALLOWED_MEMBER_CONFIRMATION_KINDS.contains(kind)) {
            throw new IllegalArgumentException(
                    "Unknown member-confirmation kind: " + kind
            );
        }
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new IllegalArgumentException("actorEmail is required");
        }
        HouseholdEvent e = new HouseholdEvent();
        e.setHouseholdId(householdId);
        e.setKind(kind);
        e.setAt(Instant.now());
        e.setActorEmail(actorEmail.toLowerCase(Locale.ROOT));
        // No payload — the kind tells the renderer everything it needs.
        e.setPayloadJson(null);
        HouseholdEvent saved = eventRepo.save(e);

        HouseholdEventDto dto = toDto(saved, resolveActorMap(saved.getActorEmail()));
        broadcastAfterCommit(householdId, dto);
        return dto;
    }

    /**
     * The most recent confirmation of {@code kind} by {@code actorEmail}
     * in this household, or null if never. Used by the FE row to render
     * "Confirmed N days ago" / "✓ recently" vs the un-confirmed call.
     */
    public Optional<HouseholdEvent> findLatestConfirmation(
            String householdId,
            String kind,
            String actorEmail
    ) {
        if (householdId == null || kind == null || actorEmail == null) {
            return Optional.empty();
        }
        return eventRepo.findFirstByHouseholdIdAndKindAndActorEmailOrderByAtDesc(
                householdId, kind, actorEmail.toLowerCase(Locale.ROOT)
        );
    }

    // ---------------------------------------------------------------------
    // §8 of docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md — weekly check-in
    // completion + variable reward inputs.
    //
    // Decoupled from the §4 ritual scheduler on purpose. A member can
    // complete a weekly check-in any time; the (future) scheduled nudge
    // is just one channel that surfaces the action. This lets §8 ship
    // without waiting on the scheduler and keeps the action available
    // even when a household hasn't opted into the ritual yet.
    //
    // "Variable reward" = the FE composes copy from honest signals
    // (roster position, household size) that genuinely vary across
    // calls. We never invent social proof — if the roster shows 1 of 1,
    // copy reflects that.
    //
    // Mood is recorded as a payload field, not a separate kind, because
    // both moods are the same primary action (the member checking in);
    // they just route the reward UI differently on the FE.
    // ---------------------------------------------------------------------

    public static final String KIND_WEEKLY_CHECK_IN_COMPLETED = "weekly-check-in-completed";
    public static final String MOOD_GOOD = "good";
    public static final String MOOD_NEEDS_HELP = "needs-help";

    private static final Set<String> ALLOWED_MOODS = Set.of(MOOD_GOOD, MOOD_NEEDS_HELP);
    private static final java.time.Duration WEEK_WINDOW = java.time.Duration.ofDays(7);

    @Transactional
    public WeeklyCheckInResultDto recordWeeklyCheckIn(
            String householdId,
            String actorEmail,
            String mood
    ) {
        if (householdId == null || householdId.isBlank()) {
            throw new IllegalArgumentException("householdId is required");
        }
        if (actorEmail == null || actorEmail.isBlank()) {
            throw new IllegalArgumentException("actorEmail is required");
        }
        if (mood == null || !ALLOWED_MOODS.contains(mood)) {
            throw new IllegalArgumentException("Unknown mood: " + mood);
        }

        Map<String, Object> payload = Map.of("mood", mood);
        HouseholdEvent e = new HouseholdEvent();
        e.setHouseholdId(householdId);
        e.setKind(KIND_WEEKLY_CHECK_IN_COMPLETED);
        e.setAt(Instant.now());
        e.setActorEmail(actorEmail.toLowerCase(Locale.ROOT));
        try {
            e.setPayloadJson(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            // ObjectMapper can't fail on a single-entry string map, but
            // be defensive — the event itself is more valuable than the
            // mood metadata if serialization ever breaks.
            log.warn("Failed to serialize mood payload, recording without: {}", ex.getMessage());
            e.setPayloadJson(null);
        }
        HouseholdEvent saved = eventRepo.save(e);

        HouseholdEventDto eventDto = toDto(saved, resolveActorMap(saved.getActorEmail()));
        broadcastAfterCommit(householdId, eventDto);

        WeeklyCheckInSummaryDto summary = summarizeWeeklyCheckIn(
                householdId, actorEmail.toLowerCase(Locale.ROOT)
        );
        return new WeeklyCheckInResultDto(eventDto, summary);
    }

    /**
     * Roster of the rolling-7-day check-in window. Returns the latest
     * completion per actor (so a member tapping twice doesn't get
     * counted twice) plus the caller's 1-based position in the
     * window's chronological order — 0 when they haven't checked in.
     *
     * <p>Member count comes from {@link Group#getMemberEmails()}; the
     * variable reward uses {@code completions.size() / totalMembers}
     * for honest "{N} of {M} checked in this week" copy.</p>
     */
    @Transactional(readOnly = true)
    public WeeklyCheckInSummaryDto summarizeWeeklyCheckIn(
            String householdId,
            String actorEmail
    ) {
        if (householdId == null || householdId.isBlank()) {
            throw new IllegalArgumentException("householdId is required");
        }
        Instant windowEnd = Instant.now();
        Instant windowStart = windowEnd.minus(WEEK_WINDOW);

        List<HouseholdEvent> events = eventRepo.findRangeByKind(
                householdId, KIND_WEEKLY_CHECK_IN_COMPLETED,
                windowStart.minusSeconds(1), windowEnd.plusSeconds(1)
        );

        // Dedupe by actor — keep each member's EARLIEST check-in in the
        // window so "position" reflects the chronological order they
        // first showed up this week. A later re-tap doesn't bump them
        // down the list.
        Map<String, HouseholdEvent> earliestByActor = new LinkedHashMap<>();
        for (HouseholdEvent e : events) {
            String actor = e.getActorEmail();
            if (actor == null) continue;
            earliestByActor.putIfAbsent(actor, e);
        }

        List<HouseholdEvent> roster = new ArrayList<>(earliestByActor.values());
        // Already ascending from the repo, but make it explicit so this
        // doesn't silently break if the repo's ordering ever changes.
        roster.sort(Comparator.comparing(HouseholdEvent::getAt));

        Set<String> rosterEmails = earliestByActor.keySet();
        Map<String, UserInfo> userByEmail = rosterEmails.isEmpty()
                ? Map.of()
                : userInfoRepo.findByUserEmailIn(new ArrayList<>(rosterEmails)).stream()
                    .collect(Collectors.toMap(
                            u -> u.getUserEmail().toLowerCase(Locale.ROOT),
                            Function.identity(),
                            (a, b) -> a));

        List<WeeklyCheckInSummaryDto.Completion> completions = new ArrayList<>(roster.size());
        int actorPosition = 0;
        String actorKey = actorEmail == null ? null : actorEmail.toLowerCase(Locale.ROOT);
        for (int i = 0; i < roster.size(); i++) {
            HouseholdEvent e = roster.get(i);
            UserInfo u = userByEmail.get(e.getActorEmail());
            String name = u == null ? null : u.getUserFirstName();
            String img = u == null ? null : PublicCdn.toPublicUrl(u.getProfileImageURL());
            String mood = extractMood(e.getPayloadJson());
            completions.add(new WeeklyCheckInSummaryDto.Completion(
                    e.getActorEmail(), name, img, e.getAt(), mood
            ));
            if (actorKey != null && actorKey.equals(e.getActorEmail())) {
                actorPosition = i + 1;
            }
        }

        int totalMembers = totalMembers(householdId);

        return new WeeklyCheckInSummaryDto(
                windowStart, windowEnd, totalMembers, actorPosition, completions
        );
    }

    private int totalMembers(String householdId) {
        return groupRepo.findByGroupId(householdId)
                .filter(g -> HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType()))
                .map(g -> g.getMemberEmails() == null ? 0 : g.getMemberEmails().size())
                .orElse(0);
    }

    private String extractMood(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            Map<String, Object> p = objectMapper.readValue(payloadJson, MAP_TYPE);
            Object m = p.get("mood");
            return m == null ? null : m.toString();
        } catch (Exception ex) {
            return null;
        }
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
