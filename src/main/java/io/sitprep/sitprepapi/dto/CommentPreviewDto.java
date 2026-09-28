package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Compact "latest comment" snippet folded onto each {@link PostDto} so
 * the FE feed card can show a one-line preview ("Jules Owens · 3h ·
 * Bringing my two boys…") without fetching the full comment thread per
 * card. Mirrors the IG / FB feed pattern where the most recent comment
 * is teased below the post. Only top-level comments are previewed — see
 * {@code PostCommentRepo.findLatestTopLevelByPostIdIn}.
 *
 * <p>{@code snippet} is the comment body trimmed to ~80 chars with an
 * ellipsis marker if truncated.</p>
 *
 * <p>Author fields mirror {@link PostCommentDto}: {@code authorUserId} is
 * the same stable id (tap-to-profile), and the name fields are the
 * profile's own values. <b>{@code authorFirstName} / {@code authorLastName}
 * are null when the profile has none</b> (unset name, deleted account) —
 * never derived from the email. The FE renders its own placeholder
 * ("Neighbor") for null.</p>
 */
public record CommentPreviewDto(
        Long commentId,
        String authorEmail,
        String authorUserId,
        String authorFirstName,
        String authorLastName,
        String authorProfileImageUrl,
        String snippet,
        Instant timestamp
) {}
