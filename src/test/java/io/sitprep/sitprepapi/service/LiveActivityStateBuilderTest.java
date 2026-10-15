package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.PlanActivationAck;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PlanActivationAckRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.service.LiveActivityStateBuilder.Situation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * live_activities P1: the backend owns the card's numbers and sentences
 * (GAMEPLAN §3.2). The widget only formats them, so a wrong count or a name
 * here is a wrong lock screen.
 */
class LiveActivityStateBuilderTest {

    private static final Instant START = Instant.parse("2026-10-13T15:00:00Z");
    private static final Instant EXPIRES = START.plus(Duration.ofHours(48));

    private GroupRepo groupRepo;
    private PlanActivationRepo activationRepo;
    private PlanActivationAckRepo ackRepo;
    private GroupService groupService;
    private LiveActivityStateBuilder builder;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        activationRepo = mock(PlanActivationRepo.class);
        ackRepo = mock(PlanActivationAckRepo.class);
        groupService = mock(GroupService.class);
        ObjectProvider<GroupService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(groupService);
        builder = new LiveActivityStateBuilder(groupRepo, activationRepo, ackRepo, provider);
        when(activationRepo.findLiveForHousehold(any(), any())).thenReturn(List.of());
    }

    private static CheckInRollupDto rollup(int total, int safe, int help, int injured) {
        int accounted = safe + help + injured;
        return new CheckInRollupDto("hh-1", "Plouis household", true, START, total, accounted,
                safe, help, injured, total - accounted, List.of(), EXPIRES);
    }

    private Group household(String alert) {
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupName("Plouis household");
        g.setGroupType("Household");
        g.setAlert(alert);
        g.setAlertActivatedAt(START);
        g.setAlertExpiresAt(EXPIRES);
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(g));
        return g;
    }

    private static PlanActivationAck ack(String who, String status, Instant at) {
        PlanActivationAck a = new PlanActivationAck();
        a.setRecipientEmail(who);
        a.setStatus(status);
        a.setAckedAt(at);
        return a;
    }

    // ── reads ──────────────────────────────────────────────────────────

    @Test
    void aRunningCheckInReadsTheSharedRollup_andItsEndTime() {
        Group g = household("Active");
        when(groupService.checkInRollupFor(g)).thenReturn(rollup(6, 3, 1, 0));

        Situation s = builder.currentSituation("hh-1").orElseThrow();

        assertThat(s.rollup().accounted()).isEqualTo(4);
        assertThat(s.expiresAt()).isEqualTo(EXPIRES);
        assertThat(s.plan()).isFalse();
        assertThat(s.pickup()).isNull();
        assertThat(s.urgent()).isEqualTo(1);
    }

    @Test
    void noRunningCheckIn_noSituation() {
        household("Inactive");
        assertThat(builder.currentSituation("hh-1")).isEmpty();
        assertThat(builder.currentSituation("missing")).isEmpty();
        verifyNoInteractions(groupService);
    }

    @Test
    void aLivePlanSetsThePlanFlag_andCountsEachPersonsLatestPickupOnce() {
        Group g = household("Active");
        when(groupService.checkInRollupFor(g)).thenReturn(rollup(6, 4, 0, 0));
        PlanActivation a1 = new PlanActivation();
        a1.setId("act-1");
        PlanActivation a2 = new PlanActivation();
        a2.setId("act-2");
        when(activationRepo.findLiveForHousehold(eq(g), any())).thenReturn(List.of(a1, a2));
        when(ackRepo.findByActivationIdOrderByAckedAtAsc("act-1")).thenReturn(List.of(
                ack("maya@x.com", "pickup", START.plusSeconds(60)),
                ack("sam@x.com", "pickup", START.plusSeconds(60))));
        when(ackRepo.findByActivationIdOrderByAckedAtAsc("act-2")).thenReturn(List.of(
                // Maya asked for a pickup on both rows: one person, one count.
                ack("MAYA@x.com", "pickup", START.plusSeconds(90)),
                // Sam later said safe: the newer answer wins.
                ack("sam@x.com", "safe", START.plusSeconds(120))));

        Situation s = builder.currentSituation("hh-1").orElseThrow();

        assertThat(s.plan()).isTrue();
        assertThat(s.pickup()).isEqualTo(1);
    }

    @Test
    void anEndedCheckInIsCountedAgainstItsOwnStart() {
        Group g = household("Inactive");
        when(groupService.checkInRollupSince(g, START)).thenReturn(rollup(6, 6, 0, 0));

        Situation s = builder.endedSituation("hh-1", START).orElseThrow();

        verify(groupService).checkInRollupSince(g, START);
        verify(groupService, never()).checkInRollupFor(any());
        assertThat(s.rollup().accounted()).isEqualTo(6);
        assertThat(s.expiresAt()).isNull();
    }

    // ── shaping ────────────────────────────────────────────────────────

    @Test
    void theStateCarriesCountsAndEpochSeconds_neverANameOrADate() {
        Situation s = new Situation("hh-1", "Plouis household", "Household", rollup(6, 3, 1, 1),
                START, EXPIRES, false, 3);
        Instant now = START.plusSeconds(600);

        Map<String, Object> m = LiveActivityStateBuilder.contentState("active", s, now, null);

        assertThat(m).containsEntry("phase", "active").containsEntry("plan", false)
                .containsEntry("total", 6).containsEntry("accounted", 5).containsEntry("safe", 3)
                .containsEntry("help", 1).containsEntry("injured", 1)
                .containsEntry("expiresAtEpoch", EXPIRES.getEpochSecond())
                .containsEntry("asOfEpoch", now.getEpochSecond());
        assertThat(m.get("pickup")).as("pickup belongs to the plan variant only").isNull();
        assertThat(m.values()).noneMatch(v -> v instanceof Instant);
        assertThat(m.toString()).doesNotContain("@").doesNotContain("Plouis");
    }

    @Test
    void accountedNeverExceedsTotal_andNothingIsNegative() {
        CheckInRollupDto odd = new CheckInRollupDto("hh-1", "x", true, START, 2, 5, 5, -1, 0, 0, List.of(), null);
        Situation s = new Situation("hh-1", "x", "Household", odd, START, null, false, null);

        Map<String, Object> m = LiveActivityStateBuilder.contentState("active", s, START, null);

        assertThat(m).containsEntry("accounted", 2).containsEntry("help", 0);
    }

    @Test
    void anEndedStateHasNoEndTimeAndCarriesTheNote() {
        Situation s = new Situation("hh-1", "x", "Household", rollup(6, 6, 0, 0), START, EXPIRES, true, 0);
        Map<String, Object> m = LiveActivityStateBuilder.contentState("ended", s, START, "Ended by you after 17 min.");
        assertThat(m).containsEntry("phase", "ended").containsEntry("note", "Ended by you after 17 min.")
                .containsEntry("pickup", 0);
        assertThat(m.get("expiresAtEpoch")).isNull();
    }

    @Test
    void theEndedNoteSaysYouOnlyToTheViewerWhoEndedIt_andNeverAName() {
        Instant end = START.plus(Duration.ofMinutes(17));
        assertThat(LiveActivityStateBuilder.endedNote("manual", true, START, end)).isEqualTo("Ended by you after 17 min.");
        assertThat(LiveActivityStateBuilder.endedNote("manual", false, START, end)).isEqualTo("Ended after 17 min.");
        assertThat(LiveActivityStateBuilder.endedNote("decay", false, START, START.plus(Duration.ofHours(48))))
                .isEqualTo("Ended automatically after 48 h.");
        assertThat(LiveActivityStateBuilder.endedNote("manual", true, null, end)).isEqualTo("Ended by you.");
        // A deleted group: why it ended, never who (not even "you").
        assertThat(LiveActivityStateBuilder.endedNote(LiveActivityStateBuilder.REASON_DELETED, true, START, end))
                .isEqualTo("This group was removed.");
        assertThat(LiveActivityStateBuilder.endedNote(LiveActivityStateBuilder.REASON_DELETED, false, START, end))
                .isEqualTo("This group was removed.");
    }

    @Test
    void durationsReadLikeTheApp() {
        // Same words as liveActivity.js formatDuration.
        assertThat(LiveActivityStateBuilder.formatDuration(10)).isEqualTo("1 min");
        assertThat(LiveActivityStateBuilder.formatDuration(17 * 60)).isEqualTo("17 min");
        assertThat(LiveActivityStateBuilder.formatDuration(3 * 3600)).isEqualTo("3 h");
        assertThat(LiveActivityStateBuilder.formatDuration(2 * 3600 + 40 * 60)).isEqualTo("2 h 40 min");
    }

    @Test
    void alertLinesAreCountsOnly() {
        assertThat(LiveActivityStateBuilder.urgentAlertBody(rollup(6, 3, 1, 0)))
                .isEqualTo("1 needs help · 4 of 6 accounted for");
        assertThat(LiveActivityStateBuilder.urgentAlertBody(rollup(6, 2, 2, 1)))
                .isEqualTo("2 need help · 1 hurt · 5 of 6 accounted for");
        assertThat(LiveActivityStateBuilder.endedAlertBody(rollup(6, 6, 0, 0)))
                .isEqualTo("Check-in ended · 6 of 6 accounted for");
        Situation unnamed = new Situation("g", " ", "Household", rollup(1, 1, 0, 0), START, null, false, null);
        assertThat(LiveActivityStateBuilder.alertTitle(unnamed)).isEqualTo("Your household");
    }
}
