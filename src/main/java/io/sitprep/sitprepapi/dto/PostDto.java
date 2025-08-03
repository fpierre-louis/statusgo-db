package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class PostDto {
    private Long id;
    private Long tempId; // <-- Add this field
    private String author;
    private String content;
    private String groupId;
    private String groupName;
    private Instant timestamp;
    private String base64Image;
    private Map<String, Integer> reactions;
    private Instant editedAt;
    private List<String> tags;
    private List<String> mentions;
    private int commentsCount;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;
}