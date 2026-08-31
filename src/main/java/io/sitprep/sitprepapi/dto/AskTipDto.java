package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
public class AskTipDto {
    private Long id;

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
    private String coverImageKey;
    private List<String> imageKeys;

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

    private Instant createdAt;
    private Instant updatedAt;
    private Instant editedAt;

    private Integer viewerVote;
    private Boolean viewerBookmarked;
    private boolean viewerIsAuthor;

    private boolean hazardMatched;
}
