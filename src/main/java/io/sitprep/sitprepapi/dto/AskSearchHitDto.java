package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * INTERNAL join key. Never serialised — see {@code @JsonIgnore}.
     *
     * <p>It used to ship, to anonymous callers, and nothing rendered it:
     * AskPage's hit row draws kind, title, snippet, votes and a hazard chip and
     * never touches an author field. But it cannot simply be deleted — the
     * enrichment pass in {@code AskService.enrichAuthors} uses it to batch-load
     * each author's {@code UserInfo}, so the DTO carries it between two
     * server-side steps.</p>
     *
     * <p>That is the distinction worth keeping straight: the field has an
     * internal consumer and no external one. {@code @JsonIgnore} is what
     * separates those, and removing the annotation re-opens the leak silently —
     * {@code AskDtoPrivacyTest} fails if it does.</p>
     */
    @JsonIgnore
    private String authorEmail;

    /** The author's user id — this is the identity that ships. */
    private String authorUserId;

    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageUrl;

    /** Best-fit deep link for the FE to navigate on tap. */
    private String href;
}
