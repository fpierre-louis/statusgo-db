package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shape for a comment on a {@link io.sitprep.sitprepapi.domain.Post}.
 * Mirrors {@link GroupPostCommentDto} (post-comments) field-for-field except for the
 * foreign key ({@code postId} → {@code postId}) so the FE comment renderer
 * (forked from {@code PostComments.js}) can be a near-exact copy and the
 * eventual entity unification collapses both DTOs into one.
 *
 * <p>Author profile fields ({@code authorFirstName}, {@code authorLastName},
 * {@code authorProfileImageUrl}) are populated server-side per the codebase
 * principle "backend shapes the data, frontend just displays" — saves a
 * separate {@code POST /userinfo/profiles/batch} round trip per page-load.</p>
 */
@Data
public class PostCommentDto {
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

    // Content. Replies use the quote-prefix convention from PostComments:
    //   "> Replying to {name}:\n> {snippet}\n\n{actual reply text}"
    // No threading column — keeps the schema clean and lets the FE render
    // both flat comments + replies via the same parser.
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
     * by {@code PostCommentService} via batched
     * {@code PostCommentReactionService.loadByPostCommentIds} so a thread
     * page is one extra DB round trip total, not N. Shape:
     * <pre>
     *   { "❤️": [{ userEmail, addedAt }, ...], "👍": [...], ... }
     * </pre>
     * Empty map when the comment has no reactions.
     */
    private Map<String, List<EmojiReactionDto>> reactions;

    /**
     * Heart "Thank" reaction count on this comment. Folded in by the
     * thread listing path via a single batched query (see
     * {@code PostCommentReactionService.loadThankSummary}). Lets the FE
     * render a heart count next to each bubble without a per-comment
     * reactions roundtrip.
     */
    private int thanksCount;

    /**
     * True when the requesting viewer has reacted with the heart "Thank"
     * emoji on this comment. Drives the filled-vs-outline heart icon on
     * the comment bubble. Always false for unauthenticated reads.
     */
    private boolean viewerThanked;

    /**
     * Resolved @-mentions in {@link #content}, in the order they appear.
     *
     * <p>The FE does NOT parse content for mentions. It renders the tokens by
     * looking each id up in this list, which is what keeps the resolution rule
     * server-side and singular. An entry with {@code deleted=true} is rendered
     * as plain text rather than a link.</p>
     */
    private List<MentionDto> mentions;
}
