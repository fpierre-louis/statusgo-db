package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Choice;
import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.ScenarioEngine.Refusal;
import io.sitprep.sitprepapi.practice.ScenarioRun.TraceStep;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioEngineTest {

    private final Version fixture = version(scenario("k", 1));

    @Test
    void commitMovesToTheChosenNext() {
        var c = ScenarioEngine.commit(fixture, "start", "start", "text");
        assertThat(c.nextKey()).isEqualTo("second");
        assertThat(c.reachedOutcome()).isFalse();
        assertThat(c.choice().feedback()).isNotBlank();

        var end = ScenarioEngine.commit(fixture, "second", "second", "plan");
        assertThat(end.reachedOutcome()).isTrue();
    }

    @Test
    void refusals() {
        assertThatThrownBy(() -> ScenarioEngine.commit(fixture, "second", "start", "text"))
                .isInstanceOfSatisfying(ScenarioEngine.EngineException.class,
                        e -> assertThat(e.refusal()).isEqualTo(Refusal.WRONG_NODE));
        assertThatThrownBy(() -> ScenarioEngine.commit(fixture, "start", "start", "teleport"))
                .isInstanceOfSatisfying(ScenarioEngine.EngineException.class,
                        e -> assertThat(e.refusal()).isEqualTo(Refusal.UNKNOWN_CHOICE));
        assertThatThrownBy(() -> ScenarioEngine.commit(fixture, "reconnected", "reconnected", "x"))
                .isInstanceOfSatisfying(ScenarioEngine.EngineException.class,
                        e -> assertThat(e.refusal()).isEqualTo(Refusal.FINISHED));
    }

    @Test
    void debriefSortsTagsAndPicksThePracticeActionFirst() {
        var d = ScenarioEngine.debrief(fixture, List.of(
                new TraceStep("start", "call", "t"), new TraceStep("second", "plan", "t")), "reconnected");
        assertThat(d.strongChoices()).isEmpty();
        assertThat(d.worthPracticing()).containsExactly("Worth checking your out-of-area contact is current.");
        assertThat(d.nextStep().action()).isEqualTo(ReadinessAction.OPEN_EMERGENCY_CONTACTS);
        assertThat(d.tags()).containsExactly("needs_contact_review");
        // BE-02: the one improvement named is the line behind the next step.
        assertThat(d.oneThing()).isEqualTo("Worth checking your out-of-area contact is current.");
    }

    @Test
    void decisionCountsFollowTheLongestPathAhead() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version tornado = catalog.exact("tornado-warning-shelter-decision", 2).orElseThrow();
        // warning_issued → storm_closer → in_shelter → warning_ends is the longest path: 4 decisions.
        assertThat(ScenarioEngine.longestDecisionsFrom(tornado, ScenarioEngine.startNode(tornado))).isEqualTo(4);
        assertThat(ScenarioEngine.longestDecisionsFrom(tornado, "warning_ends")).isEqualTo(1);
        assertThat(ScenarioEngine.longestDecisionsFrom(tornado, "warning_rehearsed")).isZero();
        assertThat(ScenarioEngine.longestDecisionsFrom(tornado, "nope")).isZero();
    }

    @Test
    void debriefFallsBackToTheOutcomeDefault() {
        var d = ScenarioEngine.debrief(fixture, List.of(
                new TraceStep("start", "text", "t"), new TraceStep("second", "improvise", "t")), "partial");
        assertThat(d.strongChoices()).containsExactly("You sent a short, clear status.");
        assertThat(d.worthPracticing()).isEmpty();
        assertThat(d.nextStep().action()).isEqualTo(ReadinessAction.OPEN_EVACUATION_PLAN);
        assertThat(d.oneThing()).isNull();
    }

    /**
     * Walks EVERY path through the shipped Communications Outage content: each
     * ends at an outcome, and each debrief offers an allowed next step with copy.
     */
    @ParameterizedTest(name = "v{0}")
    @ValueSource(ints = {1, 2})
    void everyPathThroughShippedContentEndsWithAUsableDebrief(int version) {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v = catalog.exact("comms-outage-family-reconnect", version).orElseThrow();
        List<List<TraceStep>> paths = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        walk(v, ScenarioEngine.startNode(v), new ArrayList<>(), paths, ends);

        assertThat(paths).hasSizeGreaterThan(10);
        for (int i = 0; i < paths.size(); i++) {
            var d = ScenarioEngine.debrief(v, paths.get(i), ends.get(i));
            assertThat(PracticeActions.allowed(d.nextStep().action())).isTrue();
            assertThat(PracticeActions.describe(d.nextStep().action())).isNotNull();
            assertThat(paths.get(i)).hasSizeBetween(4, 5); // open-ended progress: lengths differ by branch
            // BE-02: exactly one improvement is named whenever there is any, and it is one of them.
            if (d.worthPracticing().isEmpty()) assertThat(d.oneThing()).isNull();
            else assertThat(d.worthPracticing()).contains(d.oneThing());
        }
        assertThat(ends).contains("reconnected", "reconnected_check_sources");
    }

    @Test
    void everyPathThroughNextPriorityDraftsEndsWithAUsableDebrief() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        for (String key : List.of("flash-flood-turn-around-route", "earthquake-first-five-minutes")) {
            Version v = catalog.exact(key, 1).orElseThrow();
            List<List<TraceStep>> paths = new ArrayList<>();
            List<String> ends = new ArrayList<>();
            walk(v, ScenarioEngine.startNode(v), new ArrayList<>(), paths, ends);

            assertThat(paths).as(key).hasSize(27);
            assertThat(paths).as(key).allSatisfy(path -> assertThat(path).hasSize(3));
            assertThat(ends).as(key).containsOnly(
                    key.equals("flash-flood-turn-around-route") ? "route_rehearsed" : "first_minutes_rehearsed");
            for (int i = 0; i < paths.size(); i++) {
                var d = ScenarioEngine.debrief(v, paths.get(i), ends.get(i));
                assertThat(PracticeActions.allowed(d.nextStep().action())).as(key).isTrue();
                assertThat(PracticeActions.describe(d.nextStep().action())).as(key).isNotNull();
            }
        }
    }

    /**
     * No action params in shipped content: {@code /emergency-contacts?intent=…}
     * CREATES an empty contact group server-side when a household has none
     * (EmergencyContacts.js intent effect), which would make the debrief's
     * "Nothing changes unless you save it" untrue. The plain route writes nothing.
     */
    @ParameterizedTest(name = "v{0}")
    @ValueSource(ints = {1, 2})
    void shippedNextStepsOpenEditorsWithoutSideEffectParams(int version) {
        Version v = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION)
                .exact("comms-outage-family-reconnect", version).orElseThrow();
        v.body().tags().values().forEach(t -> assertThat(t.params()).isNullOrEmpty());
        v.body().scenario().outcomes().forEach(o -> assertThat(o.defaultParams()).isNullOrEmpty());
        System.out.println("[practice] comms-outage-family-reconnect v" + version + " contentHash = " + v.contentHash());
    }

    private static void walk(Version v, String at, List<TraceStep> trace, List<List<TraceStep>> paths, List<String> ends) {
        Node node = ScenarioEngine.node(v, at).orElse(null);
        if (node == null) {
            paths.add(List.copyOf(trace));
            ends.add(at);
            return;
        }
        for (Choice c : node.choices()) {
            var commit = ScenarioEngine.commit(v, at, at, c.key());
            trace.add(new TraceStep(at, c.key(), "t"));
            walk(v, commit.nextKey(), trace, paths, ends);
            trace.remove(trace.size() - 1);
        }
    }

    @Test
    void everyPathThroughTheTornadoDraftEndsWithAUsableDebrief() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v = catalog.exact("tornado-warning-shelter-decision", 1).orElseThrow();
        List<List<TraceStep>> paths = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        walk(v, ScenarioEngine.startNode(v), new ArrayList<>(), paths, ends);
        assertThat(paths).hasSize(27).allSatisfy(path -> assertThat(path).hasSize(3));
        assertThat(ends).containsOnly("warning_rehearsed");
        for (int i = 0; i < paths.size(); i++) {
            var d = ScenarioEngine.debrief(v, paths.get(i), ends.get(i));
            assertThat(PracticeActions.allowed(d.nextStep().action())).isTrue();
            assertThat(PracticeActions.describe(d.nextStep().action())).isNotNull();
        }
    }
}
