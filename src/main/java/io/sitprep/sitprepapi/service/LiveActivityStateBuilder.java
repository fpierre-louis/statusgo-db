package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.PlanActivationAck;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationAckRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The server's Live Activity content (live_activities P1, GAMEPLAN §3.2): the
 * backend owns the numbers and the sentences; the widget extension only
 * formats them.
 *
 * <h4>The contract</h4>
 * {@link #contentState} returns EXACTLY the keys of the Swift
 * {@code SituationAttributes.ContentState}
 * ({@code ios/App/Shared/ActivityAttributes.swift}), in its order:
 * {@link #CONTENT_STATE_KEYS}. APNs decodes {@code content-state} with Swift's
 * default Codable, so a missing non-optional key or a renamed one drops the
 * whole update on the device without an error anywhere we can see.
 * {@code LiveActivityContentStateContractTest} parses the Swift file and holds
 * the two together.
 *
 * <h4>Rules carried from the gameplan</h4>
 * <ul>
 *   <li>Times are Unix epoch SECONDS ({@code Int}), never a {@code Date}.</li>
 *   <li>Counts only, never a member's name. The only name is the group's own,
 *       and it lives in the static attributes, not here.</li>
 *   <li>A sentence the server stands behind ({@code note}) states a duration,
 *       never a clock time: the server does not know the viewer's time zone.</li>
 * </ul>
 *
 * <p>The numbers come from {@link GroupService#checkInRollupFor} — the same
 * rollup {@code /check-in-rollup}, ping-missing and the scheduled reminders
 * read — so the lock screen cannot disagree with the drawer.</p>
 */
@Component
public class LiveActivityStateBuilder {

    public static final String PHASE_ACTIVE = "active";
    public static final String PHASE_ENDED = "ended";

    /** An organizer (or the all-clear) ended it. */
    public static final String REASON_MANUAL = "manual";
    /** The 48 h decay sweep ended it. */
    public static final String REASON_DECAY = "decay";
    /** The group was deleted while its check-in ran (BE-09). */
    public static final String REASON_DELETED = "deleted";

    /** {@code SituationAttributes.ContentState}'s keys, in declaration order. */
    public static final List<String> CONTENT_STATE_KEYS = List.of(
            "phase", "plan", "total", "accounted", "safe", "help", "injured",
            "pickup", "expiresAtEpoch", "asOfEpoch", "note");

    private final GroupRepo groupRepo;
    private final PlanActivationRepo activationRepo;
    private final PlanActivationAckRepo ackRepo;
    // Lazy: GroupService → LiveActivityPushService → this → GroupService would
    // be a constructor cycle, and this app does not allow circular references.
    private final ObjectProvider<GroupService> groupService;

    public LiveActivityStateBuilder(GroupRepo groupRepo,
                                    PlanActivationRepo activationRepo,
                                    PlanActivationAckRepo ackRepo,
                                    ObjectProvider<GroupService> groupService) {
        this.groupRepo = groupRepo;
        this.activationRepo = activationRepo;
        this.ackRepo = ackRepo;
        this.groupService = groupService;
    }

    /**
     * Everything one situation card needs from the server, before it is
     * shaped per viewer.
     *
     * @param plan   a plan activation is live for this household
     * @param pickup pickup acks on the live activation(s); null outside the plan variant
     */
    public record Situation(String groupId,
                            String groupName,
                            String groupType,
                            CheckInRollupDto rollup,
                            Instant startedAt,
                            Instant expiresAt,
                            boolean plan,
                            Integer pickup) {
        public int urgent() {
            return rollup == null ? 0 : Math.max(0, rollup.help()) + Math.max(0, rollup.injured());
        }
    }

    // ---------------------------------------------------------------------
    // Reads (Spring, transactional)
    // ---------------------------------------------------------------------

    /** The running check-in, or empty when the group is gone or its check-in is not Active. */
    @Transactional(readOnly = true)
    public Optional<Situation> currentSituation(String groupId) {
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        Optional<Group> found = groupRepo.findByGroupId(groupId);
        if (found.isEmpty()) return Optional.empty();
        Group g = found.get();
        if (!"Active".equalsIgnoreCase(g.getAlert())) return Optional.empty();
        CheckInRollupDto rollup = groupService.getObject().checkInRollupFor(g);
        PlanFacts plan = planFacts(g);
        return Optional.of(new Situation(g.getGroupId(), g.getGroupName(), g.getGroupType(), rollup,
                g.getAlertActivatedAt(), g.getAlertExpiresAt(), plan.live(), plan.pickup()));
    }

    /**
     * The check-in that started at {@code startedAt} and has just ended,
     * counted the way it was counted while it ran. {@code plan} and
     * {@code pickup} are what is live NOW; the push service carries the last
     * values it sent when it has them (an all-clear ends the plan in the same
     * transaction, so "now" already reads no plan).
     */
    @Transactional(readOnly = true)
    public Optional<Situation> endedSituation(String groupId, Instant startedAt) {
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        Optional<Group> found = groupRepo.findByGroupId(groupId);
        if (found.isEmpty()) return Optional.empty();
        Group g = found.get();
        CheckInRollupDto rollup = groupService.getObject().checkInRollupSince(g, startedAt);
        PlanFacts plan = planFacts(g);
        return Optional.of(new Situation(g.getGroupId(), g.getGroupName(), g.getGroupType(), rollup,
                startedAt, null, plan.live(), plan.pickup()));
    }

    private record PlanFacts(boolean live, Integer pickup) {}

    /**
     * Whether a plan is live for this household, and how many people asked
     * for a pickup on it. One answer per person: their latest ack across the
     * live activations (two people launching makes two rows).
     */
    private PlanFacts planFacts(Group g) {
        if (!HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType())) {
            return new PlanFacts(false, null);
        }
        List<PlanActivation> live = activationRepo.findLiveForHousehold(g, Instant.now());
        if (live.isEmpty()) return new PlanFacts(false, null);
        Map<String, PlanActivationAck> latest = new HashMap<>();
        for (PlanActivation a : live) {
            for (PlanActivationAck ack : ackRepo.findByActivationIdOrderByAckedAtAsc(a.getId())) {
                if (ack.getRecipientEmail() == null) continue;
                String who = ack.getRecipientEmail().trim().toLowerCase(Locale.ROOT);
                PlanActivationAck prev = latest.get(who);
                if (prev == null || isAfter(ack.getAckedAt(), prev.getAckedAt())) latest.put(who, ack);
            }
        }
        int pickup = (int) latest.values().stream()
                .filter(a -> a.getStatus() != null && "pickup".equalsIgnoreCase(a.getStatus().trim()))
                .count();
        return new PlanFacts(true, pickup);
    }

    private static boolean isAfter(Instant a, Instant b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.isAfter(b);
    }

    // ---------------------------------------------------------------------
    // Shaping (pure)
    // ---------------------------------------------------------------------

    /**
     * {@code SituationAttributes.ContentState} as JSON-ready values. Optional
     * Swift fields ({@code pickup}, {@code expiresAtEpoch}, {@code note}) are
     * present with null when unknown; Swift's {@code decodeIfPresent} reads a
     * null or a missing key as nil.
     */
    public static Map<String, Object> contentState(String phase, Situation s, Instant asOf, String note) {
        CheckInRollupDto r = s.rollup();
        int total = r == null ? 0 : Math.max(0, r.total());
        int safe = r == null ? 0 : Math.max(0, r.safe());
        int help = r == null ? 0 : Math.max(0, r.help());
        int injured = r == null ? 0 : Math.max(0, r.injured());
        int accounted = r == null ? 0 : Math.min(total, Math.max(0, r.accounted()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("plan", s.plan());
        m.put("total", total);
        m.put("accounted", accounted);
        m.put("safe", safe);
        m.put("help", help);
        m.put("injured", injured);
        m.put("pickup", s.plan() ? s.pickup() : null);
        m.put("expiresAtEpoch", PHASE_ENDED.equals(phase) ? null : epoch(s.expiresAt()));
        m.put("asOfEpoch", epoch(asOf));
        m.put("note", note);
        return m;
    }

    /**
     * The ended card's sentence, for one viewer. Never names who ended it —
     * the lock screen is readable by whoever holds the phone — except "you".
     */
    public static String endedNote(String reason, boolean endedByViewer, Instant startedAt, Instant endedAt) {
        String after = "";
        if (startedAt != null && endedAt != null) {
            long secs = endedAt.getEpochSecond() - startedAt.getEpochSecond();
            if (secs > 0) after = " after " + formatDuration(secs);
        }
        if (REASON_DECAY.equals(reason)) return "Ended automatically" + after + ".";
        // Never says who removed it, the viewer included: the card only says why it ended.
        if (REASON_DELETED.equals(reason)) return "This group was removed.";
        if (endedByViewer) return "Ended by you" + after + ".";
        return "Ended" + after + ".";
    }

    /** The alert line when someone newly needs help: counts only. */
    public static String urgentAlertBody(CheckInRollupDto r) {
        StringBuilder b = new StringBuilder();
        int help = r == null ? 0 : Math.max(0, r.help());
        int injured = r == null ? 0 : Math.max(0, r.injured());
        if (help > 0) b.append(help == 1 ? "1 needs help" : help + " need help");
        if (injured > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(injured).append(" hurt");
        }
        if (b.length() > 0) b.append(" · ");
        b.append(accountedLine(r));
        return b.toString();
    }

    /** The alert line on an end the viewer did not make. */
    public static String endedAlertBody(CheckInRollupDto r) {
        return "Check-in ended · " + accountedLine(r);
    }

    /** The alert title: the group's own name, never a person's. */
    public static String alertTitle(Situation s) {
        String n = s.groupName() == null ? "" : s.groupName().trim();
        if (!n.isEmpty()) return n;
        return HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(s.groupType())
                ? "Your household" : "Your group";
    }

    private static String accountedLine(CheckInRollupDto r) {
        int total = r == null ? 0 : Math.max(0, r.total());
        int accounted = r == null ? 0 : Math.min(total, Math.max(0, r.accounted()));
        return accounted + " of " + total + " accounted for";
    }

    /** "17 min", "2 h 40 min", "3 h" — the same words as the app's {@code formatDuration}. */
    static String formatDuration(long seconds) {
        long mins = Math.max(1, Math.round(seconds / 60.0));
        if (mins < 60) return mins + " min";
        long h = mins / 60;
        long m = mins % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    static Long epoch(Instant t) {
        return t == null ? null : t.getEpochSecond();
    }
}
