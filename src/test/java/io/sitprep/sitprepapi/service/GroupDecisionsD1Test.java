package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CommunityReport;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.GroupPostComment;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupPostCommentDto;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 decisions (2026-10-17): SH-14 replies honour group mute + quiet hours,
 * FD-01 the group_report notice audience and copy, PR-01 no household seat cap.
 */
class GroupDecisionsD1Test {

    private static final String AUTHOR = "author@x.com";
    private static final String COMMENTER = "commenter@x.com";

    private NotificationLogRepo logRepo;
    private GroupMuteService mute;
    private NotificationService notifications;
    private GroupPostCommentService comments;

    @BeforeEach
    void setUp() {
        logRepo = mock(NotificationLogRepo.class);
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(i -> i.getArgument(0));
        mute = mock(GroupMuteService.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        notifications = spy(new NotificationService(mock(WebSocketMessageSender.class), users, logRepo,
                mock(WebSocketPresenceService.class), mock(PushPolicyService.class), mute));
        // The normal path would run presence + policy + FCM; only count that it is reached.
        doNothing().when(notifications).deliverPresenceAware(anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        UserInfo owner = new UserInfo();
        owner.setUserEmail(AUTHOR);
        UserInfo commenter = new UserInfo();
        commenter.setId("u-c");
        GroupPostRepo postRepo = mock(GroupPostRepo.class);
        GroupPost post = new GroupPost();
        post.setId(7L);
        post.setGroupId("g-1");
        post.setGroupName("Grace Church");
        post.setAuthor(AUTHOR);
        when(postRepo.findById(7L)).thenReturn(Optional.of(post));
        when(users.findByUserEmail(AUTHOR)).thenReturn(Optional.of(owner));
        when(users.findByUserEmailIgnoreCase(COMMENTER)).thenReturn(Optional.of(commenter));
        GroupPostService postService = mock(GroupPostService.class);
        MentionService mentions = mock(MentionService.class);
        when(mentions.emailsFor(any())).thenReturn(List.of());
        when(mentions.toPlainText(any())).thenReturn("thanks");
        comments = new GroupPostCommentService(mock(GroupPostCommentRepo.class), postRepo, users,
                mock(WebSocketMessageSender.class), notifications, mock(
                        GroupPostCommentReactionService.class), postService, mentions,
                mock(BlockService.class), mock(GroupContentRemovals.class));
    }

    private void reply() {
        GroupPostComment saved = new GroupPostComment();
        saved.setPostId(7L);
        saved.setAuthor(COMMENTER);
        GroupPostCommentDto dto = new GroupPostCommentDto();
        dto.setContent("thanks");
        dto.setAuthorFirstName("Cam");
        ReflectionTestUtils.invokeMethod(comments, "notifyPostAuthorOnNewComment", saved, dto);
    }

    private void assertSuppressed(String reasonStart) {
        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo).save(row.capture());
        assertThat(row.getValue().getType()).isEqualTo("comment_on_post");
        assertThat(row.getValue().getErrorMessage()).startsWith(reasonStart);
        verify(notifications, never()).deliverPresenceAware(anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void sh14_aMutedGroupGetsNoReplyPush_butTheInboxRowStays() {
        when(mute.isMuted(AUTHOR, "g-1")).thenReturn(true);
        reply();
        assertSuppressed("Muted by recipient");
    }

    @Test
    void sh14_quietHoursHoldTheReplyPush_butTheInboxRowStays() {
        when(mute.isInQuietHours(AUTHOR, "g-1")).thenReturn(true);
        reply();
        assertSuppressed("Quiet hours active");
    }

    @Test
    void sh14_aNormalReplyIsDelivered() {
        reply();
        verify(notifications).deliverPresenceAware(eq(AUTHOR), any(), any(), any(), any(), eq("comment_on_post"),
                eq("7"), any(), any(), any(), any(), eq("u-c"));
        verify(logRepo, never()).save(any(NotificationLog.class));
    }

    // ---- FD-01 ----

    private GroupReportNotifier notifier(Group g, NotificationService ns) {
        GroupRepo groups = mock(GroupRepo.class);
        when(groups.findByGroupId("g-org")).thenReturn(Optional.of(g));
        UserInfoRepo users = mock(UserInfoRepo.class);
        when(users.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        return new GroupReportNotifier(groups, users, ns, new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
        });
    }

    private static Group org(String type) {
        Group g = new Group();
        g.setGroupId("g-org");
        g.setGroupName("Grace Church");
        g.setGroupType(type);
        g.setOwnerEmail("Owner@x.com");
        g.setAdminEmails(new ArrayList<>(List.of("owner@x.com", "admin@x.com", "admin2@x.com")));
        return g;
    }

    @Test
    void fd01_theOwnerAndAdminsHearOfAReport_neverTheReporterOrTheReportedAuthor() {
        NotificationService ns = mock(NotificationService.class);
        // admin2 reported; the owner wrote the reported post.
        notifier(org("Church"), ns).notifyAdmins("g-org", CommunityReport.TargetType.GROUP_POST,
                "admin2@x.com", "owner@x.com");
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(ns).deliverPresenceAwareForGroup(to.capture(), title.capture(), body.capture(), any(), any(),
                eq("group_report"), eq("g-org"), url.capture(), any(), any(), eq("g-org"), any(), any());
        assertThat(to.getAllValues()).containsExactly("admin@x.com");
        assertThat(title.getValue()).isEqualTo("Report in Grace Church");
        assertThat(body.getValue()).isEqualTo("A member reported a post for review.");
        assertThat(url.getValue()).isEqualTo("/groups/g-org/admin?reports=1");
    }

    @Test
    void fd01_aCommentReportSaysComment_andEveryoneElseOnTheTeamIsTold() {
        NotificationService ns = mock(NotificationService.class);
        notifier(org("Church"), ns).notifyAdmins("g-org", CommunityReport.TargetType.GROUP_COMMENT,
                "member@x.com", "member2@x.com");
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(ns, org.mockito.Mockito.times(3)).deliverPresenceAwareForGroup(to.capture(), any(),
                body.capture(), any(), any(), eq("group_report"), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getAllValues()).containsExactlyInAnyOrder("owner@x.com", "admin@x.com", "admin2@x.com");
        assertThat(body.getValue()).isEqualTo("A member reported a comment for review.");
    }

    @Test
    void fd01_aHouseholdHasNoAdminHome_soNothingIsSent() {
        NotificationService ns = mock(NotificationService.class);
        notifier(org("Household"), ns).notifyAdmins("g-org", CommunityReport.TargetType.GROUP_POST,
                "member@x.com", "member2@x.com");
        verify(ns, never()).deliverPresenceAwareForGroup(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    // ---- PR-01 ----

    private static void addMember(Group g) {
        GroupRepo groups = mock(GroupRepo.class);
        GroupService svc = new GroupService(groups, mock(UserInfoRepo.class), mock(WebSocketMessageSender.class),
                mock(HouseholdEventService.class), mock(NotificationService.class),
                mock(CheckInRequestService.class));
        ReflectionTestUtils.invokeMethod(svc, "assertSeatAvailable", g, "new@x.com");
    }

    private static Group full(String type) {
        Group g = new Group();
        g.setGroupType(type);
        g.setMaxSeats(2);
        g.setMemberEmails(new ArrayList<>(List.of("a@x.com", "b@x.com")));
        return g;
    }

    @Test
    void pr01_aHouseholdIsNeverSeatCapped_evenWithMaxSeatsSet() {
        addMember(full("Household")); // does not throw
    }

    @Test
    void pr01_aPaidOrgSeatCapStillHolds() {
        assertThatThrownBy(() -> addMember(full("Business")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
