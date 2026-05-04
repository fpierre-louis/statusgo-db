package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Wire shape for an {@link io.sitprep.sitprepapi.domain.AskQuestion}. Includes
 * server-folded author profile fields and viewer-relative state
 * ({@code viewerVote}, {@code viewerBookmarked}) so the FE doesn't need to
 * stitch from multiple endpoints — backend shapes the data per the codebase
 * principle.
 *
 * <p>{@code hotScore} is computed in the service layer:
 * {@code log10(max(voteScore + 1, 1)) + max(0, 14 - daysOld) * 0.15}
 * with hazard-matched items pinned in the tier above non-matches. The FE
 * receives rows in already-ranked order; it does NOT re-rank client side.</p>
 */
@Data
public class AskQuestionDto {
    private Long id;

    // Author (folded server-side)
    private String authorEmail;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

    private String title;
    private String body;
    private Set<String> tags;
    private Set<String> hazardTags;

    private Double latitude;
    private Double longitude;
    private String zipBucket;
    private String placeLabel;

    private int voteScore;
    private long viewCount;
    private int answerCount;

    private Long acceptedAnswerId;
    private boolean hasAcceptedAnswer;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant editedAt;

    // Viewer-relative state. Null when the request is anonymous.
    /** -1 / 0 / +1 — the viewer's current vote on this question, or 0 if none. */
    private Integer viewerVote;
    private Boolean viewerBookmarked;
    /** True when the viewer is the original asker (enables Edit + Accept buttons). */
    private boolean viewerIsAuthor;

    /** True when one of {@link #hazardTags} matches an active alert in the viewer's area. */
    private boolean hazardMatched;

    /** Inline answers — populated by the detail endpoint, omitted on list pages. */
    private List<AskAnswerDto> answers;
}
