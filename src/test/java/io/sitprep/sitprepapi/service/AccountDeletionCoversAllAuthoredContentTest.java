package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account deletion must erase what the delete-account screen promises.
 *
 * <p>That screen enumerates "Posts, comments, and reactions you've made". There
 * are SIX comment/reaction tables across two parallel families — GroupPost* is
 * group chat, Post* is the community feed — and the service swept only the two
 * group-chat ones. A deleted user's community-feed comments and reactions
 * survived, carrying their email in {@code PostComment.author} and
 * {@code PostReaction.userEmail}.</p>
 *
 * <p>This is a source guard rather than a behavioural test on purpose: the
 * failure mode is an ABSENT line, and the way it comes back is somebody adding a
 * seventh table and sweeping five. A missing delete cannot be observed by
 * asserting on the rows that were deleted.</p>
 */
class AccountDeletionCoversAllAuthoredContentTest {

    private String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/sitprep/sitprepapi/service/AccountDeletionService.java"));
    }

    @Test
    void sweepsBothCommentFamilies() throws Exception {
        String src = source();
        assertTrue(src.contains("DELETE FROM GroupPostComment c WHERE LOWER(c.author) = :e"),
                "group chat comments must be deleted");
        assertTrue(src.contains("DELETE FROM PostComment c WHERE LOWER(c.author) = :e"),
                "COMMUNITY FEED comments must be deleted — PostComment.author holds the user's email");
    }

    @Test
    void sweepsAllFourReactionTables() throws Exception {
        String src = source();
        for (String entity : new String[]{
                "GroupPostReaction", "GroupPostCommentReaction",
                "PostReaction", "PostCommentReaction"}) {
            assertTrue(
                    src.contains("DELETE FROM " + entity + " r WHERE LOWER(r.userEmail) = :e"),
                    entity + " must be deleted with the account — it stores the user's email");
        }
    }

    @Test
    void stillDeletesTheAccountItselfAndItsAuthEntry() throws Exception {
        String src = source();
        // Guard the two that make the deletion real, so a refactor of the
        // cascade above can't quietly drop them.
        assertTrue(src.contains("userInfoRepo::delete"), "the UserInfo row must be deleted");
        assertTrue(src.contains("FirebaseAuth.getInstance().deleteUser"),
                "the Firebase Auth identity must be deleted");
    }
}
