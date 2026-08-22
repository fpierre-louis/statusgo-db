package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class GroupPostCommentDto {
    private Long id;
    private String tempId;                       // optimistic correlation
    private Long postId;
    /**
     * The comment this one replies to. Null on a top-level comment and on every
     * pre-V59 row, which still carries the legacy "> Replying to …" prefix in
     * {@link #content} instead.
     *
     * <p>Depth is capped at one: a reply to a reply is stored against the ROOT,
     * so a client can render exactly two levels and never has to recurse.</p>
     */
    private Long parentCommentId;

    // Author
    private String author;                       // email
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageUrl;

    // Content
    private String content;

    /** createdAt */
    private Instant timestamp;

    /** user-initiated edit moment (explicit) */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    private Instant updatedAt;

    private boolean edited;                      // convenience flag

    /**
     * Per-emoji reaction roster for this comment, populated server-side
     * by {@code GroupPostCommentService} via batched
     * {@code GroupPostCommentReactionService.loadByGroupPostCommentIds}
     * so a thread page is one extra DB round trip total, not N. Shape:
     * <pre>
     *   { "❤️": [{ userEmail, addedAt }, ...], "👍": [...], ... }
     * </pre>
     * Empty map when the comment has no reactions.
     */
    private Map<String, List<EmojiReactionDto>> reactions;

    /**
     * Heart "Thank" reaction count on this comment. Folded in by the
     * thread listing path via a single batched query.
     */
    private int thanksCount;

    /**
     * True when the requesting viewer has reacted with the heart "Thank"
     * emoji on this comment. Drives the filled-vs-outline heart icon.
     */
    private boolean viewerThanked;
}
