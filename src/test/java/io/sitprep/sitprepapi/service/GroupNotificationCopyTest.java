package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupPostCommentRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-15: the words and the routes group notifications carry.
 *
 * <ul>
 *   <li>The check-in push named the OWNER ("🚨 Important: {owner} here!") even
 *       when another admin started it.</li>
 *   <li>The decay prompt said "48 hours" whatever the config said, and asked
 *       an org admin about "family".</li>
 *   <li>A comment on a household post opened the org route, which drops the
 *       query and lands on Family instead of the thread.</li>
 * </ul>
 */
class GroupNotificationCopyTest {

    private static Group group(String type) {
        Group g = new Group();
        g.setGroupId("g-1");
        g.setGroupName("Grace");
        g.setGroupType(type);
        g.setOwnerName("Olivia Owner");
        return g;
    }

    @Test
    void theCheckInPushNamesWhoStartedIt() {
        UserInfoRepo users = mock(UserInfoRepo.class);
        UserInfo admin = new UserInfo();
        admin.setUserFirstName("Ana");
        admin.setUserLastName("Admin");
        when(users.findByUserEmailIgnoreCase("ana@x.com")).thenReturn(Optional.of(admin));
        when(users.findByUserEmailIgnoreCase("ghost@x.com")).thenReturn(Optional.empty());
        NotificationService notifications = new NotificationService(mock(WebSocketMessageSender.class), users,
                mock(NotificationLogRepo.class), mock(WebSocketPresenceService.class),
                mock(PushPolicyService.class), mock(GroupMuteService.class));

        Group g = group("Church");
        assertThat(notifications.initiatorName("ana@x.com", g)).isEqualTo("Ana Admin");
        // Unknown initiator: the owner's name, then a neutral fallback.
        assertThat(notifications.initiatorName("ghost@x.com", g)).isEqualTo("Olivia Owner");
        g.setOwnerName(null);
        assertThat(notifications.initiatorName(null, g)).isEqualTo("Your group leader");
    }

    @Test
    void theDecayPromptReadsTheConfiguredWindowAndTheGroupKind() {
        assertThat(GroupAlertDecayService.continuePromptBody(group("Household"), 72))
                .contains("after 72 hours").contains("your family").doesNotContain("48");
        assertThat(GroupAlertDecayService.continuePromptBody(group("School Staff/Classroom"), 48))
                .contains("after 48 hours").contains("your group").doesNotContain("family");
    }

    @Test
    void commentPushesOpenThePostsOwnThread() {
        GroupPostService posts = mock(GroupPostService.class);
        when(posts.isHouseholdGroup("hh-1")).thenReturn(true);
        when(posts.isHouseholdGroup("g-1")).thenReturn(false);
        GroupPostCommentService comments = new GroupPostCommentService(mock(GroupPostCommentRepo.class),
                mock(GroupPostRepo.class), mock(UserInfoRepo.class), mock(WebSocketMessageSender.class),
                mock(NotificationService.class), mock(GroupPostCommentReactionService.class), posts,
                mock(MentionService.class), mock(BlockService.class), mock(GroupContentRemovals.class));

        GroupPost household = new GroupPost();
        household.setId(7L);
        household.setGroupId("hh-1");
        assertThat(comments.postRoute(household))
                .isEqualTo("/household/h/4D-FwtX/household/hh-1/chat?postId=7");

        GroupPost org = new GroupPost();
        org.setId(8L);
        org.setGroupId("g-1");
        assertThat(comments.postRoute(org)).isEqualTo("/groups/g-1?view=post&postId=8");
    }
}
