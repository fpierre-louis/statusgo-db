package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.MemberSummary;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.SubGroupRollup;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B4 (2026-10-14) — the member view v2, through the real
 * {@link GroupViewService} over mocked repos:
 * <ul>
 *   <li><b>ST-02 / D14</b>: a parent's owner and admins get each linked sub
 *       group's counts and worst status — no member rows, no names, no
 *       emails — counted by the one rule (a SAFE from before the sub group's
 *       check-in is noResponse there). A plain member gets null.</li>
 *   <li><b>MB-06</b>: pending requesters for owner/admin only.</li>
 *   <li><b>BE-11</b>: per-row {@code role} + {@code userId}; capabilities from
 *       the GroupRole matrix.</li>
 *   <li><b>D6</b>: no join code for a plain org member.</li>
 *   <li><b>ST-04</b>: the window fields.</li>
 *   <li><b>BE-10</b>: dispatch is read for THIS group only.</li>
 * </ul>
 */
class MemberViewSubgroupRollupTest {

    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String ASKER = "asker@x.com";
    private static final String KID_A = "a@school.org";
    private static final String KID_B = "b@school.org";
    private static final String KID_C = "c@school.org";

    private GroupRepo groupRepo;
    private UserInfoRepo userRepo;
    private NotificationLogRepo logRepo;
    private GroupViewService service;
    private final Instant childStart = Instant.now().minus(Duration.ofHours(2));

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        userRepo = mock(UserInfoRepo.class);
        logRepo = mock(NotificationLogRepo.class);
        service = new GroupViewService(groupRepo, userRepo, mock(GroupPostRepo.class),
                mock(HouseholdManualMemberService.class), mock(HouseholdAccompanimentService.class),
                mock(PlatformAccessService.class), mock(AgencyStaffService.class), mock(CheckInRequestService.class),
                logRepo, mock(UserSavedLocationRepo.class), null);
        ReflectionTestUtils.setField(service, "checkInHours", 48);

        Group parent = group("parent", "Community", "Not Active", null);
        parent.setOwnerEmail(OWNER);
        parent.setAdminEmails(new ArrayList<>(List.of(ADMIN)));
        parent.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, MEMBER)));
        parent.setPendingMemberEmails(new ArrayList<>(List.of(ASKER)));
        parent.setGroupCode("PARENT-1");
        parent.setSubGroupIDs(new ArrayList<>(List.of("calm", "running", "gone")));
        when(groupRepo.findByGroupId("parent")).thenReturn(Optional.of(parent));

        // "calm": one SAFE, one HELP, no check-in.
        Group calm = group("calm", "Community", "Not Active", null);
        calm.setMemberEmails(new ArrayList<>(List.of(KID_A, KID_B)));
        // "running": a check-in started 2h ago. KID_C answered SAFE before it
        // started — that must count as noResponse here (the one rule).
        Group running = group("running", "Community", "Active", childStart);
        running.setMemberEmails(new ArrayList<>(List.of(KID_C)));
        when(groupRepo.findAllById(anyList())).thenReturn(List.of(running, calm));

        when(userRepo.findByUserEmailIn(anyList())).thenAnswer(inv -> {
            List<String> wanted = inv.getArgument(0);
            Map<String, UserInfo> all = new HashMap<>();
            all.put(OWNER, user(OWNER, "u-owner", "Olive", null, null));
            all.put(ADMIN, user(ADMIN, "u-admin", "Ada", null, null));
            all.put(MEMBER, user(MEMBER, "u-member", "Max", null, null));
            all.put(ASKER, user(ASKER, "u-asker", "Asa", null, null));
            all.put(KID_A, user(KID_A, "u-a", "Ann", "SAFE", Instant.now().minusSeconds(60)));
            all.put(KID_B, user(KID_B, "u-b", "Ben", "HELP", Instant.now().minusSeconds(60)));
            all.put(KID_C, user(KID_C, "u-c", "Cal", "SAFE", childStart.minus(Duration.ofHours(1))));
            List<UserInfo> out = new ArrayList<>();
            for (String w : wanted) if (all.containsKey(w)) out.add(all.get(w));
            return out;
        });
    }

    private static Group group(String id, String type, String alert, Instant activatedAt) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName("Group " + id);
        g.setGroupType(type);
        g.setAlert(alert);
        g.setAlertActivatedAt(activatedAt);
        g.setMemberEmails(new ArrayList<>());
        return g;
    }

    private static UserInfo user(String email, String id, String first, String status, Instant at) {
        UserInfo u = new UserInfo();
        u.setId(id);
        u.setUserEmail(email);
        u.setUserFirstName(first);
        u.setUserStatus(status);
        u.setUserStatusLastUpdated(at);
        u.setGroupLocationSharing(new HashMap<>());
        return u;
    }

    private GroupMemberViewDto view(String viewer) {
        return service.buildMemberView("parent", viewer).orElseThrow();
    }

    @Test
    void parentAdmin_getsCountsAndWorstStatusPerSubGroup_inLinkOrder() {
        List<SubGroupRollup> subs = view(ADMIN).subGroups();
        assertThat(subs).extracting(SubGroupRollup::groupId).containsExactly("calm", "running");

        SubGroupRollup calm = subs.get(0);
        assertThat(calm.rollup().total()).isEqualTo(2);
        assertThat(calm.rollup().safe()).isEqualTo(1);
        assertThat(calm.rollup().help()).isEqualTo(1);
        assertThat(calm.worstStatus()).isEqualTo("HELP");
        assertThat(calm.checkInActive()).isFalse();

        SubGroupRollup running = subs.get(1);
        // A SAFE from before the sub group's check-in is noResponse there.
        assertThat(running.rollup().safe()).isZero();
        assertThat(running.rollup().noResponse()).isEqualTo(1);
        assertThat(running.worstStatus()).isEqualTo("UNKNOWN");
        assertThat(running.checkInActive()).isTrue();
    }

    /**
     * B4 review (2026-10-14): in calm there is no check-in to clamp to, so
     * only CURRENT statuses count (StatusFreshness). A SAFE from 30 days ago
     * is no answer — the sub group must not read as an all-clear — while a
     * HELP never lapses.
     */
    @Test
    void calmSubGroup_aStaleSafeIsNoAnswer_butOldBadNewsStays() {
        String stale = "stale@school.org";
        String oldHelp = "oldhelp@school.org";
        Group parent = group("p2", "Community", "Not Active", null);
        parent.setOwnerEmail(OWNER);
        parent.setMemberEmails(new ArrayList<>(List.of(OWNER)));
        parent.setSubGroupIDs(new ArrayList<>(List.of("quiet", "worried")));
        when(groupRepo.findByGroupId("p2")).thenReturn(Optional.of(parent));
        Group quiet = group("quiet", "Community", "Not Active", null);
        quiet.setMemberEmails(new ArrayList<>(List.of(stale)));
        Group worried = group("worried", "Community", "Not Active", null);
        worried.setMemberEmails(new ArrayList<>(List.of(oldHelp, stale)));
        when(groupRepo.findAllById(anyList())).thenReturn(List.of(quiet, worried));
        Instant monthAgo = Instant.now().minus(Duration.ofDays(30));
        when(userRepo.findByUserEmailIn(anyList())).thenReturn(List.of(
                user(OWNER, "u-owner", "Olive", null, null),
                user(stale, "u-s", "Sid", "SAFE", monthAgo),
                user(oldHelp, "u-h", "Hal", "HELP", monthAgo)));

        List<SubGroupRollup> subs = service.buildMemberView("p2", OWNER).orElseThrow().subGroups();
        assertThat(subs).extracting(SubGroupRollup::groupId).containsExactly("quiet", "worried");
        SubGroupRollup q = subs.get(0);
        assertThat(q.rollup().safe()).isZero();
        assertThat(q.rollup().noResponse()).isEqualTo(1);
        assertThat(q.worstStatus()).isEqualTo("UNKNOWN").isNotEqualTo("SAFE");
        SubGroupRollup w = subs.get(1);
        assertThat(w.rollup().help()).isEqualTo(1);
        assertThat(w.rollup().safe()).isZero();
        assertThat(w.worstStatus()).isEqualTo("HELP");
    }

    /**
     * B4 review (2026-10-14): a household can't be linked under an
     * organization (D14), and prod still holds legacy links. Its name, size
     * and check-in state are its own, so the parent's admins get no row for it.
     */
    @Test
    void aLinkedHousehold_isNotListed() throws Exception {
        String mom = "mom@family.org";
        Group parent = group("p3", "Community", "Not Active", null);
        parent.setOwnerEmail(OWNER);
        parent.setMemberEmails(new ArrayList<>(List.of(OWNER)));
        parent.setSubGroupIDs(new ArrayList<>(List.of("home", "calm")));
        when(groupRepo.findByGroupId("p3")).thenReturn(Optional.of(parent));
        Group home = group("home", "Household", "Active", childStart);
        home.setGroupName("The Smiths");
        home.setMemberEmails(new ArrayList<>(List.of(mom)));
        Group calm = group("calm", "Community", "Not Active", null);
        calm.setMemberEmails(new ArrayList<>(List.of(KID_A)));
        when(groupRepo.findAllById(anyList())).thenReturn(List.of(home, calm));

        List<SubGroupRollup> subs = service.buildMemberView("p3", OWNER).orElseThrow().subGroups();
        assertThat(subs).extracting(SubGroupRollup::groupId).containsExactly("calm");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(subs))
                .doesNotContain("Smiths").doesNotContain("home");
        // Its members aren't even read.
        org.mockito.Mockito.verify(userRepo, org.mockito.Mockito.never())
                .findByUserEmailIn(org.mockito.ArgumentMatchers.argThat(l -> l != null && l.contains(mom)));
    }

    @Test
    void subGroupRollups_carryNoPeople_D14() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view(OWNER).subGroups());
        assertThat(json).doesNotContain("@").doesNotContain("Ann").doesNotContain("Cal");
    }

    @Test
    void plainMember_getsNoSubGroups_noPending_noCode() {
        GroupMemberViewDto v = view(MEMBER);
        assertThat(v.subGroups()).isNull();
        assertThat(v.pendingMembers()).isNull();
        assertThat(v.group().groupCode()).isNull();
        assertThat(v.viewerRole()).isEqualTo("member");
        GroupMemberViewDto.ViewerCapabilities c = v.viewerCapabilities();
        assertThat(c.manageMembers()).isFalse();
        assertThat(c.pinPosts()).isFalse();
        assertThat(c.viewAdminDashboard()).isFalse();
    }

    @Test
    void ownerAndAdmin_getPendingRequesters_withNames() {
        for (String viewer : List.of(OWNER, ADMIN)) {
            List<GroupMemberViewDto.PendingMember> p = view(viewer).pendingMembers();
            assertThat(p).hasSize(1);
            assertThat(p.get(0).email()).isEqualTo(ASKER);
            assertThat(p.get(0).userId()).isEqualTo("u-asker");
            assertThat(p.get(0).firstName()).isEqualTo("Asa");
        }
        assertThat(view(ADMIN).group().groupCode()).isEqualTo("PARENT-1");
    }

    @Test
    void everyRow_carriesItsRoleAndUserId_andAPlainMemberSeesNoCoMemberEmail_D6() {
        // A plain org member: rows keyed by userId; only their OWN row keeps an email.
        GroupMemberViewDto pv = view(MEMBER);
        assertThat(pv.members()).isNotEmpty();
        for (MemberSummary m : pv.members()) {
            if (!"u-member".equals(m.userId())) assertThat(m.email()).as("co-member email").isNull();
            else assertThat(m.email()).isEqualTo(MEMBER);
        }
        assertThat(pv.group().ownerEmail()).isNull();
        assertThat(pv.group().adminEmails()).isEmpty();
        assertThat(pv.members().stream().map(MemberSummary::role)).contains("owner", "admin", "member");
        // An admin keeps every email.
        Map<String, MemberSummary> rows = new HashMap<>();
        for (MemberSummary m : view(ADMIN).members()) rows.put(m.email(), m);
        assertThat(rows.get(OWNER).role()).isEqualTo("owner");
        assertThat(rows.get(ADMIN).role()).isEqualTo("admin");
        assertThat(rows.get(MEMBER).role()).isEqualTo("member");
        assertThat(rows.get(MEMBER).userId()).isEqualTo("u-member");
        assertThat(view(ADMIN).group().ownerEmail()).isEqualTo(OWNER);
    }

    @Test
    void capabilities_followTheGroupRoleMatrix() {
        GroupMemberViewDto.ViewerCapabilities owner = view(OWNER).viewerCapabilities();
        assertThat(owner.managePlan()).isTrue();
        assertThat(owner.deleteGroup()).isTrue();
        assertThat(owner.transferOwnership()).isTrue();
        GroupMemberViewDto.ViewerCapabilities admin = view(ADMIN).viewerCapabilities();
        assertThat(admin.manageMembers()).isTrue();
        assertThat(admin.manageAlerts()).isTrue();
        assertThat(admin.editGroup()).isTrue();
        assertThat(admin.viewAdminDashboard()).isTrue();
        assertThat(admin.pinPosts()).isTrue();
        assertThat(admin.managePlan()).isFalse();
        assertThat(admin.deleteGroup()).isFalse();
        assertThat(admin.transferOwnership()).isFalse();
    }

    @Test
    void windowFields_comeFromTheServer() {
        Group running = group("running", "Community", "Active", childStart);
        running.setOwnerEmail(OWNER);
        running.setMemberEmails(new ArrayList<>(List.of(OWNER)));
        when(groupRepo.findByGroupId("running")).thenReturn(Optional.of(running));
        GroupMemberViewDto.GroupInfo g = service.buildMemberView("running", OWNER).orElseThrow().group();
        assertThat(g.checkInWindowHours()).isEqualTo(48);
        // K4: offered in the last 12 hours — 48h window, so 36h after the start.
        assertThat(g.continueAvailableFrom()).isEqualTo(childStart.plus(Duration.ofHours(36)));
        // Calm: nothing to continue.
        assertThat(view(OWNER).group().continueAvailableFrom()).isNull();
    }

    @Test
    void dispatch_isReadOncePerGroup_andOnlyThisGroupsRequests() {
        Group running = group("running", "Community", "Active", childStart);
        running.setOwnerEmail(OWNER);
        running.setMemberEmails(new ArrayList<>(List.of(OWNER, KID_C)));
        when(groupRepo.findByGroupId("running")).thenReturn(Optional.of(running));
        NotificationLog failed = log(KID_C, false, childStart.plusSeconds(10));
        NotificationLog retried = log(KID_C, true, childStart.plusSeconds(20));
        when(logRepo.findByTypeAndReferenceIdAndTimestampGreaterThanEqual(
                eq("check_in_request"), eq("running"), any())).thenReturn(List.of(retried, failed));

        Map<String, MemberSummary> rows = new HashMap<>();
        for (MemberSummary m : service.buildMemberView("running", OWNER).orElseThrow().members()) rows.put(m.email(), m);
        assertThat(rows.get(KID_C).checkInDispatch()).isEqualTo("sent"); // the LAST attempt wins
        assertThat(rows.get(OWNER).checkInDispatch()).isEqualTo("unknown");
        org.mockito.Mockito.verify(logRepo, org.mockito.Mockito.never())
                .findByRecipientEmailAndTypeAndTimestampAfterOrderByTimestampAsc(anyString(), anyString(), any());
    }

    private static NotificationLog log(String email, boolean success, Instant at) {
        NotificationLog n = new NotificationLog();
        ReflectionTestUtils.setField(n, "recipientEmail", email);
        ReflectionTestUtils.setField(n, "success", success);
        ReflectionTestUtils.setField(n, "timestamp", at);
        return n;
    }
}
