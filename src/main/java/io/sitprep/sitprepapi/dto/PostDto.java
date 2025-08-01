package io.sitprep.sitprepapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data // ✅ THIS IS KEY for getters and setters
@NoArgsConstructor
@AllArgsConstructor
public class PostDto {
    private Long id;
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
    private Integer commentsCount;

    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

}