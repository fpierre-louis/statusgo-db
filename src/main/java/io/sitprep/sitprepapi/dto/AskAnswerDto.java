package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AskAnswerDto {
    private Long id;
    private Long questionId;

    /**
     * The author's user id — NOT their email.
     *
     * <p>Ask reads are anonymous by product decision, and every one of these
     * DTOs used to ship {@code authorEmail}. Verified against prod v553 with no
     * token: {@code GET /api/ask/questions} returned a real personal address to
     * an unauthenticated caller. The convention already existed and this family
     * was the exception — CLAUDE.md: "Lightweight identity DTOs ship userId
     * (not email — privacy). The route accepts either, so the FE just passes
     * whichever it has."</p>
     */
    private String authorUserId;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageUrl;

    private String body;
    private int voteScore;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant editedAt;

    private boolean accepted;
    private Integer viewerVote;
    private boolean viewerIsAuthor;
}
