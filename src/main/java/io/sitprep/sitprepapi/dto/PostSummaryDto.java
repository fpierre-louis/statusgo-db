package io.sitprep.sitprepapi.dto;

import lombok.*;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PostSummaryDto {
    private Long id;
    private String groupId;
    private String groupName;

    private String author;                 // email
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

    private String content;
    private Instant timestamp;
}
