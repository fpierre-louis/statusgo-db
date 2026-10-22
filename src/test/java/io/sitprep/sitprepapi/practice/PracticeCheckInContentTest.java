package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.M;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;

/** Practice redesign FR-7: the CHECKIN content kind, its validator rules and the four DRAFT pilots. */
class PracticeCheckInContentTest {

    private static final PracticeCatalog SHIPPED = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
    private static final List<String> PILOT_SKILLS = List.of(
            "tornado-know-where-to-go", "tornado-protect-yourselves", "tornado-after-the-warning",
            "earthquake-protect-in-place");

    private static String keyFor(String skill) { return "checkin-" + skill; }

    private static ObjectNode pilot(String skill) throws Exception {
        try (InputStream in = PracticeCheckInContentTest.class
                .getResourceAsStream("/practice/content/" + keyFor(skill) + "/v1.json")) {
            return (ObjectNode) M.readTree(in);
        }
    }

    private static ObjectNode checkin(ObjectNode root) {
        return (ObjectNode) root.get("content").get("checkin");
    }

    private static List<String> problems(ObjectNode root) {
        return PracticeContentValidator.validate(version(root));
    }

    // -- the shipped pilots --------------------------------------------------

    @Test
    void fourDraftPilotsLoadValidateAndResolveTheirSkill() {
        assertThat(SHIPPED.rejected()).isEmpty();
        assertThat(SHIPPED.keys(PracticeKind.CHECKIN)).containsExactlyInAnyOrder(
                PILOT_SKILLS.stream().map(PracticeCheckInContentTest::keyFor).toArray(String[]::new));
        for (String skill : PILOT_SKILLS) {
            Version v = SHIPPED.versions(keyFor(skill)).get(0);
            assertThat(v.publishState()).as(skill).isEqualTo(PublishState.DRAFT);
            assertThat(v.body().checkin().skill()).isEqualTo(skill);
            assertThat(SHIPPED.latestStartable(v.key(), false)).as("production serves no DRAFT").isEmpty();
        }
        assertThat(PracticeRegistryCheck.problems(SHIPPED.all(), PracticeRegistryCheck.contractDrillIds())).isEmpty();
    }

    @Test
    void everyPilotHasExactlyOneCorrectChoiceAndItsTextComesFromTheSkill() {
        for (String skill : PILOT_SKILLS) {
            PracticeContent.CheckIn c = SHIPPED.versions(keyFor(skill)).get(0).body().checkin();
            assertThat(c.choices()).filteredOn(ch -> Boolean.TRUE.equals(ch.correct())).hasSize(1);
            assertThat(c.objective()).isTrue();
            // The remember line and the sources are the skill's own reviewed text, not new facts.
            PracticeContent.Skill s = SHIPPED.versions(skill).get(0).body().skill();
            assertThat(SHIPPED.versions(keyFor(skill)).get(0).body().sources())
                    .isEqualTo(SHIPPED.versions(skill).get(0).body().sources());
            assertThat(c.remember()).isNotBlank();
            assertThat(s.action()).isNotBlank();
        }
    }

    @Test
    void existingReviewedContentIsUntouchedByTheNewKind() {
        // The hash is over the file's JSON, so an additive field cannot move any existing hash.
        PracticeContent.Version v1 = SHIPPED.exact("comms-outage-family-reconnect", 1).orElseThrow();
        assertThat(v1.contentHash()).isEqualTo(v1.file().lifecycle().safetyReview().reviewedContentHash());
    }

    // -- validator rules -----------------------------------------------------

    @Test
    void aPilotIsValidAsShipped() throws Exception {
        for (String skill : PILOT_SKILLS) assertThat(problems(pilot(skill))).as(skill).isEmpty();
    }

    @Test
    void exactlyOneChoiceMustBeCorrect() throws Exception {
        ObjectNode none = pilot("tornado-know-where-to-go");
        for (var ch : checkin(none).withArray("choices")) ((ObjectNode) ch).put("correct", false);
        assertThat(problems(none)).anyMatch(p -> p.contains("exactly one correct choice (found 0)"));

        ObjectNode two = pilot("tornado-know-where-to-go");
        for (var ch : checkin(two).withArray("choices")) ((ObjectNode) ch).put("correct", true);
        assertThat(problems(two)).anyMatch(p -> p.contains("exactly one correct choice (found 3)"));
    }

    @Test
    void twoToThreeChoicesWithUniqueKeys() throws Exception {
        ObjectNode one = pilot("tornado-know-where-to-go");
        ArrayNode only = M.createArrayNode();
        only.add(checkin(one).withArray("choices").get(0));
        checkin(one).set("choices", only);
        assertThat(problems(one)).anyMatch(p -> p.contains("needs 2-3 choices"));

        ObjectNode four = pilot("tornado-know-where-to-go");
        ArrayNode many = M.createArrayNode();
        for (int i = 0; i < 4; i++) many.addObject().put("key", "c" + i).put("label", "Choice " + i).put("correct", i == 0);
        checkin(four).set("choices", many);
        assertThat(problems(four)).anyMatch(p -> p.contains("needs 2-3 choices"));

        ObjectNode dup = pilot("tornado-know-where-to-go");
        ((ObjectNode) checkin(dup).withArray("choices").get(1)).put("key", "interior");
        assertThat(problems(dup)).anyMatch(p -> p.contains("duplicate checkin choice key interior"));
    }

    @Test
    void lengthLimitsAndRequiredFields() throws Exception {
        ObjectNode root = pilot("tornado-know-where-to-go");
        checkin(root).put("prompt", "x".repeat(161));
        checkin(root).put("remember", "x".repeat(161));
        checkin(root).put("fullerExplanation", "x".repeat(281));
        assertThat(problems(root))
                .anyMatch(p -> p.contains("checkin.prompt is longer than 160"))
                .anyMatch(p -> p.contains("checkin.remember is longer than 160"))
                .anyMatch(p -> p.contains("checkin.fullerExplanation is longer than 280"));

        ObjectNode ok = pilot("tornado-know-where-to-go");
        checkin(ok).put("prompt", "x".repeat(160));
        checkin(ok).put("remember", "x".repeat(160));
        checkin(ok).put("fullerExplanation", "x".repeat(280));
        assertThat(problems(ok)).isEmpty();

        ObjectNode missing = pilot("tornado-know-where-to-go");
        checkin(missing).remove("objective");
        checkin(missing).remove("skill");
        checkin(missing).remove("format");
        assertThat(problems(missing))
                .anyMatch(p -> p.contains("checkin.objective is required"))
                .anyMatch(p -> p.contains("checkin.skill must be a skill key"))
                .anyMatch(p -> p.contains("checkin.format is required"));
    }

    @Test
    void onlyChoiceFormatExistsInThisUnit() throws Exception {
        ObjectNode root = pilot("tornado-know-where-to-go");
        checkin(root).put("format", "SORT");
        // The strict parser rejects an unknown enum value before the validator sees it.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> version(root));
    }

    @Test
    void aCheckInNeedsItsBodyAndCarriesNoOtherKindsBody() throws Exception {
        ObjectNode noBody = pilot("tornado-know-where-to-go");
        ((ObjectNode) noBody.get("content")).remove("checkin");
        assertThat(problems(noBody)).anyMatch(p -> p.contains("a CHECKIN needs content.checkin"));

        ObjectNode withTags = pilot("tornado-know-where-to-go");
        ((ObjectNode) withTags.get("content")).set("tags", M.createObjectNode().set("protected_from_debris",
                M.createObjectNode().put("text", "x")));
        assertThat(problems(withTags)).anyMatch(p -> p.contains("no debrief tags"));

        ObjectNode withMinutes = pilot("tornado-know-where-to-go");
        ((ObjectNode) withMinutes.get("content")).put("estimatedMinutes", 5);
        assertThat(problems(withMinutes)).anyMatch(p -> p.contains("a CHECKIN has no estimatedMinutes"));

        ObjectNode scenario = PracticeFixtures.scenario("some-scenario", 1);
        ((ObjectNode) scenario.get("content")).set("checkin", checkin(pilot("tornado-know-where-to-go")).deepCopy());
        assertThat(problems(scenario)).anyMatch(p -> p.contains("only a CHECKIN carries content.checkin"));
    }

    @Test
    void checkInCopyIsHeldToTheSameCopyRules() throws Exception {
        ObjectNode root = pilot("tornado-know-where-to-go");
        ((ObjectNode) checkin(root).withArray("choices").get(0)).put("label", "Your score is perfect");
        checkin(root).put("fullerExplanation", "That was the wrong answer.");
        assertThat(problems(root)).anyMatch(p -> p.contains("prohibited practice copy"));
    }

    @Test
    void aCheckInOfAnUnknownSkillIsABrokenReference() throws Exception {
        ObjectNode root = pilot("tornado-know-where-to-go");
        checkin(root).put("skill", "no-such-skill");
        Version orphan = version(root);
        // Per-file rules pass (a key is a key); the registry check is what resolves it.
        assertThat(PracticeContentValidator.validate(orphan)).isEmpty();
        List<Version> all = new java.util.ArrayList<>(SHIPPED.all());
        all.removeIf(v -> v.key().equals(orphan.key()));
        all.add(orphan);
        assertThat(PracticeRegistryCheck.problems(all, PracticeRegistryCheck.contractDrillIds()))
                .anyMatch(p -> p.contains("check-in skill no-such-skill is not a skill"));
    }
}
