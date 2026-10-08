package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.StatusRollup;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto.ManualStatus;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dominantStatus derivation — severity-first so the label never hides a
 * member in trouble. (The rollup math itself is covered by the Phase 1
 * member-view parity scenarios; this pins the new derivation only.)
 *
 * <p>Plus the manual-member rule (V103, household drawer gameplan §5.5): a
 * fresh status set for a dependent counts; HELP / INJURED outrank the SAFE an
 * accompaniment implies; and an unmarked, unaccompanied dependent is still
 * noResponse — nobody has accounted for them.</p>
 */
class StatusRollupsTest {

    private static StatusRollup r(int total, int safe, int help, int injured, int noResponse) {
        return new StatusRollup(total, safe + help + injured, safe, help, injured, noResponse);
    }

    @Test
    void emptyRoster_isUnknown() {
        assertThat(StatusRollups.dominantStatus(r(0, 0, 0, 0, 0))).isEqualTo("UNKNOWN");
        assertThat(StatusRollups.dominantStatus(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void injuredOutranksEverything() {
        assertThat(StatusRollups.dominantStatus(r(4, 2, 1, 1, 0))).isEqualTo("INJURED");
    }

    @Test
    void helpOutranksSilenceAndSafe() {
        assertThat(StatusRollups.dominantStatus(r(4, 2, 1, 0, 1))).isEqualTo("HELP");
    }

    @Test
    void allSilent_isUnknown() {
        assertThat(StatusRollups.dominantStatus(r(3, 0, 0, 0, 3))).isEqualTo("UNKNOWN");
    }

    @Test
    void partiallyAccounted_isCheckIn() {
        assertThat(StatusRollups.dominantStatus(r(3, 2, 0, 0, 1))).isEqualTo("CHECK_IN");
    }

    @Test
    void everyoneSafe_isSafe() {
        assertThat(StatusRollups.dominantStatus(r(3, 3, 0, 0, 0))).isEqualTo("SAFE");
    }

    // ── manual members (V103) ────────────────────────────────────────────────

    private static final Instant NOW = Instant.now();

    private static HouseholdManualMemberDto manual(String id, String value, Instant at) {
        ManualStatus st = value == null ? null : new ManualStatus(value, "#000", at, "Dione",
                "SAFE".equals(value) ? at.plus(Duration.ofHours(24)) : null);
        return new HouseholdManualMemberDto(id, "hh-1", "Kid " + id, null, 5, false, "KID",
                null, NOW, NOW, st);
    }

    private static HouseholdAccompanimentDto withMe(String manualId) {
        return new HouseholdAccompanimentDto(1L,
                new HouseholdAccompanimentDto.Ref("user", "dione@x.com", "dione@x.com"),
                new HouseholdAccompanimentDto.Ref("manual", manualId, null),
                NOW.minus(Duration.ofDays(20)), false);
    }

    private static StatusRollup manualOnly(List<HouseholdManualMemberDto> manual,
                                           List<HouseholdAccompanimentDto> acc,
                                           boolean alert, Instant anchor) {
        return StatusRollups.compute(List.of(), Map.of(), manual, acc, alert, anchor);
    }

    @Test
    void manual_noStatus_noAccompaniment_isNoResponse() {
        StatusRollup r = manualOnly(List.of(manual("m1", null, null)), List.of(), false, null);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.noResponse()).isEqualTo(1);
        assertThat(r.accounted()).isZero();
    }

    @Test
    void manual_explicitStatusesBucket() {
        StatusRollup r = manualOnly(List.of(
                manual("m1", "SAFE", NOW.minus(Duration.ofHours(1))),
                manual("m2", "HELP", NOW.minus(Duration.ofHours(1))),
                manual("m3", "INJURED", NOW.minus(Duration.ofHours(1)))), List.of(), false, null);
        assertThat(r.safe()).isEqualTo(1);
        assertThat(r.help()).isEqualTo(1);
        assertThat(r.injured()).isEqualTo(1);
        assertThat(r.noResponse()).isZero();
    }

    @Test
    void manual_helpOverridesAnAccompaniment() {
        StatusRollup r = manualOnly(List.of(manual("m1", "HELP", NOW.minus(Duration.ofHours(1)))),
                List.of(withMe("m1")), false, null);
        assertThat(r.help()).isEqualTo(1);
        assertThat(r.safe()).isZero();
    }

    @Test
    void manual_aLapsedStatusFallsBackToTheAccompaniment_thenToNoResponse() {
        // A lapsed SAFE arrives as status == null: the DTO only carries a status it still shows.
        StatusRollup accompanied = manualOnly(List.of(manual("m1", null, null)), List.of(withMe("m1")), false, null);
        assertThat(accompanied.safe()).isEqualTo(1);
        StatusRollup alone = manualOnly(List.of(manual("m1", null, null)), List.of(), false, null);
        assertThat(alone.noResponse()).isEqualTo(1);
    }

    @Test
    void manual_duringACheckIn_aStatusFromBeforeItStartedDoesNotCount() {
        Instant started = NOW.minus(Duration.ofHours(2));
        StatusRollup r = manualOnly(List.of(
                manual("m1", "INJURED", started.minus(Duration.ofHours(5))),
                manual("m2", "SAFE", started.plus(Duration.ofMinutes(30)))), List.of(), true, started);
        assertThat(r.injured()).isZero();
        assertThat(r.safe()).isEqualTo(1);
        assertThat(r.noResponse()).isEqualTo(1);
    }

    @Test
    void manual_duringACheckIn_staleBadNewsWithAnAccompanimentIsNeverSafe() {
        // Marked INJURED in calm, "with" Dad, then a check-in starts: the row
        // still reads Injured, so the count must not call the child safe.
        Instant started = NOW.minus(Duration.ofHours(2));
        StatusRollup r = manualOnly(List.of(
                manual("m1", "INJURED", started.minus(Duration.ofHours(1))),
                manual("m2", "HELP", started.minus(Duration.ofHours(1)))),
                List.of(withMe("m1"), withMe("m2")), true, started);
        assertThat(r.safe()).isZero();
        assertThat(r.noResponse()).isEqualTo(2);
        assertThat(StatusRollups.manualBucket(
                manual("m1", "INJURED", started.minus(Duration.ofHours(1))),
                List.of(withMe("m1")), true, started)).isNull();
    }

    @Test
    void theCheckInRollupAndTheMemberViewRollupAgree() {
        Instant started = NOW.minus(Duration.ofHours(2));
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupName("The Lees");
        g.setGroupType("Household");
        g.setAlert("Active");
        g.setAlertActivatedAt(started);
        g.setMemberEmails(List.of("dione@x.com", "maya@x.com"));
        UserInfo dione = new UserInfo();
        dione.setUserEmail("dione@x.com");
        dione.setUserStatus("SAFE");
        dione.setUserStatusLastUpdated(started.plus(Duration.ofMinutes(5)));
        UserInfo maya = new UserInfo();
        maya.setUserEmail("maya@x.com");
        maya.setUserStatus("SAFE");
        maya.setUserStatusLastUpdated(started.minus(Duration.ofHours(1)));   // stale
        List<HouseholdManualMemberDto> manual = List.of(
                manual("m1", "HELP", started.plus(Duration.ofMinutes(10))),
                manual("m2", null, null),
                manual("m3", null, null));
        List<HouseholdAccompanimentDto> acc = List.of(withMe("m2"));

        GroupRepo groupRepo = mock(GroupRepo.class);
        UserInfoRepo userRepo = mock(UserInfoRepo.class);
        HouseholdManualMemberService manualService = mock(HouseholdManualMemberService.class);
        HouseholdAccompanimentService accService = mock(HouseholdAccompanimentService.class);
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(g));
        when(userRepo.findByUserEmailIn(any())).thenReturn(List.of(dione, maya));
        when(manualService.list("hh-1")).thenReturn(manual);
        when(accService.list("hh-1")).thenReturn(acc);
        GroupService groupService = new GroupService(groupRepo, userRepo, mock(WebSocketMessageSender.class),
                mock(HouseholdEventService.class), mock(NotificationService.class),
                mock(CheckInRequestService.class));
        groupService.setManualMembers(manualService);
        groupService.setAccompaniments(accService);

        CheckInRollupDto checkIn = groupService.getCheckInRollup("hh-1");
        StatusRollup view = StatusRollups.compute(g.getMemberEmails(),
                Map.of("dione@x.com", dione, "maya@x.com", maya), manual, acc, true, StatusRollups.anchorFor(g));

        assertThat(checkIn.total()).isEqualTo(view.total()).isEqualTo(5);
        assertThat(checkIn.accounted()).isEqualTo(view.accounted()).isEqualTo(3);
        assertThat(checkIn.safe()).isEqualTo(view.safe()).isEqualTo(2);
        assertThat(checkIn.help()).isEqualTo(view.help()).isEqualTo(1);
        assertThat(checkIn.missing()).isEqualTo(view.noResponse()).isEqualTo(2);
        // The admin reminder push reads the same rollup — manual members included.
        assertThat(GroupCheckInReminderService.rollupBody(groupService.checkInRollupFor(g)))
                .isEqualTo("3 of 5 checked in: 2 safe, 1 need help, 0 injured, 2 missing. Tap to review.");
    }
}
