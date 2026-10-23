package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.Test;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.choiceAt;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.content;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;

/** EXEC-M3: ChoiceDto.thumb is a manifest-resolved MediaDto on every choice of a node, or null on all of them. */
class ChoiceThumbDtoTest {

    /** MEDIA-016, the shipped ILLUSTRATION 4:3 still (1254x941). */
    static final String SHA = "sha256:3cbf96be93c67f4e9c466159e79ed94b0160997e031df3d7806d2f142f6b08cf";

    private static Version withThumbs() {
        ObjectNode root = scenario("k", 1);
        root.put("schema", 2);
        content(root).put("mediaStyle", "ILLUSTRATION");
        var n = (ObjectNode) content(root).get("scenario").get("nodes").get(0);
        for (var c : n.get("choices")) {
            ((ObjectNode) c).putObject("thumb")
                    .put("slotKey", "k." + n.get("key").asText() + "." + c.get("key").asText() + ".thumb")
                    .put("kind", "STILL").put("assetSha256", SHA).put("alt", "A calm street under a stormy sky.");
        }
        return version(root);
    }

    @Test
    void theFixtureIsValidAgainstTheShippedManifest() {
        assertThat(PracticeContentValidator.validate(withThumbs())).isEmpty();
    }

    @Test
    void everyChoiceDtoCarriesItsThumbResolvedFromTheManifest() {
        var node = withThumbs().body().scenario().nodes().get(0);
        var dto = ScenarioService.nodeDto(node);
        assertThat(dto.choices()).hasSize(node.choices().size()).allSatisfy(c -> {
            assertThat(c.thumb()).isNotNull();
            assertThat(c.thumb().kind()).isEqualTo("STILL");
            assertThat(c.thumb().assetSha256()).isEqualTo(SHA);
            assertThat(c.thumb().url()).contains("/images/practice/tornado-intro-3cbf96be");
            assertThat(c.thumb().width()).isEqualTo(1254);
            assertThat(c.thumb().height()).isEqualTo(941);
            assertThat(c.thumb().alt()).isEqualTo("A calm street under a stormy sky.");
        });
        // Same image shape for every option; only the alt/slot text could differ in real content.
        assertThat(dto.choices().stream().map(c -> c.thumb().width()).distinct()).hasSize(1);
    }

    @Test
    void aNodeWithoutThumbsServesNullOnEveryChoice() {
        Version v = version(scenario("k", 1));
        var dto = ScenarioService.nodeDto(v.body().scenario().nodes().get(0));
        assertThat(dto.choices()).isNotEmpty().allSatisfy(c -> assertThat(c.thumb()).isNull());
    }

    @Test
    void theCommittedStepAndTheOtherChoicesNeverCarryAThumb() {
        // Thumbs are pre-choice decoration only; the post-commit DTOs have no thumb component.
        assertThat(java.util.Arrays.stream(ScenarioDtos.CommittedDto.class.getRecordComponents()).map(c -> c.getName()))
                .noneMatch(n -> n.toLowerCase().contains("thumb"));
        assertThat(java.util.Arrays.stream(ScenarioDtos.OtherChoiceDto.class.getRecordComponents()).map(c -> c.getName()))
                .noneMatch(n -> n.toLowerCase().contains("thumb"));
    }

    @Test
    void theIntroMediaIsResolvedOnTheDetailSideFromTheScenario() {
        ObjectNode root = scenario("k", 1);
        root.put("schema", 2);
        content(root).put("mediaStyle", "ILLUSTRATION");
        ((ObjectNode) content(root).get("scenario")).putObject("introMedia").put("slotKey", "k.intro")
                .put("kind", "STILL").put("assetSha256", SHA).put("alt", "Storm clouds build.");
        Version v = version(root);
        assertThat(PracticeContentValidator.validate(v)).isEmpty();
        var m = ScenarioService.mediaDto(v.body().scenario().introMedia());
        assertThat(m).isNotNull();
        assertThat(m.assetSha256()).isEqualTo(SHA);
        assertThat(ScenarioService.mediaDto(version(scenario("k", 1)).body().scenario().introMedia())).isNull();
    }
}
