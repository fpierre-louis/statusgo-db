package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    private String title;
    private String body;
    private Set<String> tags;
    private Set<String> hazardTags;

    /**
     * WRITE-ONLY. The client sends its own coordinates when composing; the
     * server never sends them back.
     *
     * <p>They used to round-trip, so {@code GET /api/ask/questions} handed an
     * anonymous caller a 7-decimal pair — roughly centimetre precision — for
     * wherever the author was standing when they posted. On a preparedness app
     * that is usually home. Verified against prod v553 with no token.</p>
     *
     * <p>Deleting the field outright does not work: this DTO is both the
     * request and the response body, and {@code createQuestion} reads
     * {@code in.getLatitude()}. {@code Access.WRITE_ONLY} is exactly the
     * asymmetry that is wanted — bound on the way in, absent on the way out —
     * and it keeps the compose path working untouched.</p>
     *
     * <p>Coarse location still ships for display as {@code placeLabel} and
     * {@code zipBucket}, and the entity keeps the precise pair for server-side
     * local ranking.</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Double latitude;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private Double longitude;
    private String zipBucket;
    private String placeLabel;

    private int voteScore;
    private long viewCount;
    private int answerCount;

    /** Photo attachments, in order. Same shape as AskTipDto.imageKeys. */
    private List<String> imageKeys;

    private Long acceptedAnswerId;
    private boolean hasAcceptedAnswer;
    /**
     * First line of the accepted answer. Read-only: server-derived, and
     * ignored if a client sends it back on create/edit.
     */
    private String acceptedAnswerExcerpt;

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
