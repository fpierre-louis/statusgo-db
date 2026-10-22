package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Choice;
import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Outcome;
import io.sitprep.sitprepapi.practice.PracticeContent.Scenario;
import io.sitprep.sitprepapi.practice.PracticeContent.TagCopy;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.ScenarioRun.TraceStep;
import io.sitprep.sitprepapi.readiness.ReadinessAction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The choose-your-path rules, with no Spring, no DB and no clock: what the
 * start is, whether a committed choice is legal at this point, where it leads,
 * and what the debrief says. Everything is read from the run's pinned
 * {@link Version}, so a run can never be walked against text it didn't start on.
 *
 * <p>The debrief is semantic, never a score: tags earned along the path, in the
 * order first earned, sorted into "Strong choices" and "Worth practicing", plus
 * ONE next family step — the first "Worth practicing" tag that names an action,
 * else the outcome's default.</p>
 */
public final class ScenarioEngine {

    private ScenarioEngine() {}

    public enum Refusal {
        /** The run is not waiting on that node (already answered, or not reached). */
        WRONG_NODE,
        /** No such choice on the node. */
        UNKNOWN_CHOICE,
        /** The run already reached an outcome. */
        FINISHED
    }

    public static final class EngineException extends RuntimeException {
        private final Refusal refusal;

        EngineException(Refusal refusal, String message) {
            super(message);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** A legal commit: which choice, and where the run goes next. */
    public record Commit(Node node, Choice choice, String nextKey, boolean reachedOutcome) {}

    public record NextStep(ReadinessAction action, Map<String, String> params) {}

    /**
     * {@code oneThing} is the ONE improvement the debrief names (Practice
     * redesign BE-02): the copy of the worth-practicing tag that produced
     * {@code nextStep}, so the line and the action beneath it agree; when no
     * improvement carries an action, the first worth-practicing line in earned
     * order; null when there is none.
     */
    public record Debrief(Outcome outcome, List<String> tags, List<String> strongChoices,
                          List<String> worthPracticing, NextStep nextStep, String oneThing) {}

    /**
     * The most decisions left from {@code key} to any outcome (0 at an outcome):
     * the "of N" in "Decision 2 of 4" (Practice redesign FR-2). The graph is
     * acyclic (validator), so a memoised walk terminates; an unknown key is 0.
     */
    public static int longestDecisionsFrom(Version v, String key) {
        return longest(v, key, new java.util.HashMap<>());
    }

    private static int longest(Version v, String key, Map<String, Integer> memo) {
        if (key == null || outcome(v, key).isPresent()) return 0;
        Integer seen = memo.get(key);
        if (seen != null) return seen;
        Optional<Node> n = node(v, key);
        int best = 0;
        if (n.isPresent() && n.get().choices() != null) {
            for (Choice c : n.get().choices()) {
                if (c != null) best = Math.max(best, longest(v, c.next(), memo));
            }
        }
        int result = n.isPresent() ? best + 1 : 0;
        memo.put(key, result);
        return result;
    }

    public static String startNode(Version v) {
        return v.body().scenario().startNode();
    }

    public static Optional<Node> node(Version v, String key) {
        Scenario s = v.body().scenario();
        if (key == null || s == null || s.nodes() == null) return Optional.empty();
        return s.nodes().stream().filter(n -> key.equals(n.key())).findFirst();
    }

    public static Optional<Outcome> outcome(Version v, String key) {
        Scenario s = v.body().scenario();
        if (key == null || s == null || s.outcomes() == null) return Optional.empty();
        return s.outcomes().stream().filter(o -> key.equals(o.key())).findFirst();
    }

    public static Optional<Choice> choice(Node node, String choiceKey) {
        if (node == null || node.choices() == null || choiceKey == null) return Optional.empty();
        return node.choices().stream().filter(c -> choiceKey.equals(c.key())).findFirst();
    }

    /**
     * Commit {@code choiceKey} on {@code nodeKey} for a run currently waiting
     * on {@code currentKey}.
     */
    public static Commit commit(Version v, String currentKey, String nodeKey, String choiceKey) {
        if (outcome(v, currentKey).isPresent()) {
            throw new EngineException(Refusal.FINISHED, "This practice already reached its end");
        }
        if (currentKey == null || !currentKey.equals(nodeKey)) {
            throw new EngineException(Refusal.WRONG_NODE, "This step was already answered");
        }
        Node node = node(v, nodeKey)
                .orElseThrow(() -> new EngineException(Refusal.WRONG_NODE, "Unknown step"));
        Choice c = choice(node, choiceKey)
                .orElseThrow(() -> new EngineException(Refusal.UNKNOWN_CHOICE, "Unknown choice"));
        return new Commit(node, c, c.next(), outcome(v, c.next()).isPresent());
    }

    /** The debrief for a run that walked {@code trace} and ended at {@code outcomeKey}. */
    public static Debrief debrief(Version v, List<TraceStep> trace, String outcomeKey) {
        Outcome outcome = outcome(v, outcomeKey)
                .orElseThrow(() -> new EngineException(Refusal.WRONG_NODE, "The run has not reached an outcome"));
        Set<String> earned = new LinkedHashSet<>();
        for (TraceStep step : trace == null ? List.<TraceStep>of() : trace) {
            node(v, step.nodeKey()).flatMap(n -> choice(n, step.choiceKey())).ifPresent(c -> {
                if (c.tags() != null) earned.addAll(c.tags());
            });
        }
        Map<String, TagCopy> copy = v.body().tags() == null ? Map.of() : v.body().tags();
        List<String> strong = new ArrayList<>();
        List<String> practice = new ArrayList<>();
        NextStep next = null;
        String oneThing = null;
        for (String tag : earned) {
            TagCopy t = copy.get(tag);
            DebriefTag known = DebriefTag.fromWire(tag).orElse(null);
            if (t == null || known == null) continue;
            if (known.polarity() == DebriefTag.Polarity.STRONG) {
                strong.add(t.text());
            } else {
                practice.add(t.text());
                if (next == null && t.action() != null) {
                    next = new NextStep(t.action(), params(t.params()));
                    oneThing = t.text();
                }
            }
        }
        if (next == null) next = new NextStep(outcome.defaultAction(), params(outcome.defaultParams()));
        if (oneThing == null && !practice.isEmpty()) oneThing = practice.get(0);
        return new Debrief(outcome, List.copyOf(earned), strong, practice, next, oneThing);
    }

    private static Map<String, String> params(Map<String, String> p) {
        return p == null ? Map.of() : Map.copyOf(p);
    }
}
