package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class PostDto {
    private Long id;
    private String tempId;
    private String author;
    private String content;
    private String groupId;
    private String groupName;

    /** createdAt */
    private Instant timestamp;

    /**
     * R2 object key. Set on write to attach an image uploaded via
     * {@code POST /api/images}. Set on read when the post has an
     * {@code imageKey} stored — clients should prefer {@link #imageUrl}.
     */
    private String imageKey;

    /** Public delivery URL derived from {@link #imageKey} via PublicCdn. Read-only. */
    private String imageUrl;

    /**
     * Legacy inline base64-encoded image. Populated on read when a post
     * has only the old bytea column (pre-R2). Writers should prefer
     * {@code imageKey}; this path is kept for back-compat until all
     * pre-R2 rows are migrated.
     */
    private String base64Image;
    private Map<String, Integer> reactions;

    /** user-initiated edit moment (you already had this) */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    private Instant updatedAt;

    private List<String> tags;
    private List<String> mentions;
    private int commentsCount;

    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;
}
