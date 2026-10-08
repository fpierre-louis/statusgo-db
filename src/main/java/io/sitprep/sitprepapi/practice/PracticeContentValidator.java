package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.JsonNode;
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

    private static Pattern word(String alternatives) {
        return Pattern.compile("(?i)\\b(" + alternatives + ")\\b");
    }

    public static List<String> validate(Version v) {
        List<String> errors = new ArrayList<>();
        ContentFile f = v.file();
        String where = v.resourcePath() == null ? "content" : v.resourcePath();

        if (f.schema() == null || f.schema() != PracticeContent.SCHEMA) {
            errors.add(where + ": schema must be " + PracticeContent.SCHEMA);
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
            else validateScenario(b, where, errors);
        }
        validateCopy(f.kind(), b, where, errors);
        return errors;
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
        if (b.estimatedMinutes() == null || b.estimatedMinutes() < 1 || b.estimatedMinutes() > 60) {
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

        if (b.tags() == null || b.tags().isEmpty()) {
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
            if (b.activity() == null || b.activity().isNull()) errors.add(where + ": a FAMILY_ACTIVITY needs content.activity");
            if (b.scenario() != null) errors.add(where + ": a FAMILY_ACTIVITY must not carry a scenario graph");
        } else if (kind == PracticeKind.ADULT_SCENARIO) {
            if (b.ageGuidance() != null && !b.ageGuidance().isEmpty()) {
                errors.add(where + ": ageGuidance is Family Practice metadata, not for adult scenarios");
            }
            if (b.activity() != null && !b.activity().isNull()) {
                errors.add(where + ": an ADULT_SCENARIO must not carry an activity body");
            }
        }
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
        }

        if (s.startNode() == null || !nodeByKey.containsKey(s.startNode())) {
            errors.add(where + ": startNode must name a node");
        }

        Set<String> declaredTags = b.tags() == null ? Set.of() : b.tags().keySet();
        Set<String> usedTags = new HashSet<>();
        for (Node n : nodeByKey.values()) {
            String at = where + ": node " + n.key();
            if (n.title() != null) requireText(n.title(), 160, at + " title", errors);
            requireText(n.body(), 1200, at + " body", errors);
            requireText(n.prompt(), 240, at + " prompt", errors);
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
    // Copy guards
    // ------------------------------------------------------------------

    private static void validateCopy(PracticeKind kind, Body b, String where, List<String> errors) {
        List<String> texts = new ArrayList<>();
        add(texts, b.title(), b.summary(), b.objective(), b.facilitation());
        if (b.tags() != null) b.tags().values().forEach(t -> { if (t != null) add(texts, t.text()); });
        if (b.scenario() != null) {
            Scenario s = b.scenario();
            if (s.nodes() != null) for (Node n : s.nodes()) {
                if (n == null) continue;
                add(texts, n.title(), n.body(), n.prompt());
                if (n.choices() != null) for (Choice c : n.choices()) if (c != null) add(texts, c.label(), c.feedback());
            }
            if (s.outcomes() != null) for (Outcome o : s.outcomes()) if (o != null) add(texts, o.title(), o.body());
        }
        if (b.activity() != null) collectText(b.activity(), texts);

        List<Pattern> rules = new ArrayList<>(PROHIBITED_ALL);
        if (kind == PracticeKind.FAMILY_ACTIVITY) rules.addAll(PROHIBITED_FAMILY);
        for (String text : texts) {
            for (Pattern p : rules) {
                if (p.matcher(text).find()) {
                    errors.add(where + ": prohibited practice copy (" + p.pattern() + ") in \"" + abbreviate(text) + "\"");
                }
            }
        }
    }

    private static void collectText(JsonNode node, List<String> out) {
        if (node.isTextual()) out.add(node.asText());
        else if (node.isContainerNode()) node.forEach(child -> collectText(child, out));
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
