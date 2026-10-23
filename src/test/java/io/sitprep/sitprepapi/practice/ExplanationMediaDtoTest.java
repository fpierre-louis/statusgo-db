package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CommittedDto;
import io.sitprep.sitprepapi.practice.ScenarioRun.TraceStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.choiceAt;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.content;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;

/** EXEC-M2: the explanation exists only on the committed step, identical for every choice. */
class ExplanationMediaDtoTest {

    /** A real REALISM still from the shipped manifest. */
    static final String SHA = "sha256:a5eef042d174f4cf80ae26b95252c494e5ac0ef0737213822f1ab4790ca43a73";

    private static Version withExplanation() {
        ObjectNode root = scenario("k", 1);
        root.put("schema", 2);
        content(root).put("mediaStyle", "REALISM");
        var n = (ObjectNode) content(root).get("scenario").get("nodes").get(0);
        n.putObject("explanationMedia").put("slotKey", "k." + n.get("key").asText() + ".explanation")
                .put("kind", "STILL").put("assetSha256", SHA).put("alt", "Why a short text gets through.");
        return version(root);
    }

    @Test
    void theFixtureIsValidAgainstTheShippedManifest() {
        assertThat(PracticeContentValidator.validate(withExplanation())).isEmpty();
    }

    @Test
    void theNodeDtoNeverCarriesTheExplanation() {
        Version v = withExplanation();
        var node = v.body().scenario().nodes().get(0);
        assertThat(node.explanationMedia()).isNotNull();
        ScenarioDtos.NodeDto dto = ScenarioService.nodeDto(node);
        assertThat(dto.media()).isNull(); // the scene is a different slot and this node has none
        assertThat(java.util.Arrays.stream(ScenarioDtos.NodeDto.class.getRecordComponents()).map(c -> c.getName()))
                .noneMatch(name -> name.toLowerCase().contains("explanation"));
    }

    @Test
    void theCommittedDtoCarriesTheSameExplanationForEveryChoice() {
        Version v = withExplanation();
        var node = v.body().scenario().nodes().get(0);
        assertThat(node.choices().size()).isGreaterThan(1);
        CommittedDto first = null;
        for (var c : node.choices()) {
            CommittedDto d = ScenarioService.lastCommit(v, List.of(new TraceStep(node.key(), c.key(), "t")));
            assertThat(d.explanationMedia()).isNotNull();
            assertThat(d.explanationMedia().assetSha256()).isEqualTo(SHA);
            assertThat(d.explanationMedia().alt()).isEqualTo("Why a short text gets through.");
            if (first == null) first = d;
            assertThat(d.explanationMedia()).isEqualTo(first.explanationMedia());
        }
    }

    @Test
    void aNodeWithoutOneCommitsWithNull() {
        Version v = version(scenario("k", 1));
        var node = v.body().scenario().nodes().get(0);
        CommittedDto d = ScenarioService.lastCommit(v, List.of(new TraceStep(node.key(), node.choices().get(0).key(), "t")));
        assertThat(d.explanationMedia()).isNull();
    }
}
