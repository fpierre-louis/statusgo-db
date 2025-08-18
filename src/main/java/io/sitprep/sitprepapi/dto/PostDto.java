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
