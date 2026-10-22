package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.M;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;

/** Practice redesign BE-04: the skill registry and the cross-repo contract. */
class PracticeRegistryTest {

    private static final PracticeCatalog SHIPPED = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
    private static final List<String> PILOT = List.of(
            "earthquake-protect-in-place", "tornado-after-the-warning", "tornado-know-where-to-go",
            "tornado-protect-yourselves");

    @Test
    void theShippedRegistryIsWhole() {
        assertThat(SHIPPED.rejected()).isEmpty();
        assertThat(SHIPPED.keys(PracticeKind.SKILL)).containsExactlyElementsOf(PILOT);
        assertThat(PracticeRegistryCheck.problems(SHIPPED.all(), PracticeRegistryCheck.contractDrillIds())).isEmpty();
    }

    @Test
    void pilotSkillsAreDraftsSoProductionNeverServesThem() {
        for (String key : PILOT) {
            assertThat(SHIPPED.versions(key)).allSatisfy(v -> assertThat(v.publishState()).isEqualTo(PublishState.DRAFT));
            assertThat(SHIPPED.latestStartable(key, false)).isEmpty();
        }
    }

    @Test
    void theContractListsTheDrillsReviewedScenariosPointAt() {
        assertThat(PracticeRegistryCheck.contractDrillIds())
                .hasSize(54)
                .contains("tornado-safe-room-speed-round", "earthquake-drop-cover-relay", "flood-underpass-scan",
                        "pet-buddy-and-place", "exit-drill");
    }

    @Test
    void aRenamedDrillIsCaughtForScenariosAndSkillsAlike() {
        List<String> problems = PracticeRegistryCheck.problems(SHIPPED.all(), Set.of());
        assertThat(problems)
                .anyMatch(p -> p.contains("OPEN_DRILL drill earthquake-drop-cover-relay is not in the Practice contract"))
                .anyMatch(p -> p.contains("tornado-know-where-to-go v1: drill tornado-safe-room-speed-round is not in the Practice contract"));
    }

    @Test
    void aMissingScenarioNodeOrSkillIsCaught() {
        List<Version> withoutTornadoScenarioOrQuakeSkill = SHIPPED.all().stream()
                .filter(v -> !v.key().equals("tornado-warning-shelter-decision") && !v.key().equals("earthquake-protect-in-place"))
                .toList();
        assertThat(PracticeRegistryCheck.problems(withoutTornadoScenarioOrQuakeSkill, PracticeRegistryCheck.contractDrillIds()))
                .anyMatch(p -> p.contains("scenario node tornado-warning-shelter-decision#warning_issued does not exist"))
                .anyMatch(p -> p.contains("confusedWith earthquake-protect-in-place is not a skill"));
    }

    // -- per-file skill rules ------------------------------------------------

    private static ObjectNode pilot(String key) throws Exception {
        try (InputStream in = PracticeRegistryTest.class.getResourceAsStream("/practice/content/" + key + "/v1.json")) {
            return (ObjectNode) M.readTree(in);
        }
    }

    private static ObjectNode skill(ObjectNode root) {
        return (ObjectNode) root.get("content").get("skill");
    }

    @Test
    void aRequiredPhysicalRehearsalNeedsADrill() throws Exception {
        ObjectNode root = pilot("tornado-know-where-to-go");
        skill(root).set("drills", M.createArrayNode());
        assertThat(PracticeContentValidator.validate(version(root)))
                .anyMatch(p -> p.contains("REQUIRED needs at least one drill"));
    }

    @Test
    void checkInPromptsAreTwoToFour() throws Exception {
        ObjectNode root = pilot("tornado-after-the-warning");
        ArrayNode one = M.createArrayNode().add("Only one phrasing?");
        skill(root).set("checkInPrompts", one);
        assertThat(PracticeContentValidator.validate(version(root)))
                .anyMatch(p -> p.contains("checkInPrompts needs 2-4"));
    }

    @Test
    void aSkillCarriesNoDebriefTagsAndOnlyASkillCarriesASkillBody() throws Exception {
        ObjectNode root = pilot("tornado-protect-yourselves");
        ((ObjectNode) root.get("content")).set("tags", M.createObjectNode().set("protected_from_debris",
                M.createObjectNode().put("text", "x")));
        assertThat(PracticeContentValidator.validate(version(root))).anyMatch(p -> p.contains("no debrief tags"));

        ObjectNode scenario = PracticeFixtures.scenario("some-scenario", 1);
        ((ObjectNode) scenario.get("content")).set("skill", skill(pilot("tornado-protect-yourselves")).deepCopy());
        assertThat(PracticeContentValidator.validate(version(scenario))).anyMatch(p -> p.contains("only a SKILL carries content.skill"));
    }

    @Test
    void skillCopyIsHeldToTheSameCopyRules() throws Exception {
        ObjectNode root = pilot("tornado-after-the-warning");
        skill(root).put("why", "You failed the test if you went outside.");
        assertThat(PracticeContentValidator.validate(version(root))).anyMatch(p -> p.contains("prohibited practice copy"));
    }
}
