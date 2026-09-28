package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.MentionToken;
import io.sitprep.sitprepapi.domain.PostComment;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CommentPreviewDto;
import io.sitprep.sitprepapi.repo.PostCommentRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feed card's one-line comment preview (ruling B1, 2026-09-27).
 *
 * <p>Runs the real JPQL on H2 through the service, so the query and the
 * author fold are tested together. Two defects this pins:</p>
 * <ul>
 *   <li>the preview picked {@code MAX(id)} with no parent filter, so a nested
 *       reply newer than its conversation starter became the card's preview
 *       and read as an answer to the post;</li>
 *   <li>a commenter with no first name was shown as their email's local part
 *       on a public feed;</li>
 *   <li>the snippet was cut from raw content, so a mention printed as a raw
 *       {@code @[uid:...]} token, or was sliced in half by the 80-char cut.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CommentPreviewTopLevelTest {

    @Autowired PostCommentService commentService;
    @Autowired PostCommentRepo commentRepo;
    @Autowired UserInfoRepo userInfoRepo;

    /** task_id is a plain scalar with no FK in the H2 schema; a random id isolates each test. */
    private static long newPostId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
    }

    private static String newEmail(String localPart) {
        return localPart + "-" + UUID.randomUUID() + "@example.com";
    }

    private UserInfo user(String email, String first, String last) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(first);
        u.setUserLastName(last);
        return userInfoRepo.save(u);
    }

    private PostComment comment(long postId, String author, String content, Long parentId) {
        PostComment c = new PostComment();
        c.setPostId(postId);
        c.setAuthor(author);
        c.setContent(content);
        c.setParentCommentId(parentId);
        return commentRepo.save(c);
    }

    @Test
    void aNestedReplyNewerThanTheTopLevelCommentIsNotThePreview() {
        long postId = newPostId();
        String email = newEmail("ana");
        user(email, "Ana", "Reyes");

        PostComment starter = comment(postId, email, "Road is flooded at 5th", null);
        PostComment reply = comment(postId, email, "Yes, same here", starter.getId());
        assertThat(reply.getId()).isGreaterThan(starter.getId());

        CommentPreviewDto preview = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId);

        assertThat(preview).isNotNull();
        assertThat(preview.commentId()).isEqualTo(starter.getId());
        assertThat(preview.snippet()).isEqualTo("Road is flooded at 5th");
    }

    @Test
    void aNewerTopLevelCommentStillBecomesThePreview() {
        // The filter must narrow to top level, not freeze the preview on the
        // first comment ever written.
        long postId = newPostId();
        String email = newEmail("ben");
        user(email, "Ben", "Ortiz");

        PostComment first = comment(postId, email, "first", null);
        comment(postId, email, "reply to first", first.getId());
        PostComment second = comment(postId, email, "second starter", null);

        CommentPreviewDto preview = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId);

        assertThat(preview.commentId()).isEqualTo(second.getId());
    }

    @Test
    void oneBatchedCallResolvesEachPostIndependently() {
        long postA = newPostId();
        long postB = newPostId();
        String email = newEmail("cam");
        user(email, "Cam", "Lee");

        PostComment aStarter = comment(postA, email, "A starter", null);
        comment(postA, email, "A reply", aStarter.getId());
        PostComment bStarter = comment(postB, email, "B starter", null);

        Map<Long, CommentPreviewDto> out = commentService.loadLatestPreviewsByPostIds(List.of(postA, postB));

        assertThat(out).hasSize(2);
        assertThat(out.get(postA).commentId()).isEqualTo(aStarter.getId());
        assertThat(out.get(postB).commentId()).isEqualTo(bStarter.getId());
    }

    @Test
    void thePreviewCarriesTheAuthorsUserIdAndLastName() {
        long postId = newPostId();
        String email = newEmail("dee");
        UserInfo dee = user(email, "Dee", "Nguyen");

        comment(postId, email, "hello", null);

        CommentPreviewDto preview = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId);

        assertThat(preview.authorUserId()).isEqualTo(dee.getId()).isNotNull();
        assertThat(preview.authorFirstName()).isEqualTo("Dee");
        assertThat(preview.authorLastName()).isEqualTo("Nguyen");
    }

    @Test
    void aProfileWithNoFirstNameYieldsNullNotTheEmailPrefix() {
        long postId = newPostId();
        String email = newEmail("jules.owens");
        user(email, null, null);

        comment(postId, email, "hello", null);

        CommentPreviewDto preview = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId);

        assertThat(preview.authorFirstName()).isNull();
        assertThat(preview.authorLastName()).isNull();
    }

    @Test
    void aBlankFirstNameIsTreatedAsNoName() {
        long postId = newPostId();
        String email = newEmail("blank");
        user(email, "   ", "");

        comment(postId, email, "hello", null);

        CommentPreviewDto preview = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId);

        assertThat(preview.authorFirstName()).isNull();
        assertThat(preview.authorLastName()).isNull();
    }

    @Test
    void anAuthorWithNoProfileAtAllYieldsNullNames() {
        // Deleted account: the comment row outlives the user_info row. This was
        // the exact branch that sliced the email.
        long postId = newPostId();
        String email = newEmail("gone");

        comment(postId, email, "hello", null);

        CommentPreviewDto preview = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId);

        assertThat(preview.authorFirstName()).isNull();
        assertThat(preview.authorLastName()).isNull();
        assertThat(preview.authorUserId()).isNull();
    }

    @Test
    void aMentionBecomesThePlainNameEvenWhenTheRawTokenCrossesTheCut() {
        long postId = newPostId();
        String authorEmail = newEmail("eve");
        user(authorEmail, "Eve", "Park");
        UserInfo ana = user(newEmail("ana"), "Ana", "Reyes");

        String lead = "Water is over the curb on Maple and the storm drain is blocked, cc ";
        String token = MentionToken.of(ana.getId());
        // Precondition: the RAW token straddles the 80-char cut, so truncating
        // before resolving would print half a token. Resolved, "@Ana Reyes" fits.
        assertThat(lead.length()).isLessThan(80);
        assertThat(lead.length() + token.length()).isGreaterThan(80);

        comment(postId, authorEmail, lead + token + " can you check it before tonight?", null);

        String snippet = commentService.loadLatestPreviewsByPostIds(List.of(postId)).get(postId).snippet();

        assertThat(snippet)
                .doesNotContain("@[uid:")
                .contains("@Ana Reyes")
                .endsWith("…");
    }
}
