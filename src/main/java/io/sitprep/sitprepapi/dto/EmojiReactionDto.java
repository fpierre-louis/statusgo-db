package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * One reactor's record under a reactions roster. Generic shape — used
 * for both {@link io.sitprep.sitprepapi.domain.GroupPostReaction}
 * (chat) and {@link io.sitprep.sitprepapi.domain.PostReaction}
 * (community feed) so a single FE renderer covers both surfaces.
 *
 * <pre>
 *   reactions: { "❤️": [{ userEmail, addedAt }, ...], ... }
 * </pre>
 *
 * <p>Renamed from {@code PostReactionDto} 2026-05-04 to make the
 * cross-surface semantic explicit — the old name read ambiguously
 * once the {@code Post} entity rename landed.</p>
 */
public record EmojiReactionDto(String userEmail, Instant addedAt) {}
