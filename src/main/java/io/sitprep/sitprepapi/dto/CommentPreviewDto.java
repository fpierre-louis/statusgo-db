package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Compact "latest comment" snippet folded onto each {@link PostDto} so
 * the FE feed card can show a one-line preview ("Jules Owens · 3h ·
 * Bringing my two boys…") without fetching the full comment thread per
 * card. Mirrors the IG / FB feed pattern where the most recent comment
 * is teased below the post.
 *
 * <p>{@code snippet} is the comment body trimmed to ~80 chars with an
 * ellipsis marker if truncated. {@code authorFirstName} falls back to
 * the email-prefix when the author profile can't be resolved (deleted
 * account etc.) so the FE always has something to render.</p>
 */
public record CommentPreviewDto(
        Long commentId,
        String authorEmail,
        String authorFirstName,
        String authorProfileImageUrl,
        String snippet,
        Instant timestamp
) {}
