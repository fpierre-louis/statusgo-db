package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostConfirm;
import io.sitprep.sitprepapi.domain.PostFollow;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MemberAvatar;
import io.sitprep.sitprepapi.dto.ThreadContextDto;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostFollowRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2 (V86): the thread's viewer context — following is real and gated on read
 * access, and the "seen by" faces are real people, viewer first, capped at
 * three, never an email, never someone in a block relationship.
 */
class ThreadContextServiceTest {

    private static final String ME = "me@example.com";

    private PostRepo posts;
    private PostFollowRepo follows;
    private PostConfirmRepo confirms;
    private UserInfoRepo users;
    private BlockService blocks;
    private PostReadAuthorizer auth;
    private ThreadContextService service;
    private Post post;

    @BeforeEach
    void setUp() {
        posts = mock(PostRepo.class);
        follows = mock(PostFollowRepo.class);
        confirms = mock(PostConfirmRepo.class);
        users = mock(UserInfoRepo.class);
        blocks = mock(BlockService.class);
        auth = mock(PostReadAuthorizer.class);
        service = new ThreadContextService(posts, follows, confirms, users, blocks, auth);
        post = new Post();
        post.setId(7L);
        when(posts.findById(7L)).thenReturn(Optional.of(post));
        when(auth.canRead(eq(post), anyString())).thenReturn(true);
        when(confirms.findByPostIdAndUserEmailIgnoreCase(any(), anyString())).thenReturn(Optional.empty());
        when(confirms.findTop10ByPostIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());
        for (String n : new String[]{"me", "ana", "ken", "sam", "leah"}) {
            UserInfo u = new UserInfo();
            u.setId("id-" + n);
            u.setUserFirstName(n);
            u.setUserEmail(n + "@example.com");
            when(users.findByUserEmailIgnoreCase(n + "@example.com")).thenReturn(Optional.of(u));
        }
    }

    private static PostConfirm confirm(String email) {
        PostConfirm c = new PostConfirm();
        c.setPostId(7L);
        c.setUserEmail(email);
        c.setCreatedAt(Instant.now());
        return c;
    }

    private static PostFollow follow(String email) {
        PostFollow f = new PostFollow();
        f.setPostId(7L);
        f.setUserEmail(email);
        return f;
    }

    @Test
    void contextReportsFollowingFromTheTable() {
        when(follows.existsByPostIdAndUserEmailIgnoreCase(7L, ME)).thenReturn(true);
        assertThat(service.context(7L, ME)).get().extracting(ThreadContextDto::viewerFollowing).isEqualTo(true);
        when(follows.existsByPostIdAndUserEmailIgnoreCase(7L, ME)).thenReturn(false);
        assertThat(service.context(7L, ME)).get().extracting(ThreadContextDto::viewerFollowing).isEqualTo(false);
    }

    @Test
    void unreadablePostAnswersEmptyForEveryEntryPoint() {
        when(auth.canRead(eq(post), anyString())).thenReturn(false);
        assertThat(service.context(7L, ME)).isEmpty();
        assertThat(service.follow(7L, ME)).isEmpty();
        assertThat(service.unfollow(7L, ME)).isEmpty();
        verify(follows, never()).saveAndFlush(any());
    }

    @Test
    void followIsIdempotent() {
        when(follows.existsByPostIdAndUserEmailIgnoreCase(7L, ME)).thenReturn(true);
        assertThat(service.follow(7L, ME)).get().extracting(ThreadContextDto.FollowResult::following).isEqualTo(true);
        verify(follows, never()).saveAndFlush(any());
    }

    @Test
    void followWritesOneLowercasedRow() {
        when(follows.existsByPostIdAndUserEmailIgnoreCase(7L, ME)).thenReturn(false);
        service.follow(7L, "  Me@Example.com ");
        verify(follows).saveAndFlush(org.mockito.ArgumentMatchers.argThat(f ->
                f.getPostId() == 7L && f.getUserEmail().equals(ME)));
    }

    @Test
    void unfollowDeletes() {
        assertThat(service.unfollow(7L, ME)).get().extracting(ThreadContextDto.FollowResult::following).isEqualTo(false);
        verify(follows).deleteByPostAndUser(7L, ME);
    }

    @Test
    void facesAreViewerFirstThenRecentCappedAtThreeWithNoEmail() {
        when(confirms.findByPostIdAndUserEmailIgnoreCase(7L, ME)).thenReturn(Optional.of(confirm(ME)));
        when(confirms.findTop10ByPostIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(
                confirm("ana@example.com"), confirm(ME), confirm("ken@example.com"), confirm("sam@example.com")));
        List<MemberAvatar> faces = service.context(7L, ME).orElseThrow().recentConfirmers();
        assertThat(faces).extracting(MemberAvatar::firstName).containsExactly("me", "ana", "ken");
        assertThat(faces).extracting(MemberAvatar::userId).containsExactly("id-me", "id-ana", "id-ken");
        // MemberAvatar carries no email field at all — the privacy rule is structural.
        assertThat(MemberAvatar.class.getRecordComponents()).extracting(c -> c.getName())
                .doesNotContain("email", "userEmail");
    }

    @Test
    void facesSkipBlockRelationships() {
        when(confirms.findTop10ByPostIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(
                confirm("ana@example.com"), confirm("ken@example.com")));
        when(blocks.isAnyBlock(ME, "ana@example.com")).thenReturn(true);
        assertThat(service.context(7L, ME).orElseThrow().recentConfirmers())
                .extracting(MemberAvatar::firstName).containsExactly("ken");
    }

    @Test
    void replyNotifiesFollowersButNotReplierAuthorOrMentioned() {
        when(follows.findByPostId(7L)).thenReturn(List.of(
                follow("ana@example.com"), follow("ken@example.com"), follow("sam@example.com"),
                follow("leah@example.com"), follow("me@example.com")));
        List<String> out = service.followerEmailsToNotify(
                7L, "ken@example.com",       // replier
                "leah@example.com",          // post author (gets comment_on_task)
                Set.of("sam@example.com"));  // @-mentioned (gets the mention push)
        assertThat(out).containsExactlyInAnyOrder("ana@example.com", "me@example.com");
    }

    @Test
    void replyDoesNotNotifyAFollowerInABlockRelationshipWithTheReplier() {
        when(follows.findByPostId(7L)).thenReturn(List.of(follow("ana@example.com")));
        when(blocks.isAnyBlock("ana@example.com", "ken@example.com")).thenReturn(true);
        assertThat(service.followerEmailsToNotify(7L, "ken@example.com", "leah@example.com", Set.of())).isEmpty();
    }
}
