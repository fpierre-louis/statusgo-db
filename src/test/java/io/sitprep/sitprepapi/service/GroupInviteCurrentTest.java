package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.dto.GroupInviteLinkDto;
import io.sitprep.sitprepapi.dto.GroupMembershipFrame;
import io.sitprep.sitprepapi.repo.GroupInviteRedemptionRepo;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JN-10 (one share link per group, reset = revoke + mint; members get the
 * non-approving link) and JN-20 (an admin-issued link is the approval, D19).
 *
 * <p>The share sheet used to mint a fresh unlimited token on every open and
 * 403 for every member, so a group piled up live tokens nobody could list or
 * revoke, and a private group's admin link only queued its redeemer.</p>
 */
class GroupInviteCurrentTest {

    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";

    private GroupInviteRepo inviteRepo;
    private GroupService groupService;
    private GroupInviteService service;
    private final List<GroupInvite> store = new ArrayList<>();
    private Group org;
    private Group household;

    @BeforeEach
    void setUp() {
        inviteRepo = mock(GroupInviteRepo.class);
        groupService = mock(GroupService.class);
        service = new GroupInviteService(inviteRepo, mock(GroupInviteRedemptionRepo.class), groupService);

        // A small in-memory invite table behind the repo.
        when(inviteRepo.save(any(GroupInvite.class))).thenAnswer(i -> {
            GroupInvite inv = i.getArgument(0);
            if (!store.contains(inv)) store.add(inv);
            return inv;
        });
        when(inviteRepo.findActiveByGroup(anyString(), any(Instant.class))).thenAnswer(i -> {
            String gid = i.getArgument(0);
            Instant now = i.getArgument(1);
            return store.stream()
                    .filter(v -> v.getGroupId().equals(gid) && v.getRevokedAt() == null
                            && v.getExpiresAt().isAfter(now)
                            && (v.getMaxUses() == null || v.getUsedCount() < v.getMaxUses()))
                    .sorted(Comparator.comparing(GroupInvite::getIssuedAt).reversed())
                    .toList();
        });
        when(inviteRepo.findById(anyString())).thenAnswer(i ->
                store.stream().filter(v -> v.getId().equals(i.getArgument(0))).findFirst());

        org = group("g-1", "Church");
        household = group("hh-1", "Household");
    }

    private Group group(String id, String type) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName("Grace");
        g.setGroupType(type);
        g.setPrivacy("Private");
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(new ArrayList<>(List.of(OWNER, ADMIN)));
        g.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, MEMBER)));
        when(groupService.getGroupByPublicId(id)).thenReturn(g);
        return g;
    }

    private GroupInvite seed(String id, String groupId, Integer maxUses, Instant issuedAt) {
        GroupInvite inv = new GroupInvite();
        inv.setId(id);
        inv.setGroupId(groupId);
        inv.setIssuedByEmail(OWNER);
        inv.setIssuedAt(issuedAt);
        inv.setExpiresAt(issuedAt.plus(Duration.ofDays(7)));
        inv.setMaxUses(maxUses);
        inv.setUsedCount(0);
        store.add(inv);
        return inv;
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    // ── JN-10: one link ──────────────────────────────────────────────────────

    @Test
    void openingTheSheetTenTimesLeavesOneToken() {
        GroupInviteLinkDto first = service.currentLink("g-1", ADMIN);
        for (int i = 0; i < 9; i++) {
            assertThat(service.currentLink("g-1", OWNER).id()).isEqualTo(first.id());
        }
        assertThat(store).hasSize(1);
        assertThat(first.path()).isEqualTo("/share/i/" + first.id());
        assertThat(first.approving()).isTrue();
        assertThat(first.current()).isTrue();
        assertThat(first.canReset()).isTrue();
        assertThat(store.get(0).getMaxUses()).isNull(); // an org link is unlimited
    }

    @Test
    void theCurrentLinkIsTheNewestLiveOneAndItsExpirySlides() {
        Instant now = Instant.now();
        seed("old", "g-1", null, now.minus(Duration.ofDays(3)));
        GroupInvite newest = seed("new", "g-1", null, now.minus(Duration.ofDays(1)));
        seed("one-person", "g-1", 1, now); // single-use: not the org's share link

        GroupInviteLinkDto link = service.currentLink("g-1", ADMIN);

        assertThat(link.id()).isEqualTo("new");
        assertThat(newest.getExpiresAt()).isAfter(now.plus(GroupInviteService.CURRENT_TTL).minusSeconds(60));
        assertThat(store).hasSize(3); // nothing minted
    }

    @Test
    void anOrgMemberGetsTheNonApprovingLinkAndNothingIsWritten() {
        GroupInviteLinkDto link = service.currentLink("g-1", "MEMBER@x.com");
        assertThat(link.id()).isNull();
        assertThat(link.path()).isEqualTo("/share/group/g-1");
        assertThat(link.approving()).isFalse();
        assertThat(link.canReset()).isFalse();
        verify(inviteRepo, never()).save(any());
    }

    @Test
    void strangersRequestersAndPlainHouseholdMembersAreRefused() {
        org.setPendingMemberEmails(new ArrayList<>(List.of("asker@x.com")));
        assertStatus(() -> service.currentLink("g-1", "stranger@x.com"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.currentLink("g-1", "asker@x.com"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.currentLink("hh-1", MEMBER), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.resetLink("g-1", MEMBER), HttpStatus.FORBIDDEN);
        verify(inviteRepo, never()).save(any());
    }

    @Test
    void aHouseholdGetsANewSingleUseLinkOnEveryShare() {
        // The spouse, then the teen: two shares, two one-person links. Reusing
        // one would leave the second person EXHAUSTED (B5 review).
        GroupInviteLinkDto spouse = service.currentLink("hh-1", OWNER);
        GroupInviteLinkDto teen = service.currentLink("hh-1", ADMIN);

        assertThat(spouse.path()).isEqualTo("/share/household/" + spouse.id());
        assertThat(spouse.maxUses()).isEqualTo(1);
        assertThat(teen.maxUses()).isEqualTo(1);
        assertThat(teen.id()).isNotEqualTo(spouse.id());
        assertThat(spouse.approving()).isTrue();
        assertThat(store).hasSize(2);
        assertThat(store).allSatisfy(v -> assertThat(v.getRevokedAt()).isNull()); // both still redeemable
    }

    @Test
    void aLinkFromAFormerAdminIsNeverHandedOutAsTheCurrentApprovingLink() {
        Instant now = Instant.now();
        GroupInvite stale = seed("old-admin", "g-1", null, now.minus(Duration.ofDays(1)));
        stale.setIssuedByEmail("stepped-down@x.com");

        GroupInviteLinkDto link = service.currentLink("g-1", ADMIN);

        assertThat(link.id()).isNotEqualTo("old-admin");
        assertThat(link.approving()).isTrue();
        assertThat(store).hasSize(2); // a fresh one was minted; the old one is left as it was
        assertThat(stale.getRevokedAt()).isNull();

        List<GroupInviteLinkDto> rows = service.listLinks("g-1");
        assertThat(rows).extracting(GroupInviteLinkDto::id).containsExactly(link.id(), "old-admin");
        assertThat(rows).extracting(GroupInviteLinkDto::approving).containsExactly(true, false);
        assertThat(rows).extracting(GroupInviteLinkDto::current).containsExactly(true, false);
    }

    @Test
    void resetRevokesEveryLiveShareLinkAndMintsOne() {
        Instant now = Instant.now();
        GroupInvite a = seed("a", "g-1", null, now.minus(Duration.ofDays(2)));
        GroupInvite b = seed("b", "g-1", null, now.minus(Duration.ofDays(1)));
        GroupInvite single = seed("single", "g-1", 1, now);

        GroupInviteLinkDto fresh = service.resetLink("g-1", ADMIN);

        assertThat(a.getRevokedAt()).isNotNull();
        assertThat(b.getRevokedAt()).isNotNull();
        assertThat(single.getRevokedAt()).isNull(); // not a share link
        assertThat(fresh.id()).isNotIn("a", "b", "single");
        assertThat(service.currentLink("g-1", ADMIN).id()).isEqualTo(fresh.id());
    }

    @Test
    void theListFlagsTheCurrentLink() {
        Instant now = Instant.now();
        seed("old", "g-1", null, now.minus(Duration.ofDays(2)));
        seed("new", "g-1", null, now.minus(Duration.ofDays(1)));

        List<GroupInviteLinkDto> rows = service.listLinks("g-1");

        assertThat(rows).extracting(GroupInviteLinkDto::id).containsExactly("new", "old");
        assertThat(rows).extracting(GroupInviteLinkDto::current).containsExactly(true, false);
        assertThat(rows.get(0).path()).isEqualTo("/share/i/new");
    }

    // ── JN-20: the admin's link is the approval ──────────────────────────────

    @Test
    void aLinkFromACurrentAdminJoinsDirectly() {
        GroupInvite inv = seed("inv", "g-1", null, Instant.now());
        inv.setIssuedByEmail(ADMIN);
        when(inviteRepo.findByIdForUpdate("inv")).thenReturn(Optional.of(inv));
        when(groupService.joinByInvite("g-1", "new@x.com")).thenReturn(org);

        var result = service.redeem("inv", "New@x.com");

        assertThat(result.isOk()).isTrue();
        verify(groupService).joinByInvite("g-1", "new@x.com");
        verify(groupService, never()).selfJoin(anyString(), anyString());
    }

    @Test
    void aLinkFromSomeoneNoLongerAnAdminIsOnlyARequest() {
        GroupInvite inv = seed("inv", "g-1", null, Instant.now());
        inv.setIssuedByEmail("stepped-down@x.com");
        when(inviteRepo.findByIdForUpdate("inv")).thenReturn(Optional.of(inv));
        when(groupService.selfJoin("g-1", "new@x.com")).thenReturn(org);

        service.redeem("inv", "new@x.com");

        verify(groupService).selfJoin("g-1", "new@x.com");
        verify(groupService, never()).joinByInvite(anyString(), anyString());
    }

    @Test
    void joinByInviteSkipsTheQueueButNotTheSeatCap() {
        GroupRepo repo = mock(GroupRepo.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        WebSocketMessageSender ws = mock(WebSocketMessageSender.class);
        GroupService real = new GroupService(repo, users, ws, mock(HouseholdEventService.class),
                mock(NotificationService.class), mock(CheckInRequestService.class));
        when(repo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        when(users.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        Group priv = new Group();
        priv.setGroupId("g-2");
        priv.setGroupType("School Staff/Classroom");
        priv.setPrivacy("Private");
        priv.setOwnerEmail(OWNER);
        priv.setMemberEmails(new ArrayList<>(List.of(OWNER)));
        priv.setPendingMemberEmails(new ArrayList<>(List.of("new@x.com")));
        when(repo.findByGroupId("g-2")).thenReturn(Optional.of(priv));

        real.joinByInvite("g-2", " New@x.com ");
        assertThat(priv.getMemberEmails()).containsExactly(OWNER, "new@x.com");
        assertThat(priv.getPendingMemberEmails()).isEmpty();
        ArgumentCaptor<GroupMembershipFrame> frames = ArgumentCaptor.forClass(GroupMembershipFrame.class);
        verify(ws, times(2)).sendGroupMembership(eq("g-2"), frames.capture());
        // The roster hears the ADD; admins hear that the request was answered.
        assertThat(frames.getAllValues()).extracting(GroupMembershipFrame::action)
                .containsExactly("ADD", GroupService.PENDING_CHANGED);

        priv.setMaxSeats(2); // full now
        assertStatus(() -> real.joinByInvite("g-2", "third@x.com"), HttpStatus.CONFLICT);
        assertThat(priv.getMemberEmails()).hasSize(2);

        Group hh = new Group();
        hh.setGroupId("hh-9");
        hh.setGroupType("Household");
        when(repo.findByGroupId("hh-9")).thenReturn(Optional.of(hh));
        assertStatus(() -> real.joinByInvite("hh-9", "new@x.com"), HttpStatus.NOT_FOUND);
    }
}
