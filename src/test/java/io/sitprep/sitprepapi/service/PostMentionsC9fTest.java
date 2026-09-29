package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.MentionToken;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MentionDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import io.sitprep.sitprepapi.dto.PostCommentDto;
import java.util.UUID;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Composer V2 C9f — @-mentions in community posts (owner Q6).
 */
class PostMentionsC9fTest {

    /** Through the REAL create path (H2): tokens → stored ids → resolved names. */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @Transactional
    class CreatePath {
        @Autowired PostService postService;
        @Autowired PostRepo postRepo;
        @Autowired UserInfoRepo userInfoRepo;
        @MockBean NominatimGeocodeService geocode;

        private UserInfo user(String first, String email) {
            UserInfo u = new UserInfo();
            u.setUserFirstName(first);
            u.setUserLastName("Tester");
            u.setUserEmail(email);
            return userInfoRepo.save(u);
        }

        @Test
        void mentionsAreParsedFromTheBodyStoredAndResolvedOnTheDto() {
            when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
            UserInfo ana = user("Ana", "ana@example.com");
            UserInfo ben = user("Ben", "ben@example.com");
            Post p = new Post();
            p.setKind("post");
            p.setDescription("Thanks " + MentionToken.of(ana.getId()) + " and " + MentionToken.of(ben.getId()) + "!");

            PostDto dto = postService.create(p, "author@example.com");

            assertThat(postRepo.findById(dto.id()).orElseThrow().getMentionedUserIds())
                    .containsExactly(ana.getId().toLowerCase(), ben.getId().toLowerCase());
            assertThat(dto.mentions()).extracting(MentionDto::displayName)
                    .containsExactly("Ana Tester", "Ben Tester");
        }

        @Test
        void anUnknownIdResolvesAsAFormerMember() {
            when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
            Post p = new Post();
            p.setKind("post");
            p.setDescription("Hi " + MentionToken.of("00000000-0000-4000-8000-000000000001"));
            PostDto dto = postService.create(p, "author@example.com");
            assertThat(dto.mentions()).singleElement()
                    .satisfies(m -> {
                        assertThat(m.deleted()).isTrue();
                        assertThat(m.displayName()).isEqualTo(MentionToken.TOMBSTONE_NAME);
                    });
        }

        @Test
        void aBodyLongerThanTheColumnIsA400() {
            Post p = new Post();
            p.setKind("post");
            p.setDescription("x".repeat(PostService.MAX_DESCRIPTION_CHARS + 1));
            assertThrows(IllegalArgumentException.class, () -> postService.create(p, "author@example.com"));
        }
    }

    /** The notify guard, with every collaborator mocked. */
    @Nested
    class Notify {
        private final MentionService mentionService = mock(MentionService.class);
        private final NotificationService notifications = mock(NotificationService.class);
        private final UserInfoRepo users = mock(UserInfoRepo.class);
        private final BlockService blocks = mock(BlockService.class);
        private final PostReadAuthorizer reads = mock(PostReadAuthorizer.class);
        private final PostMentionService service =
                new PostMentionService(mentionService, notifications, users, blocks, reads);

        private UserInfo u(String email) {
            UserInfo u = new UserInfo();
            u.setUserEmail(email);
            u.setUserFirstName(email.substring(0, email.indexOf('@')));
            return u;
        }

        @Test
        void onlyAReaderWhoIsNeitherTheActorNorBlockedIsNotified() {
            Post post = new Post();
            post.setId(42L);
            post.setDescription("hey");
            List<String> ids = List.of("a", "b", "c", "d");
            when(mentionService.emailsFor(ids)).thenReturn(List.of(
                    "actor@x.com", "blocked@x.com", "stranger@x.com", "friend@x.com"));
            when(mentionService.toPlainText(any())).thenReturn("hey");
            for (String e : List.of("actor@x.com", "blocked@x.com", "stranger@x.com", "friend@x.com")) {
                when(users.findByUserEmailIgnoreCase(e)).thenReturn(Optional.of(u(e)));
            }
            when(blocks.isAnyBlock("actor@x.com", "blocked@x.com")).thenReturn(true);
            when(reads.canRead(eq(post), anyString())).thenReturn(true);
            when(reads.canRead(post, "stranger@x.com")).thenReturn(false);

            service.notifyPostMentioned(post, "actor@x.com", ids);

            verify(notifications, times(1)).deliverPresenceAware(
                    eq("friend@x.com"), anyString(), contains("mentioned you in a post"), anyString(),
                    nullable(String.class), eq(NotificationService.TYPE_POST_MENTION), eq("42"),
                    eq("/community/posts/42"), nullable(String.class), nullable(String.class),
                    nullable(String.class));
            verifyNoMoreInteractions(notifications);
        }
    }

    /**
     * Notices ride AFTER COMMIT, so these run WITHOUT a test transaction (each
     * service call commits). Unique emails per run keep them independent of
     * whatever else the shared H2 holds.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class AfterCommit {
        @Autowired PostService postService;
        @Autowired PostCommentService commentService;
        @Autowired UserInfoRepo userInfoRepo;
        @Autowired BlockService blockService;
        @MockBean NominatimGeocodeService geocode;
        @MockBean NotificationService notifications;
        @SpyBean PostMentionService mentions;

        private UserInfo user(String first) {
            UserInfo u = new UserInfo();
            u.setUserFirstName(first);
            u.setUserLastName("Tester");
            u.setUserEmail(first.toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
            return userInfoRepo.save(u);
        }

        @Test
        void anEditNotifiesOnlyTheMentionItAdded() {
            when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
            UserInfo author = user("Author");
            UserInfo ana = user("Ana");
            UserInfo ben = user("Ben");
            Post p = new Post();
            p.setKind("post");
            p.setDescription("Hi " + MentionToken.of(ana.getId()));
            PostDto dto = postService.create(p, author.getUserEmail());
            verify(mentions).notifyPostMentioned(any(), eq(author.getUserEmail()), eq(List.of(ana.getId().toLowerCase())));

            Post patch = new Post();
            patch.setDescription("Hi " + MentionToken.of(ana.getId()) + " and " + MentionToken.of(ben.getId()));
            postService.patch(dto.id(), patch, author.getUserEmail());
            verify(mentions).notifyPostMentioned(any(), eq(author.getUserEmail()), eq(List.of(ben.getId().toLowerCase())));
        }

        @Test
        void aCommentMentionNeverReachesSomeoneWhoBlockedTheAuthor() {
            when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
            UserInfo author = user("Author");
            UserInfo blocker = user("Blocker");
            Post p = new Post();
            p.setKind("post");
            p.setDescription("Open question");
            PostDto post = postService.create(p, author.getUserEmail());
            blockService.block(blocker.getUserEmail(), author.getUserEmail());

            PostCommentDto c = new PostCommentDto();
            c.setPostId(post.id());
            c.setAuthor(author.getUserEmail());
            c.setContent("cc " + MentionToken.of(blocker.getId()));
            commentService.createCommentFromDto(c);

            verify(notifications, never()).deliverPresenceAware(
                    eq(blocker.getUserEmail()), anyString(), anyString(), anyString(), nullable(String.class),
                    eq(NotificationService.TYPE_POST_MENTION), anyString(), anyString(),
                    nullable(String.class), nullable(String.class), nullable(String.class));
        }
    }
}
