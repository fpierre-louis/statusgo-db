package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * One reactor's record under a post's reactions roster.
 * <pre>
 *   reactions: { "❤️": [{ userEmail, addedAt }, ...], ... }
 * </pre>
 */
public record PostReactionDto(String userEmail, Instant addedAt) {}
