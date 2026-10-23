package io.sitprep.sitprepapi.practice;

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
    /**
     * Current authoring schema. Schema 2 adds hashed media (EXEC-M); schema 1
     * stays readable so every reviewed version keeps its hash.
     */
    public static final int SCHEMA = 2;
    public static final int MIN_SCHEMA = 1;

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
            /** One visual language per version, required when any media is present (schema 2). */
            MediaStyle mediaStyle,
            Scenario scenario,
            /** FAMILY_ACTIVITY body; local choices are not persisted. */
            Activity activity,
            /**
             * SKILL body (redesign BE-04). Additive and nullable: files without it
             * parse and hash exactly as before (the hash is over the file's JSON).
             */
            Skill skill,
            /** CHECKIN body (redesign FR-7). Additive and nullable like {@code skill}. */
            CheckIn checkin) {}

    /**
     * One recall card for one skill. {@code correct} on a choice is server-internal: it
     * steers scheduling and is never in any DTO. {@code fullerExplanation} is shown only
     * after a different choice; {@code remember} is shown after every answer.
     */
    public record CheckIn(
            String skill,
            CheckInFormat format,
            String prompt,
            List<CheckInChoice> choices,
            String remember,
            String fullerExplanation,
            Boolean objective) {}

    /** Only {@code CHOICE} exists in FR-7; sort and free recall come with later units. */
    public enum CheckInFormat { CHOICE }

    public record CheckInChoice(String key, String label, Boolean correct) {}

    /**
     * One protective capability (Practice redesign BE-04). {@code title} on the
     * body is its capability phrasing ("Know where to go"); {@code summary} its
     * learning purpose. Every line is reviewed content like a scenario's.
     *
     * <ul>
     *   <li>{@code scenarioNodes}: {@code scenario-key#node_key} steps that rehearse it.</li>
     *   <li>{@code drills}: FE catalog drill ids (checked against the cross-repo contract).</li>
     *   <li>{@code checkInPrompts}: 2–4 retrieval phrasings for spaced refresh.</li>
     *   <li>{@code confusedWith}: skill keys whose rules a learner may mix up with this one.</li>
     *   <li>{@code ifThen}: the household routine this skill feeds (one plan line per routine,
     *       shared by several skills), saved only on an explicit tap.</li>
     * </ul>
     */
    public record Skill(
            String hazard,
            String cue,
            String action,
            String why,
            PhysicalRehearsal physicalRehearsal,
            ReviewTier reviewTier,
            List<String> scenarioNodes,
            List<String> drills,
            List<String> checkInPrompts,
            List<String> confusedWith,
            IfThen ifThen) {}

    /** {@code REQUIRED}: shown as practiced only after its drill is done. */
    public enum PhysicalRehearsal { REQUIRED, OPTIONAL, NONE }

    /** {@code LIFE_SAFETY} needs independent review at the exact hash; {@code PREP} the standard review. */
    public enum ReviewTier { LIFE_SAFETY, PREP }

    /** A household routine's if-then line, with {placeholders} filled from the plan or free text. */
    public record IfThen(String routine, String template) {}

    public record Source(String label, String url) {}

    /**
     * Debrief copy for one tag. {@code action} is the next family step a
     * "Worth practicing" tag points at (a {@link PracticeActions#ALLOWED} editor);
     * {@code params} narrow it (e.g. {@code intent=outOfTownContact}) and are
     * passed to the FE action map verbatim.
     */
    public record TagCopy(String text, ReadinessAction action, Map<String, String> params) {}

    /** A choose-your-path graph: nodes ask, choices move, outcomes end. Acyclic. */
    public record Scenario(
            String startNode,
            List<Node> nodes,
            List<Outcome> outcomes) {}

    /**
     * One decision step. {@code scene} and {@code messages} are the optional
     * story touches (EXEC-I5): an in-story time-and-place line ("Saturday,
     * 4:10 PM · Kitchen") and up to three short text messages the step shows
     * as bubbles. Reviewed content like every other line; additive and
     * nullable, so files without them parse and hash exactly as before.
     */
    public record Node(
            String key,
            String title,
            String body,
            String prompt,
            List<Choice> choices,
            String scene,
            List<Message> messages,
            /** Neutral scene still shown before a choice (schema 2). Never answer-revealing. */
            Media media) {}

    /** One in-story text message. {@code outgoing} = sent by the household (drawn on the right). */
    public record Message(
            String from,
            String text,
            Boolean outgoing) {}

    /**
     * One option. {@code next} names a node or an outcome; {@code feedback}
     * is the calm one-line reflection shown after choosing (the "why");
     * {@code tags} are the debrief tags this choice earns.
     *
     * <p>{@code consequence} is the optional in-story "what happened next"
     * line shown before {@code feedback} (spec Q10). It describes the
     * situation, never the person. All-or-none per version, so a version
     * never shows two-line reflections on some steps and one-line on others.
     * Additive and nullable: a file without it (v1) parses, validates and
     * hashes exactly as before, because the hash is taken over the file's
     * JSON tree, not over this record.</p>
     */
    public record Choice(
            String key,
            String label,
            String consequence,
            String feedback,
            List<String> tags,
            String next,
            /** Reaction media, withheld until this choice is committed; all-or-none per choice set. */
            Media media) {}

    /** A terminal state. {@code defaultAction} is the next step when no "Worth practicing" tag names one. */
    public record Outcome(
            String key,
            String title,
            String body,
            ReadinessAction defaultAction,
            Map<String, String> defaultParams,
            /** Calm terminal-state still for the debrief (schema 2). */
            Media media) {}

    public enum MediaKind { STILL, CLIP }

    public enum MediaStyle { ILLUSTRATION, REALISM }

    /**
     * A reviewed reference into {@code practice/media/manifest.json} (EXEC-M).
     * Content never carries a URL: the asset's SHA-256 is part of the hashed
     * content, so changing a single pixel means a new version and a new review
     * (scenario engagement spec §5.2). {@code slotKey} must be
     * {@code <scenario>.<node>.scene}, {@code <scenario>.<node>.<choice>.reaction}
     * or {@code <scenario>.<outcome>.ending}.
     */
    public record Media(
            String slotKey,
            MediaKind kind,
            String assetSha256,
            String posterSha256,
            Integer durationMs,
            String alt) {}

    /**
     * An adult-facilitated item sort. The server owns every word and the
     * completion tags; the client keeps the household's piles in memory only.
     */
    public record Activity(
            String type,
            String prompt,
            String reminderQuestion,
            String recapTitle,
            String recapBody,
            List<ActivityPile> piles,
            List<ActivityItem> items,
            List<String> completionTags,
            ReadinessAction nextAction,
            Map<String, String> nextParams,
            String nextPrompt,
            String nextLabel,
            String nextNote,
            /* Optional presentation copy; null keeps the client's Kit Builder defaults. */
            String finishLabel,
            String summaryTitle,
            String summaryBody,
            /* TOGETHER only (redesign FR-8); all nullable so ITEM_SORT files parse and hash as before. */
            String recommendedStyle,
            List<String> roles,
            List<TogetherTurn> turns,
            String remember,
            String talkAboutIt,
            List<String> followUps,
            /* TOGETHER only, optional: the setup overline's topic ("Home fire"). */
            String topic) {}

    /**
     * One step of an adult-led Together session. {@code rhythm} is a {@link TogetherRhythm} name,
     * {@code role} a {@link TogetherRole} name or ADULT / ALL / ANY. Strings, not enums, so a typo is
     * a validator error that names the turn instead of a parse failure of the whole file.
     */
    public record TogetherTurn(String rhythm, String role, String say, String grownUp, String ask) {}

    public record ActivityPile(String key, String label) {}

    /**
     * {@code artKey} selects a reviewed, code-native object drawing in the client.
     * Optional: an item without one shows no art in the decision loop.
     */
    public record ActivityItem(String key, String label, String artKey) {}

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
