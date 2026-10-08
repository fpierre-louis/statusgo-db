package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Choice;
import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.ScenarioEngine.Refusal;
import io.sitprep.sitprepapi.practice.ScenarioRun.TraceStep;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void debriefFallsBackToTheOutcomeDefault() {
        var d = ScenarioEngine.debrief(fixture, List.of(
                new TraceStep("start", "text", "t"), new TraceStep("second", "improvise", "t")), "partial");
        assertThat(d.strongChoices()).containsExactly("You sent a short, clear status.");
        assertThat(d.worthPracticing()).isEmpty();
        assertThat(d.nextStep().action()).isEqualTo(ReadinessAction.OPEN_EVACUATION_PLAN);
    }

    /**
     * Walks EVERY path through the shipped Communications Outage content: each
     * ends at an outcome, and each debrief offers an allowed next step with copy.
     */
    @Test
    void everyPathThroughShippedContentEndsWithAUsableDebrief() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v = catalog.exact("comms-outage-family-reconnect", 1).orElseThrow();
        List<List<TraceStep>> paths = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        walk(v, ScenarioEngine.startNode(v), new ArrayList<>(), paths, ends);

        assertThat(paths).hasSizeGreaterThan(10);
        for (int i = 0; i < paths.size(); i++) {
            var d = ScenarioEngine.debrief(v, paths.get(i), ends.get(i));
            assertThat(PracticeActions.allowed(d.nextStep().action())).isTrue();
            assertThat(PracticeActions.describe(d.nextStep().action())).isNotNull();
            assertThat(paths.get(i)).hasSizeBetween(4, 5); // open-ended progress: lengths differ by branch
        }
        assertThat(ends).contains("reconnected", "reconnected_check_sources");
    }

    /**
     * No action params in shipped content: {@code /emergency-contacts?intent=…}
     * CREATES an empty contact group server-side when a household has none
     * (EmergencyContacts.js intent effect), which would make the debrief's
     * "Nothing changes unless you save it" untrue. The plain route writes nothing.
     */
    @Test
    void shippedNextStepsOpenEditorsWithoutSideEffectParams() {
        Version v = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION)
                .exact("comms-outage-family-reconnect", 1).orElseThrow();
        v.body().tags().values().forEach(t -> assertThat(t.params()).isNullOrEmpty());
        v.body().scenario().outcomes().forEach(o -> assertThat(o.defaultParams()).isNullOrEmpty());
        System.out.println("[practice] comms-outage-family-reconnect v1 contentHash = " + v.contentHash());
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
}
