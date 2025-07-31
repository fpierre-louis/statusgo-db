package io.sitprep.sitprepapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data // ✅ THIS IS KEY for getters and setters
@NoArgsConstructor
@AllArgsConstructor
public class CommentDto {
    private Long id;
    private Long postId;
    private String author;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;
    private String content;
    private Instant timestamp;
    private boolean edited;
}