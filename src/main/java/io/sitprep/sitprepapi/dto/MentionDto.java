package io.sitprep.sitprepapi.dto;

/**
 * One resolved @-mention, as the FE renders it.
 *
 * <p>The frontend never parses {@code content} for mentions. It receives this
 * list alongside the content and looks each token up by id, which is what keeps
 * the resolution rule in one place instead of two implementations that drift.</p>
 *
 * @param userId      stable id — what the token stores and what
 *                    {@code useProfileNav} routes on
 * @param displayName resolved at read time, so a rename is reflected
 *                    everywhere the mention has ever been written
 * @param deleted     the account no longer exists. The FE renders the name as
 *                    PLAIN TEXT in this case, never a link: a tap target that
 *                    leads to a 404 is worse than no tap target, and a mention
 *                    of someone who left is still a true statement about what
 *                    the comment said.
 */
public record MentionDto(
        String userId,
        String displayName,
        boolean deleted
) {}
