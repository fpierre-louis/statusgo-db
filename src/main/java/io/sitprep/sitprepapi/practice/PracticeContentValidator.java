package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Activity;
import io.sitprep.sitprepapi.practice.PracticeContent.ActivityItem;
import io.sitprep.sitprepapi.practice.PracticeContent.ActivityPile;
import io.sitprep.sitprepapi.practice.PracticeContent.TogetherTurn;
import io.sitprep.sitprepapi.practice.PracticeContent.Body;
import io.sitprep.sitprepapi.practice.PracticeContent.Choice;
import io.sitprep.sitprepapi.practice.PracticeContent.ContentFile;
import io.sitprep.sitprepapi.practice.PracticeContent.Lifecycle;
import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Outcome;
import io.sitprep.sitprepapi.practice.PracticeContent.SafetyReview;
import io.sitprep.sitprepapi.practice.PracticeContent.Scenario;
import io.sitprep.sitprepapi.practice.PracticeContent.Source;
import io.sitprep.sitprepapi.practice.PracticeContent.TagCopy;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every rule a Practice content version must pass before anyone can start it
 * (gameplan "Catalog Validation"). Returns the list of problems; empty means
 * valid. {@link PracticeCatalog} excludes any version with problems, and
 * {@code PracticeShippedContentTest} fails the build on them.
 *
 * <p>The copy guards are a tripwire for the obvious, not a substitute for the
 * human safety review the lifecycle requires.</p>
 */
public final class PracticeContentValidator {

    private PracticeContentValidator() {}

    public static final Pattern KEY_FORMAT = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    public static final int KEY_MAX = 80;
    static final Pattern GRAPH_KEY_FORMAT = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** Age guidance is activity metadata, never household data (audit "Family Practice Guardrails"). */
    public static final Set<String> AGE_GUIDANCE = Set.of(
            "Great for ages 5-8", "Great for ages 8-12", "Family / all ages", "Teen conversation");

    static final int MIN_CHOICES = 2;
    static final int MAX_CHOICES = 4;

    /** A choice's optional in-story "what happened next" line (spec Q10). */
    static final int CONSEQUENCE_MAX = 140;
    static final int SCENE_MAX = 80;
    static final int MESSAGES_MAX = 3;
    static final int MESSAGE_FROM_MAX = 40;
    static final int MESSAGE_TEXT_MAX = 160;

    /** Never in any Practice copy: death, failure framing, scoring. */
    private static final List<Pattern> PROHIBITED_ALL = List.of(
            word("die|dies|died|dead|death|deaths|killed|kills|fatal|fatality|fatalities"),
            Pattern.compile("(?i)\\byou (failed|fail|lost|lose)\\b"),
            Pattern.compile("(?i)\\bgame over\\b"),
            Pattern.compile("(?i)\\bwrong answer\\b"),
            Pattern.compile("(?i)\\bincorrect\\b"),
            word("score|scores|scored|scoring"),
            Pattern.compile("(?i)\\b\\d+\\s*(points|pts)\\b"));

    /** Additionally never in Family Practice copy: injury, fear, and child-responsibility framing. */
    private static final List<Pattern> PROHIBITED_FAMILY = List.of(
            word("injured|injury|injuries|hurt|bleeding|blood"),
            word("scary|terrifying|terrified|panic|nightmare"),
            Pattern.compile("(?i)\\b(it'?s|it is) (your|up to you)( job)?\\b.*\\b(safe|protect|rescue|save)"),
            Pattern.compile("(?i)\\byou are (in charge of|responsible for)\\b"),
            Pattern.compile("(?i)\\b(protect|save|rescue) your (family|parents?|mom|dad|brother|sister|siblings?)\\b"),
            Pattern.compile("(?i)\\balone\\b"));

    /**
     * Never in a choice's consequence line. A consequence describes what
     * happened in the story, not a verdict on the person who chose: no
     * right/wrong, no mistakes, no "should have". Applied on top of
     * {@link #PROHIBITED_ALL} (and the family rules for family content).
     */
    private static final List<Pattern> JUDGMENT_IN_CONSEQUENCE = List.of(
            word("wrong|wrongly"),
            word("mistake|mistakes|mistaken"),
            word("fail|fails|failed|failing|failure"),
            Pattern.compile("(?i)\\bshould(n'?t| not)? have\\b"),
            Pattern.compile("(?i)\\bshould(n'?t)?'ve\\b"),
            word("correct|correctly|incorrect|incorrectly"),
            Pattern.compile("(?i)\\b(bad|poor|good|great|smart|best|better|worse|worst|right) (choice|call|decision|move|answer|pick)\\b"));

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?i)\\b(" + alternatives + ")\\b");
    }

    public static List<String> validate(Version v) {
        return validate(v, PracticeMediaManifest.getDefault());
    }

    static List<String> validate(Version v, PracticeMediaManifest manifest) {
        List<String> errors = new ArrayList<>();
        ContentFile f = v.file();
        String where = v.resourcePath() == null ? "content" : v.resourcePath();

        if (f.schema() == null || f.schema() < PracticeContent.MIN_SCHEMA || f.schema() > PracticeContent.SCHEMA) {
            errors.add(where + ": schema must be " + PracticeContent.MIN_SCHEMA + "-" + PracticeContent.SCHEMA);
        }
        if (f.key() == null || f.key().length() > KEY_MAX || !KEY_FORMAT.matcher(f.key()).matches()) {
            errors.add(where + ": key must be lower-kebab-case, 1-" + KEY_MAX + " chars");
        }
        if (f.version() == null || f.version() < 1) {
            errors.add(where + ": version must be a positive integer");
        }
        if (f.kind() == null) errors.add(where + ": kind is required");
        if (v.resourcePath() != null && f.key() != null && f.version() != null
                && !v.resourcePath().endsWith("/" + f.key() + "/v" + f.version() + ".json")) {
            errors.add(where + ": file must live at practice/content/" + f.key() + "/v" + f.version() + ".json");
        }

        validateLifecycle(f.lifecycle(), v.contentHash(), where, errors);

        Body b = f.content();
        if (b == null) {
            errors.add(where + ": content is required");
            return errors;
        }
        validateBody(f.kind(), b, where, errors);
        if (f.kind() == PracticeKind.ADULT_SCENARIO) {
            if (b.scenario() == null) errors.add(where + ": an ADULT_SCENARIO needs content.scenario");
            else {
                validateScenario(b, where, errors);
                validateScenarioMedia(f.schema(), f.key(), b, manifest, where, errors);
            }
        } else if (f.kind() == PracticeKind.FAMILY_ACTIVITY && b.activity() != null) {
            validateActivity(b, where, errors);
        } else if (f.kind() == PracticeKind.SKILL) {
            validateSkill(b, where, errors);
        } else if (f.kind() == PracticeKind.CHECKIN) {
            validateCheckIn(b, where, errors);
        }
        validateCopy(f.kind(), b, where, errors);
        return errors;
    }

    // ------------------------------------------------------------------
    // Media (EXEC-M): hashed, manifest-resolved, never a URL
    // ------------------------------------------------------------------

    static final int ALT_MAX = 200;
    static final int CLIP_MIN_MS = 6_000;
    static final int CLIP_MAX_MS = 10_000;
    private static final Pattern SHA256 = Pattern.compile("^sha256:[0-9a-f]{64}$");

    private static void validateScenarioMedia(Integer schema, String key, Body b, PracticeMediaManifest manifest,
                                              String where, List<String> errors) {
        PracticeContent.Scenario s = b.scenario();
        int count = 0;
        for (PracticeContent.Node n : s.nodes() == null ? List.<PracticeContent.Node>of() : s.nodes()) {
            if (n == null) continue;
            String at = where + ": node " + n.key();
            if (n.media() != null) {
                count++;
                validateMedia(n.media(), b.mediaStyle(), key + "." + n.key() + ".scene", manifest, at + " media", errors);
            }
            List<PracticeContent.Choice> choices = n.choices() == null ? List.of() : n.choices();
            int withMedia = 0;
            for (PracticeContent.Choice c : choices) {
                if (c == null || c.media() == null) continue;
                withMedia++;
                count++;
                validateMedia(c.media(), b.mediaStyle(), key + "." + n.key() + "." + c.key() + ".reaction", manifest,
                        at + " choice " + c.key() + " media", errors);
            }
            if (withMedia != 0 && withMedia != choices.size()) {
                // Media on some options and not others would grade the choice.
                errors.add(at + ": reaction media is all-or-none per choice set (" + withMedia + " of " + choices.size() + ")");
            }
        }
        for (PracticeContent.Outcome o : s.outcomes() == null ? List.<PracticeContent.Outcome>of() : s.outcomes()) {
            if (o == null || o.media() == null) continue;
            count++;
            validateMedia(o.media(), b.mediaStyle(), key + "." + o.key() + ".ending", manifest,
                    where + ": outcome " + o.key() + " media", errors);
        }
        if (count > 0 && (schema == null || schema < 2)) errors.add(where + ": media requires schema 2");
        if (count > 0 && b.mediaStyle() == null) errors.add(where + ": content.mediaStyle is required when media is present");
        if (count == 0 && b.mediaStyle() != null) errors.add(where + ": content.mediaStyle is only valid with media");
        if (count > 0 && !manifest.problems().isEmpty()) errors.add(where + ": media manifest is invalid: " + manifest.problems());
    }

    private static void validateMedia(PracticeContent.Media m, PracticeContent.MediaStyle style, String slot,
                                      PracticeMediaManifest manifest, String at, List<String> errors) {
        if (!slot.equals(m.slotKey())) errors.add(at + ".slotKey must be " + slot);
        if (m.kind() == null) errors.add(at + ".kind is required");
        requireText(m.alt(), ALT_MAX, at + ".alt", errors);
        if (m.assetSha256() == null || !SHA256.matcher(m.assetSha256()).matches()) {
            errors.add(at + ".assetSha256 must be sha256:<64 hex>");
            return;
        }
        var asset = manifest.find(m.assetSha256());
        if (asset.isEmpty()) {
            errors.add(at + ": asset " + m.assetSha256() + " is not in the media manifest");
            return;
        }
        if (m.kind() != null && asset.get().kind() != m.kind()) errors.add(at + ": kind does not match the manifest");
        if (style != null && asset.get().style() != style) errors.add(at + ": asset style does not match content.mediaStyle");
        if (m.kind() == PracticeContent.MediaKind.STILL) {
            if (m.posterSha256() != null || m.durationMs() != null) errors.add(at + ": a still has no poster or duration");
        } else if (m.kind() == PracticeContent.MediaKind.CLIP) {
            var poster = m.posterSha256() == null ? java.util.Optional.<PracticeMediaManifest.Asset>empty() : manifest.find(m.posterSha256());
            if (poster.isEmpty() || poster.get().kind() != PracticeContent.MediaKind.STILL) {
                errors.add(at + ": a clip needs a posterSha256 naming a manifest still");
            }
            if (m.durationMs() == null || m.durationMs() < CLIP_MIN_MS || m.durationMs() > CLIP_MAX_MS) {
                errors.add(at + ": a clip runs " + CLIP_MIN_MS / 1000 + "-" + CLIP_MAX_MS / 1000 + " seconds");
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private static void validateLifecycle(Lifecycle l, String hash, String where, List<String> errors) {
        if (l == null || l.publishState() == null) {
            errors.add(where + ": lifecycle.publishState is required");
            return;
        }
        PublishState state = l.publishState();
        SafetyReview r = l.safetyReview();
        if (state.requiresReview()) {
            if (r == null || r.status() != SafetyReviewStatus.APPROVED) {
                errors.add(where + ": " + state + " needs safetyReview.status APPROVED");
            }
            if (r == null || r.reviewedAt() == null) errors.add(where + ": " + state + " needs safetyReview.reviewedAt");
            if (r == null || isBlank(r.reviewedBy())) errors.add(where + ": " + state + " needs safetyReview.reviewedBy");
            if (r == null || !hash.equals(r.reviewedContentHash())) {
                errors.add(where + ": content changed since its safety review (reviewedContentHash "
                        + (r == null ? null : r.reviewedContentHash()) + " != " + hash
                        + "). Publish a new version instead of editing a reviewed one.");
            }
        }
        if (state == PublishState.PUBLISHED && l.publishedAt() == null) {
            errors.add(where + ": PUBLISHED needs lifecycle.publishedAt");
        }
        if (state == PublishState.RETIRED && l.retiredAt() == null) {
            errors.add(where + ": RETIRED needs lifecycle.retiredAt");
        }
        if (state != PublishState.RETIRED && l.retiredAt() != null) {
            errors.add(where + ": retiredAt is only valid on RETIRED content");
        }
    }

    // ------------------------------------------------------------------
    // Body
    // ------------------------------------------------------------------

    private static void validateBody(PracticeKind kind, Body b, String where, List<String> errors) {
        requireText(b.title(), 160, where + ": content.title", errors);
        requireText(b.summary(), 500, where + ": content.summary", errors);
        if (b.objective() != null) requireText(b.objective(), 240, where + ": content.objective", errors);
        if (kind == PracticeKind.SKILL) {
            if (b.estimatedMinutes() != null) errors.add(where + ": a SKILL has no estimatedMinutes (its activities do)");
        } else if (kind == PracticeKind.CHECKIN) {
            if (b.estimatedMinutes() != null) errors.add(where + ": a CHECKIN has no estimatedMinutes");
        } else if (b.estimatedMinutes() == null || b.estimatedMinutes() < 1 || b.estimatedMinutes() > 60) {
            errors.add(where + ": content.estimatedMinutes must be 1-60");
        }

        if (b.sources() == null || b.sources().isEmpty()) {
            errors.add(where + ": content.sources needs at least one official source");
        } else {
            for (Source s : b.sources()) {
                if (s == null || isBlank(s.label())) errors.add(where + ": every source needs a label");
                if (s == null || s.url() == null || !s.url().startsWith("https://")) {
                    errors.add(where + ": every source needs an https:// url");
                }
            }
        }

        if (kind == PracticeKind.SKILL || kind == PracticeKind.CHECKIN) {
            if (b.tags() != null && !b.tags().isEmpty()) errors.add(where + ": a " + kind + " carries no debrief tags");
        } else if (b.tags() == null || b.tags().isEmpty()) {
            errors.add(where + ": content.tags (debrief copy) is required");
        } else {
            for (Map.Entry<String, TagCopy> e : b.tags().entrySet()) {
                Optional<DebriefTag> tag = DebriefTag.fromWire(e.getKey());
                if (tag.isEmpty()) {
                    errors.add(where + ": unknown debrief tag '" + e.getKey() + "' (closed vocabulary: DebriefTag)");
                    continue;
                }
                TagCopy copy = e.getValue();
                if (copy == null) {
                    errors.add(where + ": tag " + e.getKey() + " needs copy");
                    continue;
                }
                requireText(copy.text(), 240, where + ": tag " + e.getKey() + " text", errors);
                if (tag.get().polarity() == DebriefTag.Polarity.PRACTICE && copy.action() == null) {
                    errors.add(where + ": practice tag " + e.getKey() + " needs an action (the next family step)");
                }
                if (copy.action() != null && !PracticeActions.allowed(copy.action())) {
                    errors.add(where + ": tag " + e.getKey() + " action " + copy.action()
                            + " is not a preparedness editor (PracticeActions.ALLOWED)");
                }
                validateParams(copy.params(), copy.action(), where + ": tag " + e.getKey(), errors);
            }
        }

        if (kind == PracticeKind.FAMILY_ACTIVITY) {
            requireText(b.facilitation(), 800, where + ": a FAMILY_ACTIVITY needs adult facilitation copy", errors);
            if (b.ageGuidance() == null || b.ageGuidance().isEmpty()) {
                errors.add(where + ": a FAMILY_ACTIVITY needs ageGuidance");
            } else {
                for (String g : b.ageGuidance()) {
                    if (!AGE_GUIDANCE.contains(g)) errors.add(where + ": unknown ageGuidance '" + g + "'");
                }
            }
            if (b.activity() == null) errors.add(where + ": a FAMILY_ACTIVITY needs content.activity");
            if (b.scenario() != null) errors.add(where + ": a FAMILY_ACTIVITY must not carry a scenario graph");
        } else if (kind == PracticeKind.ADULT_SCENARIO) {
            if (b.ageGuidance() != null && !b.ageGuidance().isEmpty()) {
                errors.add(where + ": ageGuidance is Family Practice metadata, not for adult scenarios");
            }
            if (b.activity() != null) {
                errors.add(where + ": an ADULT_SCENARIO must not carry an activity body");
            }
        } else if (kind == PracticeKind.SKILL) {
            if (b.skill() == null) errors.add(where + ": a SKILL needs content.skill");
            if (b.scenario() != null || b.activity() != null) {
                errors.add(where + ": a SKILL carries no scenario graph or activity body");
            }
            if (b.ageGuidance() != null && !b.ageGuidance().isEmpty()) errors.add(where + ": a SKILL has no ageGuidance");
            if (b.facilitation() != null) errors.add(where + ": a SKILL has no facilitation copy");
        } else if (kind == PracticeKind.CHECKIN) {
            if (b.checkin() == null) errors.add(where + ": a CHECKIN needs content.checkin");
            if (b.scenario() != null || b.activity() != null) {
                errors.add(where + ": a CHECKIN carries no scenario graph or activity body");
            }
            if (b.ageGuidance() != null && !b.ageGuidance().isEmpty()) errors.add(where + ": a CHECKIN has no ageGuidance");
            if (b.facilitation() != null) errors.add(where + ": a CHECKIN has no facilitation copy");
        }
        if (kind != PracticeKind.SKILL && b.skill() != null) {
            errors.add(where + ": only a SKILL carries content.skill");
        }
        if (kind != PracticeKind.CHECKIN && b.checkin() != null) {
            errors.add(where + ": only a CHECKIN carries content.checkin");
        }
    }

    /** Action params: short identifier-like strings only, and only alongside an action. */
    private static void validateParams(Map<String, String> params, Object action, String at, List<String> errors) {
        if (params == null || params.isEmpty()) return;
        if (action == null) errors.add(at + " has params but no action");
        for (Map.Entry<String, String> p : params.entrySet()) {
            if (p.getKey() == null || !PARAM.matcher(p.getKey()).matches()
                    || p.getValue() == null || !PARAM.matcher(p.getValue()).matches()) {
                errors.add(at + " param " + p.getKey() + " must be a short identifier");
            }
        }
    }

    static final Pattern PARAM = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    // ------------------------------------------------------------------
    // Family activity
    // ------------------------------------------------------------------

    private static void validateActivity(Body b, String where, List<String> errors) {
        Activity a = b.activity();
        String at = where + ": activity";
        boolean together = "TOGETHER".equals(a.type());
        if (!together && !"ITEM_SORT".equals(a.type())) errors.add(at + ".type must be ITEM_SORT or TOGETHER");
        if (together) {
            validateTogether(a, at, errors);
        } else {
            validateItemSort(a, at, errors);
        }
        validateActivityCompletion(b, a, where, at, errors);
    }

    private static void validateItemSort(Activity a, String at, List<String> errors) {
        if (a.recommendedStyle() != null || a.roles() != null || a.turns() != null || a.remember() != null
                || a.talkAboutIt() != null || a.followUps() != null || a.topic() != null) {
            errors.add(at + ": Together fields belong only to a TOGETHER activity");
        }
        requireText(a.prompt(), 240, at + ".prompt", errors);
        requireText(a.reminderQuestion(), 240, at + ".reminderQuestion", errors);
        requireText(a.recapTitle(), 120, at + ".recapTitle", errors);
        requireText(a.recapBody(), 500, at + ".recapBody", errors);

        List<ActivityPile> piles = a.piles() == null ? List.of() : a.piles();
        if (piles.size() < 2 || piles.size() > 4) errors.add(at + " needs 2-4 piles");
        Set<String> pileKeys = new HashSet<>();
        for (ActivityPile pile : piles) {
            if (pile == null || pile.key() == null || !GRAPH_KEY_FORMAT.matcher(pile.key()).matches()) {
                errors.add(at + ": pile key missing or malformed");
                continue;
            }
            if (!pileKeys.add(pile.key())) errors.add(at + ": duplicate pile key " + pile.key());
            requireText(pile.label(), 120, at + ": pile " + pile.key() + " label", errors);
        }

        List<ActivityItem> items = a.items() == null ? List.of() : a.items();
        if (items.isEmpty() || items.size() > 20) errors.add(at + " needs 1-20 items");
        Set<String> itemKeys = new HashSet<>();
        for (ActivityItem item : items) {
            if (item == null || item.key() == null || !GRAPH_KEY_FORMAT.matcher(item.key()).matches()) {
                errors.add(at + ": item key missing or malformed");
                continue;
            }
            if (!itemKeys.add(item.key())) errors.add(at + ": duplicate item key " + item.key());
            requireText(item.label(), 160, at + ": item " + item.key() + " label", errors);
            if (item.artKey() != null && !GRAPH_KEY_FORMAT.matcher(item.artKey()).matches()) {
                errors.add(at + ": item " + item.key() + " artKey must be lower-snake-case");
            }
        }
    }

    static final int TURNS_MIN = 4;
    static final int TURNS_MAX = 8;

    /** TOGETHER (FR-8): the rhythm, roles and child-safety rules of an adult-led session. */
    private static void validateTogether(Activity a, String at, List<String> errors) {
        if ((a.piles() != null && !a.piles().isEmpty()) || (a.items() != null && !a.items().isEmpty())) {
            errors.add(at + ": a TOGETHER activity carries no piles or items");
        }
        optionalText(a.prompt(), 240, at + ".prompt", errors);
        optionalText(a.reminderQuestion(), 240, at + ".reminderQuestion", errors);
        optionalText(a.recapTitle(), 120, at + ".recapTitle", errors);
        optionalText(a.recapBody(), 500, at + ".recapBody", errors);

        if (a.recommendedStyle() == null || Arrays.stream(TogetherStyle.values())
                .noneMatch(st -> st.name().equals(a.recommendedStyle()))) {
            errors.add(at + ".recommendedStyle must be DO_IT_TOGETHER, TAKE_A_TURN or TEEN_LEAD");
        }

        Set<String> declared = new HashSet<>();
        List<String> roles = a.roles() == null ? List.of() : a.roles();
        if (roles.isEmpty()) errors.add(at + ".roles needs at least one role");
        for (String role : roles) {
            if (TogetherRole.parse(role).isEmpty()) {
                errors.add(at + ": unknown role " + role + " (roles come from the server TogetherRole list)");
            } else if (!declared.add(role)) {
                errors.add(at + ": duplicate role " + role);
            }
        }

        List<TogetherTurn> turns = a.turns() == null ? List.of() : a.turns();
        if (turns.size() < TURNS_MIN || turns.size() > TURNS_MAX) {
            errors.add(at + " needs " + TURNS_MIN + "-" + TURNS_MAX + " turns");
        }
        int lastRhythm = -1;
        Set<TogetherRhythm> seen = EnumSet.noneOf(TogetherRhythm.class);
        int n = 0;
        for (TogetherTurn t : turns) {
            n++;
            String tt = at + ": turn " + n;
            if (t == null) { errors.add(tt + " is empty"); continue; }
            TogetherRhythm rhythm = null;
            for (TogetherRhythm r : TogetherRhythm.values()) if (r.name().equals(t.rhythm())) rhythm = r;
            if (rhythm == null) {
                errors.add(tt + ".rhythm must be SHOW_ME, WITH_ME, TRY_IT or TELL_ME_WHY");
            } else {
                if (rhythm.ordinal() < lastRhythm) errors.add(tt + ": rhythm goes backwards (show, with, try, tell)");
                lastRhythm = Math.max(lastRhythm, rhythm.ordinal());
                seen.add(rhythm);
            }
            requireText(t.say(), 120, tt + ".say", errors);
            requireText(t.grownUp(), 160, tt + ".grownUp", errors);
            optionalText(t.ask(), 120, tt + ".ask", errors);

            String role = t.role();
            boolean special = role != null && TogetherRole.SPECIAL.contains(role);
            Optional<TogetherRole> known = TogetherRole.parse(role);
            if (!special && (known.isEmpty() || !declared.contains(role))) {
                errors.add(tt + ".role " + role + " must be ADULT, ALL, ANY or one of the listed roles");
            }
            if (rhythm == TogetherRhythm.SHOW_ME && !"ADULT".equals(role)) {
                errors.add(tt + ": a SHOW_ME turn is led by the ADULT");
            }
            if (rhythm == TogetherRhythm.TELL_ME_WHY && isBlank(t.ask())) {
                errors.add(tt + ": a TELL_ME_WHY turn needs an ask");
            }
            if ((rhythm == TogetherRhythm.TRY_IT || rhythm == TogetherRhythm.TELL_ME_WHY)
                    && known.map(TogetherRole::adultOnly).orElse(false)) {
                errors.add(tt + ": adult-only role " + role + " cannot be on a child's step (" + t.rhythm() + ")");
            }
        }
        if (seen.size() != TogetherRhythm.values().length) {
            errors.add(at + ": all four rhythms (SHOW_ME, WITH_ME, TRY_IT, TELL_ME_WHY) must appear");
        }

        requireText(a.remember(), 160, at + ".remember", errors);
        requireText(a.talkAboutIt(), 160, at + ".talkAboutIt", errors);
        if (a.topic() != null && (a.topic().isBlank() || a.topic().length() > 40)) {
            errors.add(at + ".topic must be 1-40 characters when present");
        }
        List<String> followUps = a.followUps() == null ? List.of() : a.followUps();
        if (followUps.isEmpty() || followUps.size() > 3) errors.add(at + ".followUps needs 1-3 entries");
        for (String f : followUps) requireText(f, 80, at + ".followUps entry", errors);
    }

    private static void validateActivityCompletion(Body b, Activity a, String where, String at, List<String> errors) {
        Set<String> declaredTags = b.tags() == null ? Set.of() : b.tags().keySet();
        List<String> completionTags = a.completionTags() == null ? List.of() : a.completionTags();
        if (completionTags.isEmpty()) errors.add(at + ".completionTags needs at least one tag");
        Set<String> seenTags = new HashSet<>();
        for (String tag : completionTags) {
            if (!declaredTags.contains(tag)) errors.add(at + " uses tag '" + tag + "' with no copy in content.tags");
            if (!seenTags.add(tag)) errors.add(at + ": duplicate completion tag " + tag);
        }
        for (String declared : declaredTags) {
            if (!seenTags.contains(declared)) errors.add(where + ": tag " + declared + " has copy but activity completion does not use it");
        }

        if (!PracticeActions.allowed(a.nextAction())) {
            errors.add(at + " needs a nextAction from PracticeActions.ALLOWED");
        }
        validateParams(a.nextParams(), a.nextAction(), at, errors);
        requireText(a.nextPrompt(), 240, at + ".nextPrompt", errors);
        requireText(a.nextLabel(), 120, at + ".nextLabel", errors);
        requireText(a.nextNote(), 240, at + ".nextNote", errors);
        optionalText(a.finishLabel(), 40, at + ".finishLabel", errors);
        optionalText(a.summaryTitle(), 80, at + ".summaryTitle", errors);
        optionalText(a.summaryBody(), 240, at + ".summaryBody", errors);
    }

    private static void optionalText(String value, int max, String where, List<String> errors) {
        if (value == null) return;
        requireText(value, max, where, errors);
    }

    // ------------------------------------------------------------------
    // Scenario graph
    // ------------------------------------------------------------------

    private static void validateScenario(Body b, String where, List<String> errors) {
        Scenario s = b.scenario();
        List<Node> nodes = s.nodes() == null ? List.of() : s.nodes();
        List<Outcome> outcomes = s.outcomes() == null ? List.of() : s.outcomes();
        if (nodes.isEmpty()) errors.add(where + ": scenario needs at least one node");
        if (outcomes.isEmpty()) errors.add(where + ": scenario needs at least one outcome");

        Map<String, Node> nodeByKey = new LinkedHashMap<>();
        Set<String> outcomeKeys = new HashSet<>();
        for (Node n : nodes) {
            if (n == null || n.key() == null || !GRAPH_KEY_FORMAT.matcher(n.key()).matches()) {
                errors.add(where + ": node key missing or malformed");
                continue;
            }
            if (nodeByKey.putIfAbsent(n.key(), n) != null) errors.add(where + ": duplicate node key " + n.key());
        }
        for (Outcome o : outcomes) {
            if (o == null || o.key() == null || !GRAPH_KEY_FORMAT.matcher(o.key()).matches()) {
                errors.add(where + ": outcome key missing or malformed");
                continue;
            }
            if (nodeByKey.containsKey(o.key()) || !outcomeKeys.add(o.key())) {
                errors.add(where + ": duplicate node/outcome key " + o.key());
            }
            requireText(o.title(), 160, where + ": outcome " + o.key() + " title", errors);
            requireText(o.body(), 800, where + ": outcome " + o.key() + " body", errors);
            if (!PracticeActions.allowed(o.defaultAction())) {
                errors.add(where + ": outcome " + o.key() + " needs a defaultAction from PracticeActions.ALLOWED");
            }
            validateParams(o.defaultParams(), o.defaultAction(), where + ": outcome " + o.key(), errors);
        }

        if (s.startNode() == null || !nodeByKey.containsKey(s.startNode())) {
            errors.add(where + ": startNode must name a node");
        }

        Set<String> declaredTags = b.tags() == null ? Set.of() : b.tags().keySet();
        Set<String> usedTags = new HashSet<>();
        List<String> withConsequence = new ArrayList<>();
        List<String> withoutConsequence = new ArrayList<>();
        for (Node n : nodeByKey.values()) {
            String at = where + ": node " + n.key();
            if (n.title() != null) requireText(n.title(), 160, at + " title", errors);
            requireText(n.body(), 1200, at + " body", errors);
            requireText(n.prompt(), 240, at + " prompt", errors);
            // EXEC-I5 story touches: optional, short, and never blank when present.
            if (n.scene() != null) requireText(n.scene(), SCENE_MAX, at + " scene", errors);
            if (n.messages() != null) {
                if (n.messages().isEmpty() || n.messages().size() > MESSAGES_MAX) {
                    errors.add(at + " messages needs 1-" + MESSAGES_MAX + " entries (omit it instead of an empty list)");
                }
                for (int i = 0; i < n.messages().size(); i++) {
                    PracticeContent.Message m = n.messages().get(i);
                    if (m == null) { errors.add(at + " message " + i + " is empty"); continue; }
                    requireText(m.from(), MESSAGE_FROM_MAX, at + " message " + i + " from", errors);
                    requireText(m.text(), MESSAGE_TEXT_MAX, at + " message " + i + " text", errors);
                }
            }
            List<Choice> choices = n.choices() == null ? List.of() : n.choices();
            if (choices.size() < MIN_CHOICES || choices.size() > MAX_CHOICES) {
                errors.add(at + " needs " + MIN_CHOICES + "-" + MAX_CHOICES + " choices");
            }
            Set<String> choiceKeys = new HashSet<>();
            for (Choice c : choices) {
                if (c == null || c.key() == null || !GRAPH_KEY_FORMAT.matcher(c.key()).matches()) {
                    errors.add(at + ": choice key missing or malformed");
                    continue;
                }
                if (!choiceKeys.add(c.key())) errors.add(at + ": duplicate choice key " + c.key());
                requireText(c.label(), 160, at + " choice " + c.key() + " label", errors);
                requireText(c.feedback(), 280, at + " choice " + c.key() + " feedback", errors);
                String ref = n.key() + "." + c.key();
                if (c.consequence() == null) {
                    withoutConsequence.add(ref);
                } else {
                    withConsequence.add(ref);
                    if (c.consequence().isBlank()) {
                        errors.add(at + " choice " + c.key() + " consequence is blank (omit it instead)");
                    } else if (c.consequence().length() > CONSEQUENCE_MAX) {
                        errors.add(at + " choice " + c.key() + " consequence is longer than "
                                + CONSEQUENCE_MAX + " characters");
                    }
                }
                if (c.next() == null || !(nodeByKey.containsKey(c.next()) || outcomeKeys.contains(c.next()))) {
                    errors.add(at + " choice " + c.key() + " points to missing node/outcome '" + c.next() + "'");
                }
                for (String t : c.tags() == null ? List.<String>of() : c.tags()) {
                    if (!declaredTags.contains(t)) {
                        errors.add(at + " choice " + c.key() + " uses tag '" + t + "' with no debrief copy in content.tags");
                    }
                    usedTags.add(t);
                }
            }
        }
        for (String declared : declaredTags) {
            if (!usedTags.contains(declared)) errors.add(where + ": tag " + declared + " has copy but no choice earns it");
        }
        if (!withConsequence.isEmpty() && !withoutConsequence.isEmpty()) {
            errors.add(where + ": consequence is all-or-none per version; " + withConsequence.size() + " of "
                    + (withConsequence.size() + withoutConsequence.size())
                    + " choices have one, missing on " + String.join(", ", withoutConsequence));
        }

        if (s.startNode() != null && nodeByKey.containsKey(s.startNode())) {
            checkReachableAndAcyclic(s.startNode(), nodeByKey, outcomeKeys, where, errors);
        }
    }

    /** Every node and outcome reachable from the start; no cycles, so every path ends at an outcome. */
    private static void checkReachableAndAcyclic(String start, Map<String, Node> nodes, Set<String> outcomes,
                                                 String where, List<String> errors) {
        Set<String> reached = new HashSet<>();
        Map<String, Integer> color = new HashMap<>(); // 1 = on stack, 2 = done
        Deque<String> stack = new ArrayDeque<>();
        Deque<java.util.Iterator<String>> iters = new ArrayDeque<>();
        boolean cycle = false;

        stack.push(start);
        iters.push(nextKeys(nodes.get(start)).iterator());
        color.put(start, 1);
        reached.add(start);
        while (!stack.isEmpty()) {
            java.util.Iterator<String> it = iters.peek();
            if (it.hasNext()) {
                String next = it.next();
                reached.add(next);
                Node n = nodes.get(next);
                if (n == null) continue; // outcome (or a dangling target, reported elsewhere)
                Integer c = color.get(next);
                if (c != null && c == 1) {
                    cycle = true;
                } else if (c == null) {
                    color.put(next, 1);
                    stack.push(next);
                    iters.push(nextKeys(n).iterator());
                }
            } else {
                color.put(stack.pop(), 2);
                iters.pop();
            }
        }
        if (cycle) errors.add(where + ": scenario graph has a cycle; every path must end at an outcome");
        for (String k : nodes.keySet()) if (!reached.contains(k)) errors.add(where + ": orphan node " + k);
        for (String k : outcomes) if (!reached.contains(k)) errors.add(where + ": unreachable outcome " + k);
    }

    private static List<String> nextKeys(Node n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.choices() == null) return out;
        for (Choice c : n.choices()) if (c != null && c.next() != null) out.add(c.next());
        return out;
    }

    // ------------------------------------------------------------------
    // Skill (Practice redesign BE-04)
    // ------------------------------------------------------------------

    /** {@code scenario-key#node_key}. */
    static final Pattern SCENARIO_NODE_REF = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*#[a-z][a-z0-9_]{0,63}$");
    /** An FE drill id (the drill log's key shape, without a phase). */
    static final Pattern DRILL_REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$");
    static final Pattern IF_THEN_ROUTINE = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private static void validateSkill(Body b, String where, List<String> errors) {
        PracticeContent.Skill k = b.skill();
        if (k == null) return;
        if (k.hazard() == null || !KEY_FORMAT.matcher(k.hazard()).matches()) {
            errors.add(where + ": skill.hazard must be a lower-kebab hazard key");
        }
        requireText(k.cue(), 240, where + ": skill.cue", errors);
        requireText(k.action(), 240, where + ": skill.action (the protective action)", errors);
        requireText(k.why(), 240, where + ": skill.why", errors);
        if (k.physicalRehearsal() == null) errors.add(where + ": skill.physicalRehearsal is required");
        if (k.reviewTier() == null) errors.add(where + ": skill.reviewTier is required");
        if (k.physicalRehearsal() == PracticeContent.PhysicalRehearsal.REQUIRED
                && (k.drills() == null || k.drills().isEmpty())) {
            errors.add(where + ": a skill whose physical rehearsal is REQUIRED needs at least one drill");
        }
        List<String> prompts = k.checkInPrompts();
        if (prompts == null || prompts.size() < 2 || prompts.size() > 4) {
            errors.add(where + ": skill.checkInPrompts needs 2-4 phrasings");
        } else {
            for (String q : prompts) requireText(q, 240, where + ": skill.checkInPrompts entry", errors);
        }
        if (k.scenarioNodes() != null) for (String ref : k.scenarioNodes()) {
            if (ref == null || !SCENARIO_NODE_REF.matcher(ref).matches()) {
                errors.add(where + ": skill.scenarioNodes entry '" + ref + "' must be scenario-key#node_key");
            }
        }
        if (k.drills() != null) for (String d : k.drills()) {
            if (d == null || !DRILL_REF.matcher(d).matches()) errors.add(where + ": skill.drills entry '" + d + "' is not a drill id");
        }
        if (k.confusedWith() != null) for (String c : k.confusedWith()) {
            if (c == null || !KEY_FORMAT.matcher(c).matches()) errors.add(where + ": skill.confusedWith entry '" + c + "' is not a skill key");
        }
        if (k.ifThen() != null) {
            if (k.ifThen().routine() == null || !IF_THEN_ROUTINE.matcher(k.ifThen().routine()).matches()) {
                errors.add(where + ": skill.ifThen.routine must be a lower-kebab routine key");
            }
            requireText(k.ifThen().template(), 240, where + ": skill.ifThen.template", errors);
        }
    }

    // ------------------------------------------------------------------
    // Check-in (Practice redesign FR-7)
    // ------------------------------------------------------------------

    static final int CHECKIN_PROMPT_MAX = 160;
    static final int CHECKIN_LABEL_MAX = 120;
    static final int CHECKIN_REMEMBER_MAX = 160;
    static final int CHECKIN_EXPLANATION_MAX = 280;
    static final Pattern CHECKIN_CHOICE_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    private static void validateCheckIn(Body b, String where, List<String> errors) {
        PracticeContent.CheckIn c = b.checkin();
        if (c == null) return;
        if (c.skill() == null || !KEY_FORMAT.matcher(c.skill()).matches()) {
            errors.add(where + ": checkin.skill must be a skill key");
        }
        if (c.format() == null) errors.add(where + ": checkin.format is required (CHOICE)");
        requireText(c.prompt(), CHECKIN_PROMPT_MAX, where + ": checkin.prompt", errors);
        requireText(c.remember(), CHECKIN_REMEMBER_MAX, where + ": checkin.remember", errors);
        requireText(c.fullerExplanation(), CHECKIN_EXPLANATION_MAX, where + ": checkin.fullerExplanation", errors);
        if (c.objective() == null) errors.add(where + ": checkin.objective is required");
        List<PracticeContent.CheckInChoice> choices = c.choices();
        if (choices == null || choices.size() < 2 || choices.size() > 3) {
            errors.add(where + ": checkin.choices needs 2-3 choices");
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        int correct = 0;
        for (PracticeContent.CheckInChoice ch : choices) {
            if (ch == null) { errors.add(where + ": checkin.choices has a null entry"); continue; }
            if (ch.key() == null || !CHECKIN_CHOICE_KEY.matcher(ch.key()).matches()) {
                errors.add(where + ": checkin choice key '" + ch.key() + "' must match " + CHECKIN_CHOICE_KEY.pattern());
            } else if (!seen.add(ch.key())) {
                errors.add(where + ": duplicate checkin choice key " + ch.key());
            }
            requireText(ch.label(), CHECKIN_LABEL_MAX, where + ": checkin choice " + ch.key() + " label", errors);
            if (ch.correct() == null) errors.add(where + ": checkin choice " + ch.key() + " needs correct true/false");
            else if (ch.correct()) correct++;
        }
        if (correct != 1) errors.add(where + ": checkin.choices needs exactly one correct choice (found " + correct + ")");
    }

    // ------------------------------------------------------------------
    // Copy guards
    // ------------------------------------------------------------------

    private static void validateCopy(PracticeKind kind, Body b, String where, List<String> errors) {
        List<String> texts = new ArrayList<>();
        List<String> consequences = new ArrayList<>();
        add(texts, b.title(), b.summary(), b.objective(), b.facilitation());
        if (b.skill() != null) {
            PracticeContent.Skill k = b.skill();
            add(texts, k.cue(), k.action(), k.why());
            if (k.checkInPrompts() != null) k.checkInPrompts().forEach(q -> add(texts, q));
            if (k.ifThen() != null) add(texts, k.ifThen().template());
        }
        if (b.checkin() != null) {
            PracticeContent.CheckIn c = b.checkin();
            add(texts, c.prompt(), c.remember(), c.fullerExplanation());
            if (c.choices() != null) c.choices().forEach(ch -> { if (ch != null) add(texts, ch.label()); });
        }
        if (b.tags() != null) b.tags().values().forEach(t -> { if (t != null) add(texts, t.text()); });
        if (b.scenario() != null) {
            Scenario s = b.scenario();
            if (s.nodes() != null) for (Node n : s.nodes()) {
                if (n == null) continue;
                add(texts, n.title(), n.body(), n.prompt());
                if (n.choices() != null) for (Choice c : n.choices()) {
                    if (c == null) continue;
                    add(texts, c.label(), c.consequence(), c.feedback());
                    if (c.consequence() != null) consequences.add(c.consequence());
                }
            }
            if (s.outcomes() != null) for (Outcome o : s.outcomes()) if (o != null) add(texts, o.title(), o.body());
        }
        if (b.activity() != null) {
            Activity a = b.activity();
            add(texts, a.prompt(), a.reminderQuestion(), a.recapTitle(), a.recapBody(),
                    a.nextPrompt(), a.nextLabel(), a.nextNote(), a.finishLabel(), a.summaryTitle(), a.summaryBody());
            if (a.piles() != null) a.piles().forEach(p -> { if (p != null) add(texts, p.label()); });
            if (a.items() != null) a.items().forEach(i -> { if (i != null) add(texts, i.label()); });
            add(texts, a.remember(), a.talkAboutIt());
            if (a.followUps() != null) a.followUps().forEach(f -> add(texts, f));
            if (a.turns() != null) a.turns().forEach(t -> { if (t != null) add(texts, t.say(), t.grownUp(), t.ask()); });
        }

        List<Pattern> rules = new ArrayList<>(PROHIBITED_ALL);
        if (kind == PracticeKind.FAMILY_ACTIVITY) rules.addAll(PROHIBITED_FAMILY);
        for (String text : texts) {
            for (Pattern p : rules) {
                if (p.matcher(text).find()) {
                    errors.add(where + ": prohibited practice copy (" + p.pattern() + ") in \"" + abbreviate(text) + "\"");
                }
            }
        }
        for (String text : consequences) {
            for (Pattern p : JUDGMENT_IN_CONSEQUENCE) {
                if (p.matcher(text).find()) {
                    errors.add(where + ": judgment wording in consequence (" + p.pattern() + ") in \""
                            + abbreviate(text) + "\"; describe what happened, not the person");
                }
            }
        }
    }

    private static void add(List<String> out, String... values) {
        for (String v : values) if (v != null && !v.isBlank()) out.add(v);
    }

    private static void requireText(String value, int max, String label, List<String> errors) {
        if (isBlank(value)) errors.add(label + " is required");
        else if (value.length() > max) errors.add(label + " is longer than " + max + " characters");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
