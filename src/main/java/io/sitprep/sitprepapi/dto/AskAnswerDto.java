package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AskAnswerDto {
    private Long id;
    private Long questionId;

    private String authorEmail;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

    private String body;
    private int voteScore;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant editedAt;

    private boolean accepted;
    private Integer viewerVote;
    private boolean viewerIsAuthor;
}
