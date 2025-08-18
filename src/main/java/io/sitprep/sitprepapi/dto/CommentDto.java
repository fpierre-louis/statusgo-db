package io.sitprep.sitprepapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentDto {
    private Long id;
    private String tempId; // client-side correlation
    private Long postId;
    private String author;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;
    private String content;

    /** createdAt */
    private Instant timestamp;

    /** last modified */
    private Instant updatedAt;

    private boolean edited;
}
