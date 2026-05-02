package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Wire shape for a comment on a {@link io.sitprep.sitprepapi.domain.Post}.
 * Mirrors {@link GroupPostCommentDto} (post-comments) field-for-field except for the
 * foreign key ({@code postId} → {@code postId}) so the FE comment renderer
 * (forked from {@code PostComments.js}) can be a near-exact copy and the
 * eventual entity unification collapses both DTOs into one.
 *
 * <p>Author profile fields ({@code authorFirstName}, {@code authorLastName},
 * {@code authorProfileImageURL}) are populated server-side per the codebase
 * principle "backend shapes the data, frontend just displays" — saves a
 * separate {@code POST /userinfo/profiles/batch} round trip per page-load.</p>
 */
@Data
public class PostCommentDto {
    private Long id;
    private String tempId;                       // optimistic correlation
    private Long postId;

    // Author
    private String author;                       // email
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

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
}
