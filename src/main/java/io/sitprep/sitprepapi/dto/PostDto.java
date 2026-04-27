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
     * R2 object key. Clients set this on write after uploading via
     * {@code POST /api/images}. Stored on the entity; read clients
     * should prefer {@link #imageUrl}.
     */
    private String imageKey;

    /** Public delivery URL derived from {@link #imageKey} via PublicCdn. Read-only. */
    private String imageUrl;

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
