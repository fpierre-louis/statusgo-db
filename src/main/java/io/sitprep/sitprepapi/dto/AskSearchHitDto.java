package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Set;

/**
 * Unified search-hit row across guides / questions / tips. The Ask search
 * endpoint returns mixed types in a single ranked list; the FE decides
 * how to render each type from {@link #kind}.
 */
@Data
public class AskSearchHitDto {
    /** "guide" | "question" | "tip". */
    private String kind;

    /** Stable identifier — numeric for question/tip (stringified), slug for guide. */
    private String key;

    private String title;
    /** Short snippet for the hit card — first ~200 chars of body, or guide summary. */
    private String snippet;

    private Set<String> tags;
    private Set<String> hazardTags;

    private int voteScore;
    private Instant createdAt;

    private boolean hazardMatched;
    private double hotScore;

    /** "guide" hits don't have an author; question/tip hits do. */
    private String authorEmail;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

    /** Best-fit deep link for the FE to navigate on tap. */
    private String href;
}
