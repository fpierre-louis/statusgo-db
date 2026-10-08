package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.choiceAt;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.content;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.family;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.node;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.versionAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each failure class in the gameplan's "Catalog Validation" list, one test apiece. */
class PracticeContentValidatorTest {

    private static List<String> errors(ObjectNode root) {
        return PracticeContentValidator.validate(version(root));
    }

    private static void assertRejected(ObjectNode root, String fragment) {
        assertThat(errors(root)).anySatisfy(e -> assertThat(e).contains(fragment));
    }

    // -- valid baselines ------------------------------------------------

    @Test
    void validDraftScenarioPasses() {
        assertThat(errors(scenario("comms-test", 1))).isEmpty();
    }

    @Test
    void validPublishedAndRetiredScenarioPass() {
        assertThat(errors(approve(scenario("comms-test", 1), PublishState.SAFETY_REVIEWED))).isEmpty();
        assertThat(errors(approve(scenario("comms-test", 1), PublishState.PUBLISHED))).isEmpty();
        assertThat(errors(approve(scenario("comms-test", 1), PublishState.RETIRED))).isEmpty();
    }

    @Test
    void validDraftFamilyActivityPasses() {
        assertThat(errors(family("kit-test", 1))).isEmpty();
    }

    // -- metadata -------------------------------------------------------

    @Test
    void missingSources() {
        ObjectNode r = scenario("k", 1);
        content(r).putArray("sources");
        assertRejected(r, "at least one official source");
    }

    @Test
    void nonHttpsSource() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) content(r).get("sources").get(0)).put("url", "http://www.ready.gov/plan");
        assertRejected(r, "https://");
    }

    @Test
    void malformedKeyAndVersion() {
        assertRejected(scenario("Comms_Outage", 1), "lower-kebab-case");
        assertRejected(scenario("k", 0), "positive integer");
    }

    @Test
    void fileMustLiveAtItsKeyAndVersion() {
        List<String> e = PracticeContentValidator.validate(
                versionAt(scenario("k", 2), "practice/content/k/v1.json"));
        assertThat(e).anySatisfy(s -> assertThat(s).contains("must live at practice/content/k/v2.json"));
    }

    @Test
    void unknownPropertyIsAParseError() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) choiceAt(r, 0, 0)).put("feeback", "typo");
        assertThatThrownBy(() -> PracticeContentParser.parse(
                new ByteArrayInputStream(PracticeFixtures.M.writeValueAsBytes(r)), "x"))
                .hasMessageContaining("feeback");
    }

    // -- lifecycle ------------------------------------------------------

    @Test
    void publishedWithoutApprovedReview() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) r.get("lifecycle")).put("publishState", "PUBLISHED").put("publishedAt", "2026-10-08");
        assertRejected(r, "needs safetyReview.status APPROVED");
        assertRejected(r, "needs safetyReview.reviewedAt");
        assertRejected(r, "needs safetyReview.reviewedBy");
    }

    @Test
    void editingReviewedContentIsCaught() {
        ObjectNode r = approve(scenario("k", 1), PublishState.PUBLISHED);
        node(r, 0).put("prompt", "A quietly edited prompt");
        assertRejected(r, "content changed since its safety review");
    }

    @Test
    void publishedNeedsDateAndRetiredNeedsDate() {
        ObjectNode pub = approve(scenario("k", 1), PublishState.PUBLISHED);
        ((ObjectNode) pub.get("lifecycle")).remove("publishedAt");
        assertRejected(pub, "PUBLISHED needs lifecycle.publishedAt");

        ObjectNode ret = approve(scenario("k", 1), PublishState.RETIRED);
        ((ObjectNode) ret.get("lifecycle")).remove("retiredAt");
        assertRejected(ret, "RETIRED needs lifecycle.retiredAt");

        ObjectNode early = approve(scenario("k", 1), PublishState.PUBLISHED);
        ((ObjectNode) early.get("lifecycle")).put("retiredAt", "2026-10-09");
        assertRejected(early, "retiredAt is only valid on RETIRED");
    }

    // -- debrief tags + actions ----------------------------------------

    @Test
    void unknownTagOutsideTheClosedVocabulary() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) content(r).get("tags")).putObject("bravery_points").put("text", "x");
        assertRejected(r, "unknown debrief tag 'bravery_points'");
    }

    @Test
    void practiceTagNeedsAnAction() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) content(r).get("tags").get("needs_contact_review")).remove("action");
        assertRejected(r, "needs an action");
    }

    @Test
    void emergencySurfacesAreNeverANextStep() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) content(r).get("tags").get("needs_contact_review")).put("action", "OPEN_CHECK_IN");
        assertRejected(r, "is not a preparedness editor");

        ObjectNode o = scenario("k", 1);
        ((ObjectNode) content(o).get("scenario").get("outcomes").get(0)).put("defaultAction", "OPEN_ACTIVE_SITUATION");
        assertRejected(o, "needs a defaultAction from PracticeActions.ALLOWED");
    }

    @Test
    void choiceTagWithoutCopy() {
        ObjectNode r = scenario("k", 1);
        ((ArrayNode) choiceAt(r, 0, 0).get("tags")).add("used_household_plan");
        assertRejected(r, "uses tag 'used_household_plan' with no debrief copy");
    }

    @Test
    void tagWithCopyButNoChoiceEarnsIt() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) content(r).get("tags")).putObject("used_household_plan").put("text", "Plan used.");
        assertRejected(r, "has copy but no choice earns it");
    }

    // -- graph ----------------------------------------------------------

    @Test
    void choicePointingToMissingNode() {
        ObjectNode r = scenario("k", 1);
        choiceAt(r, 1, 0).put("next", "nowhere");
        assertRejected(r, "points to missing node/outcome 'nowhere'");
    }

    @Test
    void startNodeMustExist() {
        ObjectNode r = scenario("k", 1);
        ((ObjectNode) content(r).get("scenario")).put("startNode", "ghost");
        assertRejected(r, "startNode must name a node");
    }

    @Test
    void orphanNodeAndUnreachableOutcome() {
        ObjectNode r = scenario("k", 1);
        // Both start choices skip "second" and land on an outcome.
        choiceAt(r, 0, 0).put("next", "reconnected");
        choiceAt(r, 0, 1).put("next", "reconnected");
        assertRejected(r, "orphan node second");
        assertRejected(r, "unreachable outcome partial");
    }

    @Test
    void cycleIsRejected() {
        ObjectNode r = scenario("k", 1);
        choiceAt(r, 1, 1).put("next", "start");
        assertRejected(r, "has a cycle");
    }

    @Test
    void duplicateNodeAndChoiceKeys() {
        ObjectNode r = scenario("k", 1);
        node(r, 1).put("key", "start");
        assertRejected(r, "duplicate node key start");

        ObjectNode c = scenario("k", 1);
        choiceAt(c, 0, 1).put("key", "text");
        assertRejected(c, "duplicate choice key text");
    }

    @Test
    void choiceCountBounds() {
        ObjectNode r = scenario("k", 1);
        ((ArrayNode) node(r, 1).get("choices")).remove(1);
        assertRejected(r, "needs 2-4 choices");
    }

    // -- copy guards ----------------------------------------------------

    @Test
    void prohibitedCopyInEveryKind() {
        ObjectNode died = scenario("k", 1);
        choiceAt(died, 0, 1).put("feedback", "In real life, someone could have died.");
        assertRejected(died, "prohibited practice copy");

        ObjectNode failed = scenario("k", 1);
        ((ObjectNode) content(failed).get("scenario").get("outcomes").get(1)).put("title", "You failed to reconnect");
        assertRejected(failed, "prohibited practice copy");

        ObjectNode score = scenario("k", 1);
        content(score).put("summary", "Earn 10 points for each good choice.");
        assertRejected(score, "prohibited practice copy");
    }

    @Test
    void familyCopyGuardsAreStricter() {
        ObjectNode resp = family("kit", 1);
        content(resp).put("facilitation", "Remind kids it's your job to keep everyone safe.");
        assertRejected(resp, "prohibited practice copy");

        ObjectNode injury = family("kit", 1);
        content(injury).put("summary", "Pack bandages in case someone is injured.");
        assertRejected(injury, "prohibited practice copy");

        ObjectNode activityText = family("kit", 1);
        ((ObjectNode) content(activityText).get("activity")).put("intro", "Imagine you are home alone in a storm.");
        assertRejected(activityText, "prohibited practice copy");

        // The same injury word is not a family-only rule for adult scenarios.
        ObjectNode adult = scenario("k", 1);
        content(adult).put("objective", "Check whether anyone is injured before you leave.");
        assertThat(errors(adult)).isEmpty();
    }

    // -- kind rules -----------------------------------------------------

    @Test
    void familyNeedsFacilitationAndKnownAgeGuidance() {
        ObjectNode r = family("kit", 1);
        content(r).remove("facilitation");
        assertRejected(r, "needs adult facilitation copy");

        ObjectNode age = family("kit", 1);
        ((ArrayNode) content(age).get("ageGuidance")).removeAll().add("Ages 7");
        assertRejected(age, "unknown ageGuidance 'Ages 7'");
    }

    @Test
    void ageGuidanceIsNotForAdultScenarios() {
        ObjectNode r = scenario("k", 1);
        content(r).putArray("ageGuidance").add("Family / all ages");
        assertRejected(r, "not for adult scenarios");
    }

    @Test
    void scenarioNeedsAGraph() {
        ObjectNode r = scenario("k", 1);
        content(r).remove("scenario");
        assertRejected(r, "needs content.scenario");
    }
}
