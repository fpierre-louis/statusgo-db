package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class GroupPostCommentDto {
    private Long id;
    private String tempId;                       // optimistic correlation
    private Long postId;

    // Author
    private String author;                       // email
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

    // Content
    private String content;

    /** createdAt */
    private Instant timestamp;

    /** user-initiated edit moment (explicit) */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    private Instant updatedAt;

    private boolean edited;                      // convenience flag
}