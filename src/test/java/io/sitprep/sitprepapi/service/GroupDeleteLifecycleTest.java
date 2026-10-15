package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.domain.GroupInviteRedemption;
import io.sitprep.sitprepapi.domain.GroupMutePref;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.GroupReadState;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupAlertFrame;
import io.sitprep.sitprepapi.repo.GroupInviteRedemptionRepo;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import io.sitprep.sitprepapi.repo.GroupMutePrefRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostReactionRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.resource.GroupResource;
import io.sitprep.sitprepapi.websocket.GroupTopicEvictor;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.messaging.Message;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.dto.CheckInRollupDto;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-09: deleting a group is one transaction, not a bare row delete.
 *
 * <p>Before: a running check-in never ended (open pages kept it, the Live
 * Activity kept a running card), members whose base was the household kept
 * pointing at a dead id, and invites, mutes, read state and link lists were
 * orphaned.</p>
 */
class GroupDeleteLifecycleTest {

    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String KID = "kid@x.com";

    private GroupRepo groupRepo;
    private UserInfoRepo userInfoRepo;
    private WebSocketMessageSender ws;
    private LiveActivityPushService liveActivities;
    private HouseholdEventService householdEvents;
    private HouseholdProvisioningService provisioning;
    private GroupInviteRepo inviteRepo;
    private GroupInviteRedemptionRepo redemptionRepo;
    private GroupMutePrefRepo muteRepo;
    private GroupReadStateRepo readRepo;
    private GroupPostRepo postRepo;
    private GroupPostCommentRepo commentRepo;
    private GroupPostReactionRepo postReactionRepo;
    private GroupPostCommentReactionRepo commentReactionRepo;
    private GroupTopicEvictor evictor;
    private GroupService groupService;
    private GroupDeletionService deletion;

    private Group hh;
    private Group parent;
    private UserInfo owner;
    private UserInfo kid;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        ws = mock(WebSocketMessageSender.class);
        liveActivities = mock(LiveActivityPushService.class);
        householdEvents = mock(HouseholdEventService.class);
        provisioning = mock(HouseholdProvisioningService.class);
        inviteRepo = mock(GroupInviteRepo.class);
        redemptionRepo = mock(GroupInviteRedemptionRepo.class);
        muteRepo = mock(GroupMutePrefRepo.class);
        readRepo = mock(GroupReadStateRepo.class);
        postRepo = mock(GroupPostRepo.class);
        commentRepo = mock(GroupPostCommentRepo.class);
        postReactionRepo = mock(GroupPostReactionRepo.class);
        commentReactionRepo = mock(GroupPostCommentReactionRepo.class);
        evictor = mock(GroupTopicEvictor.class);

        groupService = new GroupService(groupRepo, userInfoRepo, ws, householdEvents,
                mock(NotificationService.class), mock(CheckInRequestService.class));
        groupService.setLiveActivities(liveActivities);
        ObjectProvider<GroupTopicEvictor> evictorProvider = mock(ObjectProvider.class);
        when(evictorProvider.getIfAvailable()).thenReturn(evictor);
        deletion = newDeletion(evictorProvider, null, null);

        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        when(userInfoRepo.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));

        hh = new Group();
        hh.setGroupId("hh-1");
        hh.setGroupName("The Ruiz household");
        hh.setGroupType("Household");
        hh.setOwnerEmail(OWNER);
        hh.setAdminEmails(new ArrayList<>(List.of(OWNER, ADMIN)));
        hh.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, KID)));
        hh.setParentGroupIDs(new ArrayList<>(List.of("p-1")));
        hh.setSubGroupIDs(new ArrayList<>());
        hh.setAlert("Inactive");
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(hh));

        parent = new Group();
        parent.setGroupId("p-1");
        parent.setGroupType("Church");
        parent.setSubGroupIDs(new ArrayList<>(List.of("hh-1", "other")));
        when(groupRepo.findByGroupId("p-1")).thenReturn(Optional.of(parent));

        owner = user(OWNER, "hh-1", Set.of("hh-1", "p-1"), Set.of("hh-1"));
        kid = user(KID, "hh-1", Set.of("hh-1"), Set.of());
        UserInfo admin = user(ADMIN, "elsewhere", Set.of("hh-1"), Set.of("hh-1"));
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(owner, admin, kid));

        when(inviteRepo.findByGroupId("hh-1")).thenReturn(List.of(new GroupInvite(), new GroupInvite()));
        when(redemptionRepo.findByGroupId("hh-1")).thenReturn(List.of(new GroupInviteRedemption()));
        when(muteRepo.findByGroupId("hh-1")).thenReturn(List.of(new GroupMutePref()));
        when(readRepo.findByGroupId("hh-1")).thenReturn(List.of(new GroupReadState(), new GroupReadState()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @SuppressWarnings("unchecked")
    private GroupDeletionService newDeletion(ObjectProvider<GroupTopicEvictor> evictorProvider,
                                             LiveActivityStateBuilder state, LiveActivityPushService la) {
        ObjectProvider<LiveActivityStateBuilder> stateProvider = mock(ObjectProvider.class);
        when(stateProvider.getIfAvailable()).thenReturn(state);
        ObjectProvider<LiveActivityPushService> laProvider = mock(ObjectProvider.class);
        when(laProvider.getIfAvailable()).thenReturn(la);
        return new GroupDeletionService(groupRepo, userInfoRepo, groupService, provisioning,
                inviteRepo, redemptionRepo, muteRepo, readRepo, postRepo, commentRepo,
                postReactionRepo, commentReactionRepo, evictorProvider, stateProvider, laProvider);
    }

    private static UserInfo user(String email, String base, Set<String> joined, Set<String> managed) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setBaseHouseholdId(base);
        u.setJoinedGroupIDs(new HashSet<>(joined));
        u.setManagedGroupIDs(new HashSet<>(managed));
        return u;
    }

    @Test
    void deleteEndsARunningCheckIn() {
        Instant started = Instant.parse("2026-10-14T15:00:00Z");
        hh.setAlert("Active");
        hh.setAlertActivatedAt(started);

        var result = deletion.delete("hh-1", OWNER, GroupDeletionService.Posts.KEEP);

        assertThat(result.checkInEnded()).isTrue();
        ArgumentCaptor<GroupAlertFrame> frame = ArgumentCaptor.forClass(GroupAlertFrame.class);
        verify(ws).sendGroupAlertStatus(eq("hh-1"), frame.capture());
        assertThat(frame.getValue().alert()).isEqualTo("Cleared");
        verify(householdEvents).recordCheckinEnded("hh-1", OWNER);
        // The Live Activity end hook fires with the start of the check-in that ended.
        verify(liveActivities).situationEnded("hh-1", started, LiveActivityStateBuilder.REASON_MANUAL, OWNER);
        verify(groupRepo).delete(hh);
    }

    /**
     * The real push service, with a real commit order: both end hooks run
     * after the commit, when the row is gone. The card must still get an
     * APNs "end" (B5 review: before the fix the group-gone branch retired the
     * tokens and sent nothing, so the card stayed on the lock screen).
     */
    @Test
    @SuppressWarnings("unchecked")
    void deletingMidCheckInSendsTheLockScreenCardItsEnd() throws Exception {
        Instant started = Instant.now().minusSeconds(20 * 60);
        hh.setAlert("Active");
        hh.setAlertActivatedAt(started);

        // The builder answers only while the row exists, like the real one.
        boolean[] deleted = {false};
        org.mockito.Mockito.doAnswer(i -> { deleted[0] = true; return null; }).when(groupRepo).delete(hh);
        LiveActivityStateBuilder state = mock(LiveActivityStateBuilder.class);
        CheckInRollupDto rollup = new CheckInRollupDto("hh-1", "The Ruiz household", true, started, 3, 2,
                2, 0, 0, 1, List.of(), null);
        LiveActivityStateBuilder.Situation situation = new LiveActivityStateBuilder.Situation(
                "hh-1", "The Ruiz household", "Household", rollup, started, null, false, null);
        when(state.endedSituation(eq("hh-1"), any())).thenAnswer(i ->
                deleted[0] ? Optional.empty() : Optional.of(situation));

        LiveActivityTokenRepo tokenRepo = mock(LiveActivityTokenRepo.class);
        LiveActivityToken t = new LiveActivityToken();
        t.setId(7L);
        t.setUserEmail(KID);
        t.setKind("situation");
        t.setTokenType("update");
        t.setReferenceId("hh-1");
        t.setApnsToken("abcdef0123456789");
        t.setFcmToken("fcm-7");
        List<LiveActivityToken> live = new ArrayList<>(List.of(t));
        when(tokenRepo.findLiveUpdateTokens("situation", "hh-1")).thenAnswer(i -> new ArrayList<>(live));
        org.mockito.Mockito.doAnswer(i -> { live.clear(); return 1; })
                .when(tokenRepo).endByIds(anyList(), any());
        List<Message> sent = new ArrayList<>();
        LiveActivityPushService la = new LiveActivityPushService(tokenRepo, () -> state, email -> false,
                m -> { sent.add(m); return "ok"; },
                (task, delay) -> { }, Runnable::run, java.time.Duration.ofSeconds(10),
                LiveActivityPushService.DEFAULT_APNS_TOPIC, null);
        groupService.setLiveActivities(la);
        ObjectProvider<GroupTopicEvictor> evictorProvider = mock(ObjectProvider.class);
        deletion = newDeletion(evictorProvider, state, la);

        // A transaction: hooks wait for the commit.
        TransactionSynchronizationManager.initSynchronization();
        deletion.delete("hh-1", OWNER, GroupDeletionService.Posts.KEEP);
        assertThat(sent).as("nothing goes out before the commit").isEmpty();
        assertThat(deleted[0]).isTrue();
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        assertThat(sent).hasSize(1);
        JsonNode msg = new ObjectMapper().readTree(GsonFactory.getDefaultInstance().toString(sent.get(0)));
        // The end reaches the token that existed before the delete: the delete
        // leaves live_activity_token alone, the end path reads and retires it.
        assertThat(msg.path("token").asText()).isEqualTo("fcm-7");
        assertThat(msg.path("apns").path("live_activity_token").asText()).isEqualTo("abcdef0123456789");
        JsonNode aps = msg.path("apns").path("payload").path("aps");
        assertThat(aps.path("event").asText()).isEqualTo("end");
        assertThat(aps.path("content-state").path("phase").asText()).isEqualTo("ended");
        assertThat(aps.path("content-state").path("accounted").asInt()).isEqualTo(2);
        assertThat(aps.path("content-state").path("note").asText()).isEqualTo("This group was removed.");
        verify(tokenRepo).endByIds(eq(List.of(7L)), any()); // once: the ordinary hook then finds no tokens
    }

    @Test
    void basesAreReanchoredOnlyAfterTheRowIsGone() {
        var result = deletion.delete("hh-1", OWNER, GroupDeletionService.Posts.KEEP);

        assertThat(result.reanchored()).isEqualTo(2);
        InOrder order = inOrder(groupRepo, provisioning);
        order.verify(groupRepo).delete(hh);
        order.verify(groupRepo).flush();
        order.verify(provisioning).ensureBaseHousehold(owner);
        order.verify(provisioning).ensureBaseHousehold(kid);
        // Joined/managed ids lose the dead id; unrelated ids stay.
        assertThat(owner.getJoinedGroupIDs()).containsExactly("p-1");
        assertThat(owner.getManagedGroupIDs()).isEmpty();
        assertThat(kid.getJoinedGroupIDs()).isEmpty();
    }

    @Test
    void linksInvitesMutesAndReadStateAreCleared() {
        var result = deletion.delete("hh-1", OWNER, GroupDeletionService.Posts.KEEP);

        assertThat(parent.getSubGroupIDs()).containsExactly("other");
        assertThat(result.linksDropped()).isEqualTo(1);
        assertThat(result.invitesDeleted()).isEqualTo(2);
        assertThat(result.mutePrefsDeleted()).isEqualTo(1);
        assertThat(result.readStatesDeleted()).isEqualTo(2);
        verify(inviteRepo).deleteAll(anyList());
        verify(redemptionRepo).deleteAll(anyList());
        verify(muteRepo).deleteAll(anyList());
        verify(readRepo).deleteAll(anyList());
        verify(evictor).evictAll("hh-1");
    }

    @Test
    void postsAreKeptByDefault() {
        var result = deletion.delete("hh-1", OWNER, GroupDeletionService.Posts.KEEP);
        assertThat(result.postsDeleted()).isZero();
        verify(postRepo, never()).findPostsByGroupId(anyString());
        verify(postRepo, never()).deleteAll(anyList());
        verify(commentRepo, never()).deleteAll(anyList());
    }

    @Test
    void postsGoWhenTheOwnerAsks() {
        GroupPost p = new GroupPost();
        p.setId(7L);
        p.setGroupId("hh-1");
        GroupPostComment c = new GroupPostComment();
        c.setId(70L);
        c.setPostId(7L);
        when(postRepo.findPostsByGroupId("hh-1")).thenReturn(List.of(p));
        when(commentRepo.findAllByPostIdInOrderByPostIdAscTimestampAsc(List.of(7L))).thenReturn(List.of(c));

        var result = deletion.delete("hh-1", OWNER, GroupDeletionService.Posts.DELETE);

        assertThat(result.postsDeleted()).isEqualTo(1);
        verify(commentReactionRepo).findByGroupPostCommentIdIn(List.of(70L));
        verify(commentRepo).deleteAll(List.of(c));
        verify(postReactionRepo).findByPostIdIn(List.of(7L));
        verify(postRepo).deleteAll(List.of(p));
    }

    @Test
    void onlyTheOwnerDeletes() {
        assertThatThrownBy(() -> deletion.delete("hh-1", ADMIN, GroupDeletionService.Posts.KEEP))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(groupRepo, never()).delete(any(Group.class));
        verify(inviteRepo, never()).deleteAll(anyList());
    }

    @Test
    void anUnknownPostsModeIsA400BeforeAnythingHappens() {
        GroupResource resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", groupService);
        ReflectionTestUtils.setField(resource, "groupDeletionService", deletion);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThatThrownBy(() -> resource.deleteGroup("hh-1", "archive"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(groupRepo, never()).delete(any(Group.class));

        assertThat(resource.deleteGroup("hh-1", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(groupRepo).delete(hh);
    }
}
