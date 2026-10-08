package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.CheckIn;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.GroupService.AskEveryoneResult;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Household drawer gameplan §5.6 — the check-in pieces end to end over the
 * real services and the H2 schema: the persisted "Ask everyone" cooldown, the
 * member view's per-row {@code checkIn}, and a manual member's status during a
 * check-in. The unit tests pin each rule; these pin the wiring.
 */
@SpringBootTest
@ActiveProfiles("test")
class HouseholdCheckInFlowTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired GroupService groupService;
    @Autowired GroupViewService groupView;
    @Autowired HouseholdManualMemberService manualService;
    @Autowired GroupRepo groups;
    @Autowired UserInfoRepo users;
    @Autowired HouseholdManualMemberRepo manualRepo;
    @Autowired HouseholdEventRepo eventRepo;

    private String a;
    private String b;
    private String c;

    @BeforeEach
    void setUp() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        a = "a-" + sfx + "@x.com";
        b = "b-" + sfx + "@x.com";
        c = "c-" + sfx + "@x.com";
        user(a, "Dione", null, null);
        user(b, "Maya", null, null);
        user(c, "Chris", null, null);
    }

    // ── the cooldown, persisted ──────────────────────────────────────────

    @Test
    void aSecondHouseholdAskInsideTenMinutesIsHeld_andNamesTheFirst() {
        Group hh = group("Household", "Not Active", null, a, b, c);

        AskEveryoneResult first = groupService.requestCheckIn(hh.getGroupId(), a);
        AskEveryoneResult second = groupService.requestCheckIn(hh.getGroupId(), b);

        assertThat(first.sent()).isTrue();
        assertThat(second.sent()).isFalse();
        assertThat(second.requestedByEmail()).isEqualTo(a);
        assertThat(second.requestedByName()).isEqualTo("Dione");
        List<HouseholdEvent> rows = eventRepo.findRangeByKind(hh.getGroupId(),
                HouseholdEventService.KIND_CHECKIN_REQUEST, Instant.EPOCH, Instant.now().plusSeconds(5));
        assertThat(rows).hasSize(1);
        assertThat(second.requestedAt()).isEqualTo(rows.get(0).getAt());
        assertThat(second.retryAt()).isEqualTo(rows.get(0).getAt().plus(Duration.ofMinutes(10)));
    }

    @Test
    void anOrgGroupHasNoCooldown() {
        Group org = group("Community", "Not Active", null, a, b);

        assertThat(groupService.requestCheckIn(org.getGroupId(), a).sent()).isTrue();
        assertThat(groupService.requestCheckIn(org.getGroupId(), a).sent()).isTrue();
    }

    // ── the member view's checkIn ────────────────────────────────────────

    @Test
    void calm_theRowSaysAskedAnsweredOrNothing() {
        Group hh = group("Household", "Not Active", null, a, b, c);
        groupService.requestCheckIn(hh.getGroupId(), a);
        // Chris answers after the ask; Maya doesn't; Dione asked, so wasn't.
        Instant answered = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        setStatus(c, "SAFE", answered);

        GroupMemberViewDto view = groupView.buildMemberView(hh.getGroupId(), a).orElseThrow();

        CheckIn dione = checkInOf(view, a);
        CheckIn maya = checkInOf(view, b);
        CheckIn chris = checkInOf(view, c);
        assertThat(dione.state()).isEqualTo(CheckInState.NONE);
        assertThat(dione.askedAt()).isNull();
        assertThat(maya.state()).isEqualTo(CheckInState.AWAITING);
        assertThat(maya.askedAt()).isNotNull();
        assertThat(maya.showUntil()).isEqualTo(maya.askedAt().plus(Duration.ofHours(24)));
        assertThat(chris.state()).isEqualTo(CheckInState.ANSWERED);
        assertThat(chris.value()).isEqualTo("SAFE");
        assertThat(chris.askedAt()).isNotNull();
        assertThat(chris.answeredAt()).isEqualTo(answered);
        assertThat(chris.showUntil()).isEqualTo(answered.plus(Duration.ofHours(24)));
    }

    @Test
    void checkIn_aSafeFromBeforeItStartedIsNotAnAnswer_andAFreshOneShowsUntilTheEnd() {
        Instant activated = Instant.now().minus(Duration.ofHours(2));
        Instant ends = activated.plus(Duration.ofHours(48)).truncatedTo(ChronoUnit.MILLIS);
        Group hh = group("Household", "Active", activated, a, b);
        hh.setAlertExpiresAt(ends);
        groups.save(hh);
        setStatus(a, "SAFE", Instant.now().minus(Duration.ofHours(3)));
        setStatus(b, "SAFE", Instant.now().minus(Duration.ofHours(1)));

        GroupMemberViewDto view = groupView.buildMemberView(hh.getGroupId(), a).orElseThrow();

        assertThat(checkInOf(view, a).state()).isEqualTo(CheckInState.NONE);
        CheckIn maya = checkInOf(view, b);
        assertThat(maya.state()).isEqualTo(CheckInState.ANSWERED);
        assertThat(maya.showUntil()).isEqualTo(ends);
        assertThat(view.rollup().safe()).isEqualTo(1);
        assertThat(view.rollup().noResponse()).isEqualTo(1);
    }

    @Test
    void checkIn_withNoActivationTime_aWeekOldSafeIsNotAnAnswer() {
        // A check-in opened before alertActivatedAt existed: the row anchors
        // where the counts do (updatedAt), not "every SAFE is in window".
        Group hh = group("Household", "Active", null, a, b);
        setStatus(b, "SAFE", Instant.now().minus(Duration.ofDays(7)));

        GroupMemberViewDto view = groupView.buildMemberView(hh.getGroupId(), a).orElseThrow();

        assertThat(checkInOf(view, b).state()).isEqualTo(CheckInState.NONE);
        assertThat(view.rollup().safe()).isZero();
    }

    // ── a manual member during a check-in ────────────────────────────────

    @Test
    void manual_duringACheckIn_aCalmSafeIsHidden_andAFreshOneShowsUntilTheEnd() {
        Instant activated = Instant.now().minus(Duration.ofHours(2));
        Instant ends = activated.plus(Duration.ofHours(30)).truncatedTo(ChronoUnit.MILLIS);
        Group hh = group("Household", "Active", activated, a);
        hh.setAlertExpiresAt(ends);
        groups.save(hh);
        HouseholdManualMember before = manual(hh.getGroupId(), "Ava", "SAFE", Instant.now().minus(Duration.ofHours(3)));
        HouseholdManualMember after = manual(hh.getGroupId(), "Leo", "SAFE", Instant.now().minus(Duration.ofHours(1)));

        List<HouseholdManualMemberDto> rows = manualService.list(hh.getGroupId());

        assertThat(dtoOf(rows, before.getId()).status()).isNull();
        assertThat(dtoOf(rows, after.getId()).status().showUntil()).isEqualTo(ends);
    }

    @Test
    void manual_aCheckInWithNoEndUsesTheConfiguredWindow() {
        Instant activated = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MILLIS);
        Group hh = group("Household", "Active", activated, a);
        HouseholdManualMember leo = manual(hh.getGroupId(), "Leo", "SAFE", Instant.now().minus(Duration.ofHours(1)));

        HouseholdManualMemberDto dto = dtoOf(manualService.list(hh.getGroupId()), leo.getId());

        // app.groupAlert.decayHours — the same end an account's row gets.
        assertThat(dto.status().showUntil()).isEqualTo(activated.plus(Duration.ofHours(48)));
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static CheckIn checkInOf(GroupMemberViewDto view, String email) {
        return view.members().stream().filter(m -> email.equals(m.email())).findFirst().orElseThrow().checkIn();
    }

    private static HouseholdManualMemberDto dtoOf(List<HouseholdManualMemberDto> rows, String id) {
        return rows.stream().filter(r -> id.equals(r.id())).findFirst().orElseThrow();
    }

    private Group group(String type, String alert, Instant activatedAt, String owner, String... others) {
        Group g = new Group();
        g.setGroupId("g-" + UUID.randomUUID());
        g.setGroupType(type);
        g.setGroupName("Test " + type);
        g.setPrivacy("Private");
        g.setAlert(alert);
        g.setAlertActivatedAt(activatedAt);
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(List.of(owner)));
        List<String> members = new ArrayList<>(List.of(owner));
        members.addAll(List.of(others));
        g.setMemberEmails(members);
        g.setPendingMemberEmails(new ArrayList<>());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groups.save(g);
    }

    private void user(String email, String first, String status, Instant at) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(first);
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUserStatus(status);
        u.setUserStatusLastUpdated(at);
        users.save(u);
    }

    private void setStatus(String email, String status, Instant at) {
        UserInfo u = users.findByUserEmailIgnoreCase(email).orElseThrow();
        u.setUserStatus(status);
        u.setUserStatusLastUpdated(at);
        users.save(u);
    }

    private HouseholdManualMember manual(String hid, String name, String status, Instant at) {
        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(UUID.randomUUID().toString());
        m.setHouseholdId(hid);
        m.setName(name);
        m.setIsAdult(false);
        m.setBand(HouseholdBand.KID);
        m.setStatus(status);
        m.setStatusUpdatedAt(at);
        m.setStatusSetByEmail(a);
        return manualRepo.save(m);
    }
}
