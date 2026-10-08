package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.JsonNode;
import io.sitprep.sitprepapi.readiness.ReadinessAction;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Practice content model — one JSON file per immutable version under
 * {@code src/main/resources/practice/content/{key}/v{version}.json}.
 *
 * <p>The file has two halves with different rules:</p>
 * <ul>
 *   <li>{@code content} plus {@code key}/{@code version}/{@code kind} — what a
 *       person sees and the graph they walk. Hashed ({@link ContentHasher}) and
 *       <b>immutable once reviewed</b>: a change means a new version file.</li>
 *   <li>{@code lifecycle} — publish state, the safety review, dates. Not
 *       hashed, so publishing or retiring a version never changes the identity
 *       a {@code scenario_run} pinned when it started.</li>
 * </ul>
 */
public final class PracticeContent {

    private PracticeContent() {}

    /** Current file schema. Bump when the shape below changes incompatibly. */
    public static final int SCHEMA = 1;

    /** One content file exactly as authored. */
    public record ContentFile(
            Integer schema,
            String key,
            Integer version,
            PracticeKind kind,
            Lifecycle lifecycle,
            Body content) {}

    public record Lifecycle(
            PublishState publishState,
            SafetyReview safetyReview,
            LocalDate publishedAt,
            LocalDate retiredAt) {}

    /**
     * The human safety review. {@code reviewedContentHash} is the hash the
     * reviewer actually read — the validator requires it to equal the file's
     * computed hash for anything past DRAFT, so an edit after sign-off is
     * caught rather than shipped.
     */
    public record SafetyReview(
            SafetyReviewStatus status,
            LocalDate reviewedAt,
            String reviewedBy,
            String reviewedContentHash,
            String notes) {}

    /** Everything a person sees. Kind-specific bodies hang off {@code scenario} / {@code activity}. */
    public record Body(
            String title,
            String summary,
            String objective,
            Integer estimatedMinutes,
            List<Source> sources,
            /** FAMILY_ACTIVITY only: activity metadata like "Great for ages 5-8" — never household data. */
            List<String> ageGuidance,
            /** FAMILY_ACTIVITY only: what the adult reads aloud / does first. */
            String facilitation,
            /** Debrief copy per {@link DebriefTag} wire name. */
            Map<String, TagCopy> tags,
            Scenario scenario,
            /** FAMILY_ACTIVITY body; its schema lands with Phase D. */
            JsonNode activity) {}

    public record Source(String label, String url) {}

    /**
     * Debrief copy for one tag. {@code action} is the next family step a
     * "Worth practicing" tag points at (a {@link PracticeActions#ALLOWED} editor).
     */
    public record TagCopy(String text, ReadinessAction action) {}

    /** A choose-your-path graph: nodes ask, choices move, outcomes end. Acyclic. */
    public record Scenario(
            String startNode,
            List<Node> nodes,
            List<Outcome> outcomes) {}

    public record Node(
            String key,
            String title,
            String body,
            String prompt,
            List<Choice> choices) {}

    /**
     * One option. {@code next} names a node or an outcome; {@code feedback}
     * is the calm one-line reflection shown after choosing; {@code tags} are
     * the debrief tags this choice earns.
     */
    public record Choice(
            String key,
            String label,
            String feedback,
            List<String> tags,
            String next) {}

    /** A terminal state. {@code defaultAction} is the next step when no "Worth practicing" tag names one. */
    public record Outcome(
            String key,
            String title,
            String body,
            ReadinessAction defaultAction) {}

    /**
     * A loaded, hashed version. {@code resourcePath} is where it came from,
     * for error messages and the operator status list.
     */
    public record Version(ContentFile file, String contentHash, String resourcePath) {
        public String key() { return file.key(); }
        public int version() { return file.version(); }
        public PracticeKind kind() { return file.kind(); }
        public PublishState publishState() { return file.lifecycle().publishState(); }
        public Body body() { return file.content(); }
    }
}
