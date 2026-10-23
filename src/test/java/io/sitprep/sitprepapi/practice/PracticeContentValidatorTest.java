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
import static io.sitprep.sitprepapi.practice.PracticeFixtures.withConsequences;
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
    void actionParamsAreShortIdentifiersOnly() {
        ObjectNode ok = scenario("k", 1);
        ((ObjectNode) content(ok).get("tags").get("needs_contact_review")).putObject("params").put("intent", "outOfTownContact");
        assertThat(errors(ok)).isEmpty();

        ObjectNode bad = scenario("k", 1);
        ((ObjectNode) content(bad).get("tags").get("needs_contact_review")).putObject("params").put("intent", "../../admin?x=1");
        assertRejected(bad, "must be a short identifier");

        ObjectNode orphan = scenario("k", 1);
        ((ObjectNode) content(orphan).get("tags").get("communicated_clearly")).putObject("params").put("intent", "x");
        assertRejected(orphan, "has params but no action");
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
        ((ObjectNode) content(activityText).get("activity")).put("prompt", "Imagine you are home alone in a storm.");
        assertRejected(activityText, "prohibited practice copy");

        // The same injury word is not a family-only rule for adult scenarios.
        ObjectNode adult = scenario("k", 1);
        content(adult).put("objective", "Check whether anyone is injured before you leave.");
        assertThat(errors(adult)).isEmpty();
    }

    @Test
    void familyActivityNeedsTheStrictItemSortShape() {
        ObjectNode wrongType = family("kit", 1);
        ((ObjectNode) content(wrongType).get("activity")).put("type", "QUIZ");
        assertRejected(wrongType, ".type must be ITEM_SORT");

        ObjectNode duplicatePile = family("kit", 1);
        ObjectNode activity = (ObjectNode) content(duplicatePile).get("activity");
        ((ObjectNode) activity.withArray("piles").get(1)).put("key", "core");
        assertRejected(duplicatePile, "duplicate pile key core");

        ObjectNode badArt = family("kit", 1);
        ((ObjectNode) content(badArt).get("activity").get("items").get(0)).put("artKey", "Not Snake");
        assertRejected(badArt, "artKey must be lower-snake-case");

        // Art is optional: an item without art shows none in the decision loop (Weather Watch).
        ObjectNode noArt = family("kit", 1);
        ((ObjectNode) content(noArt).get("activity").get("items").get(0)).remove("artKey");
        assertThat(PracticeContentValidator.validate(version(noArt))).isEmpty();

        ObjectNode longSummary = family("kit", 1);
        ((ObjectNode) content(longSummary).get("activity")).put("summaryTitle", "x".repeat(81));
        assertRejected(longSummary, ".summaryTitle");

        ObjectNode missingRecap = family("kit", 1);
        ((ObjectNode) content(missingRecap).get("activity")).remove("recapBody");
        assertRejected(missingRecap, ".recapBody is required");
    }

    @Test
    void familyCompletionTagsAndNextStepAreServerOwnedAndValidated() {
        ObjectNode unknownTag = family("kit", 1);
        ((ObjectNode) content(unknownTag).get("activity")).withArray("completionTags").add("not_declared");
        assertRejected(unknownTag, "with no copy in content.tags");

        ObjectNode unsafeAction = family("kit", 1);
        ((ObjectNode) content(unsafeAction).get("activity")).put("nextAction", "OPEN_ACTIVE_SITUATION");
        assertRejected(unsafeAction, "needs a nextAction from PracticeActions.ALLOWED");
    }

    // -- story touches (EXEC-I5) ------------------------------------------

    private static ObjectNode nodeAt(ObjectNode root, int i) {
        return (ObjectNode) content(root).get("scenario").get("nodes").get(i);
    }

    @Test
    void sceneAndMessagesAreOptionalAndParse() {
        ObjectNode r = scenario("k", 1);
        nodeAt(r, 0).put("scene", "Saturday, 4:10 PM · Kitchen");
        var msgs = nodeAt(r, 0).putArray("messages");
        msgs.addObject().put("from", "You").put("text", "I'm safe. Where are you?").put("outgoing", true);
        msgs.addObject().put("from", "Sam").put("text", "At school. Phones are jammed.");
        assertThat(errors(r)).isEmpty();
        var node = version(r).body().scenario().nodes().get(0);
        assertThat(node.scene()).isEqualTo("Saturday, 4:10 PM · Kitchen");
        assertThat(node.messages()).hasSize(2);
        assertThat(node.messages().get(0).outgoing()).isTrue();
        assertThat(node.messages().get(1).outgoing()).isNull();
        assertThat(version(scenario("k", 1)).body().scenario().nodes().get(0).scene()).isNull();
    }

    @Test
    void storyTouchesStayShortAndAreNeverBlank() {
        ObjectNode longScene = scenario("k", 1);
        nodeAt(longScene, 0).put("scene", "x".repeat(81));
        assertRejected(longScene, "scene is longer than 80 characters");

        ObjectNode blankScene = scenario("k", 1);
        nodeAt(blankScene, 0).put("scene", " ");
        assertRejected(blankScene, "scene is required");

        ObjectNode empty = scenario("k", 1);
        nodeAt(empty, 0).putArray("messages");
        assertRejected(empty, "messages needs 1-3 entries");

        ObjectNode four = scenario("k", 1);
        var m = nodeAt(four, 0).putArray("messages");
        for (int i = 0; i < 4; i++) m.addObject().put("from", "A").put("text", "hi");
        assertRejected(four, "messages needs 1-3 entries");

        ObjectNode noText = scenario("k", 1);
        nodeAt(noText, 0).putArray("messages").addObject().put("from", "Sam");
        assertRejected(noText, "message 0 text is required");

        ObjectNode longText = scenario("k", 1);
        nodeAt(longText, 0).putArray("messages").addObject().put("from", "Sam").put("text", "y".repeat(161));
        assertRejected(longText, "message 0 text is longer than 160 characters");
    }

    @Test
    void anUntouchedFileHashesAsBefore() {
        // The new optional fields live on the record, not in the file: a file without them hashes the same.
        ObjectNode r = scenario("k", 1);
        String before = ContentHasher.hashFile(r);
        assertThat(version(r).contentHash()).isEqualTo(before);
    }

    // -- consequence (spec Q10) ------------------------------------------

    @Test
    void consequenceOnEveryChoiceParsesAndPasses() {
        ObjectNode r = withConsequences(scenario("k", 2));
        assertThat(errors(r)).isEmpty();
        assertThat(version(r).body().scenario().nodes().get(0).choices().get(0).consequence())
                .isEqualTo("Consequence for text: the reply comes.");
    }

    @Test
    void noConsequenceAnywhereIsStillValid() {
        ObjectNode r = scenario("k", 1);
        assertThat(errors(r)).isEmpty();
        assertThat(version(r).body().scenario().nodes())
                .allSatisfy(n -> assertThat(n.choices()).allSatisfy(c -> assertThat(c.consequence()).isNull()));
    }

    @Test
    void consequenceIsAllOrNonePerVersion() {
        ObjectNode one = scenario("k", 1);
        choiceAt(one, 0, 0).put("consequence", "Your sister replies a minute later.");
        assertRejected(one, "consequence is all-or-none per version; 1 of 4 choices have one");
        assertRejected(one, "missing on start.call, second.plan, second.improvise");

        // All but one, across nodes, is still a mix.
        ObjectNode most = withConsequences(scenario("k", 1));
        choiceAt(most, 1, 1).remove("consequence");
        assertRejected(most, "missing on second.improvise");
    }

    @Test
    void consequenceLengthAndBlank() {
        ObjectNode exact = withConsequences(scenario("k", 1));
        choiceAt(exact, 0, 0).put("consequence", "a".repeat(PracticeContentValidator.CONSEQUENCE_MAX));
        assertThat(errors(exact)).isEmpty();

        ObjectNode longer = withConsequences(scenario("k", 1));
        choiceAt(longer, 0, 0).put("consequence", "a".repeat(PracticeContentValidator.CONSEQUENCE_MAX + 1));
        assertRejected(longer, "choice text consequence is longer than 140 characters");

        ObjectNode blank = withConsequences(scenario("k", 1));
        choiceAt(blank, 0, 1).put("consequence", "  ");
        assertRejected(blank, "choice call consequence is blank");
    }

    @Test
    void consequenceNeverJudgesThePerson() {
        for (String line : List.of(
                "That was the WRONG move.",
                "A small mistake: the message sits unsent.",
                "The plan failed.",
                "You should have texted first.",
                "You should've texted first.",
                "Bad choice. The line stays busy.",
                "Correct. Your brother answers.",
                "Right call. Your brother answers.")) {
            ObjectNode r = withConsequences(scenario("k", 1));
            choiceAt(r, 0, 0).put("consequence", line);
            assertThat(errors(r)).as(line).anySatisfy(e -> assertThat(e).contains("judgment wording in consequence"));
        }
    }

    @Test
    void consequenceIsInTheGeneralCopyGuard() {
        ObjectNode r = withConsequences(scenario("k", 1));
        choiceAt(r, 1, 1).put("consequence", "Nobody answers. You lose an hour and 10 points.");
        assertRejected(r, "prohibited practice copy");
    }

    @Test
    void judgmentTermsAreConsequenceOnly() {
        // The same words in feedback are left to the human review, as before.
        ObjectNode r = scenario("k", 1);
        choiceAt(r, 0, 0).put("feedback", "A wrong number in a contact card is easy to miss.");
        assertThat(errors(r)).isEmpty();
    }

    @Test
    void consequenceChangesTheHash() {
        assertThat(ContentHasher.hashFile(withConsequences(scenario("k", 1))))
                .isNotEqualTo(ContentHasher.hashFile(scenario("k", 1)));
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

    // -- hashed media (EXEC-M) ---------------------------------------------

    private static final String STILL = "sha256:" + "1".repeat(64);
    private static final String STILL2 = "sha256:" + "2".repeat(64);
    private static final String CLIP = "sha256:" + "3".repeat(64);
    private static final String T43 = "sha256:" + "4".repeat(64);      // REALISM 1440x1080 (4:3)
    private static final String T43_NEAR = "sha256:" + "5".repeat(64); // REALISM 1000x751 (within 1%)
    private static final String T43_OFF = "sha256:" + "6".repeat(64);  // REALISM 1000x780 (outside 1%)

    private static PracticeMediaManifest testManifest() {
        var style = PracticeContent.MediaStyle.REALISM;
        return new PracticeMediaManifest(new PracticeMediaManifest.File(1, List.of(
                new PracticeMediaManifest.Asset(STILL, style, PracticeContent.MediaKind.STILL, "/images/a", List.of(640), 1280, 720, "center", 5L, null),
                new PracticeMediaManifest.Asset(STILL2, PracticeContent.MediaStyle.ILLUSTRATION, PracticeContent.MediaKind.STILL, "/images/b", List.of(640), 1280, 720, "center", 5L, null),
                new PracticeMediaManifest.Asset(CLIP, style, PracticeContent.MediaKind.CLIP, "/media/c", null, 1280, 720, "center", 5L, 8000),
                new PracticeMediaManifest.Asset(T43, style, PracticeContent.MediaKind.STILL, "/images/t", List.of(640), 1440, 1080, "center", 5L, null),
                new PracticeMediaManifest.Asset(T43_NEAR, style, PracticeContent.MediaKind.STILL, "/images/tn", List.of(640), 1000, 751, "center", 5L, null),
                new PracticeMediaManifest.Asset(T43_OFF, style, PracticeContent.MediaKind.STILL, "/images/to", List.of(640), 1000, 780, "center", 5L, null))));
    }

    private static List<String> mediaErrors(ObjectNode root) {
        return PracticeContentValidator.validate(version(root), testManifest());
    }

    private static ObjectNode withScene(ObjectNode root, String sha) {
        root.put("schema", 2);
        content(root).put("mediaStyle", "REALISM");
        var n = (ObjectNode) content(root).get("scenario").get("nodes").get(0);
        n.putObject("media").put("slotKey", root.get("key").asText() + "." + n.get("key").asText() + ".scene")
                .put("kind", "STILL").put("assetSha256", sha).put("alt", "A kitchen table with a phone on it.");
        return root;
    }

    @Test
    void aHashedSceneStillValidatesAndSchema1FilesAreUntouched() {
        assertThat(mediaErrors(withScene(scenario("k", 1), STILL))).isEmpty();
        assertThat(errors(scenario("k", 1))).isEmpty(); // schema 1 without media still valid
    }

    @Test
    void mediaMustBeAllowListedMatchingAndDeclared() {
        assertThat(mediaErrors(withScene(scenario("k", 1), "sha256:" + "9".repeat(64)))).anyMatch(e -> e.contains("not in the media manifest"));
        assertThat(mediaErrors(withScene(scenario("k", 1), STILL2))).anyMatch(e -> e.contains("style does not match"));

        ObjectNode s1 = withScene(scenario("k", 1), STILL); s1.put("schema", 1);
        assertThat(mediaErrors(s1)).anyMatch(e -> e.contains("media requires schema 2"));

        ObjectNode noStyle = withScene(scenario("k", 1), STILL); content(noStyle).remove("mediaStyle");
        assertThat(mediaErrors(noStyle)).anyMatch(e -> e.contains("mediaStyle is required"));

        ObjectNode badSlot = withScene(scenario("k", 1), STILL);
        ((ObjectNode) content(badSlot).get("scenario").get("nodes").get(0).get("media")).put("slotKey", "k.other.scene");
        assertThat(mediaErrors(badSlot)).anyMatch(e -> e.contains("slotKey must be k.start.scene"));

        ObjectNode noAlt = withScene(scenario("k", 1), STILL);
        ((ObjectNode) content(noAlt).get("scenario").get("nodes").get(0).get("media")).remove("alt");
        assertThat(mediaErrors(noAlt)).anyMatch(e -> e.contains(".alt is required"));

        ObjectNode stray = scenario("k", 1); stray.put("schema", 2); content(stray).put("mediaStyle", "REALISM");
        assertThat(mediaErrors(stray)).anyMatch(e -> e.contains("mediaStyle is only valid with media"));
    }

    private static ObjectNode withExplanation(ObjectNode root, String sha) {
        root.put("schema", 2);
        content(root).put("mediaStyle", "REALISM");
        var n = (ObjectNode) content(root).get("scenario").get("nodes").get(0);
        n.putObject("explanationMedia").put("slotKey", root.get("key").asText() + "." + n.get("key").asText() + ".explanation")
                .put("kind", "STILL").put("assetSha256", sha).put("alt", "A calm explanation of the step.");
        return root;
    }

    private static ObjectNode explanationOf(ObjectNode root) {
        return (ObjectNode) content(root).get("scenario").get("nodes").get(0).get("explanationMedia");
    }

    @Test
    void anExplanationStillValidatesAndCountsTowardTheSchema2Rules() {
        assertThat(mediaErrors(withExplanation(scenario("k", 1), STILL))).isEmpty();

        ObjectNode s1 = withExplanation(scenario("k", 1), STILL); s1.put("schema", 1);
        assertThat(mediaErrors(s1)).anyMatch(e -> e.contains("media requires schema 2"));

        ObjectNode noStyle = withExplanation(scenario("k", 1), STILL); content(noStyle).remove("mediaStyle");
        assertThat(mediaErrors(noStyle)).anyMatch(e -> e.contains("mediaStyle is required"));
    }

    @Test
    void anExplanationNeedsItsSlotTheManifestTheContentStyleAndAStill() {
        ObjectNode badSlot = withExplanation(scenario("k", 1), STILL);
        explanationOf(badSlot).put("slotKey", "k.start.scene");
        assertThat(mediaErrors(badSlot)).anyMatch(e -> e.contains("slotKey must be k.start.explanation"));

        assertThat(mediaErrors(withExplanation(scenario("k", 1), "sha256:" + "9".repeat(64))))
                .anyMatch(e -> e.contains("not in the media manifest"));
        assertThat(mediaErrors(withExplanation(scenario("k", 1), STILL2)))
                .anyMatch(e -> e.contains("style does not match"));

        ObjectNode clip = withExplanation(scenario("k", 1), CLIP);
        explanationOf(clip).put("kind", "CLIP").put("posterSha256", STILL).put("durationMs", 8000);
        assertThat(mediaErrors(clip)).anyMatch(e -> e.contains("an explanation is a still"));
    }

    @Test
    void choiceReactionMediaIsRetiredAndRejectedOnAnyContent() {
        ObjectNode r = withScene(scenario("k", 1), STILL);
        var c0 = choiceAt(r, 0, 0);
        c0.putObject("media").put("slotKey", "k.start." + c0.get("key").asText() + ".reaction")
                .put("kind", "STILL").put("assetSha256", STILL).put("alt", "Calm reaction.");
        assertThat(mediaErrors(r)).anyMatch(e -> e.contains("choice (reaction) media is retired"));
        // Rejected even when it would have been the only media in a schema-1 file.
        ObjectNode only = scenario("k", 1);
        choiceAt(only, 0, 0).putObject("media").put("kind", "STILL");
        assertThat(errors(only)).anyMatch(e -> e.contains("choice (reaction) media is retired"));
    }

    @Test
    void clipsNeedAPosterAndAShortDuration() {
        ObjectNode clip = withScene(scenario("k", 1), STILL);
        ((ObjectNode) content(clip).get("scenario").get("nodes").get(0).get("media"))
                .put("kind", "CLIP").put("assetSha256", CLIP).put("durationMs", 12000);
        assertThat(mediaErrors(clip)).anyMatch(e -> e.contains("needs a posterSha256"))
                .anyMatch(e -> e.contains("runs 6-10 seconds"));
        ((ObjectNode) content(clip).get("scenario").get("nodes").get(0).get("media"))
                .put("posterSha256", STILL).put("durationMs", 8000);
        assertThat(mediaErrors(clip)).isEmpty();
    }

    @Test
    void theAssetHashIsPartOfTheContentHash() {
        String a = version(withScene(scenario("k", 1), STILL)).contentHash();
        String b = version(withScene(scenario("k", 1), CLIP)).contentHash();
        assertThat(a).isNotEqualTo(b);
    }

    // -- CHOICE_THUMB + INTRO (EXEC-M3) -------------------------------------

    private static ObjectNode withThumbs(ObjectNode root, String sha) {
        root.put("schema", 2);
        content(root).put("mediaStyle", "REALISM");
        var n = node(root, 0);
        for (var c : n.get("choices")) {
            ((ObjectNode) c).putObject("thumb")
                    .put("slotKey", root.get("key").asText() + "." + n.get("key").asText() + "." + c.get("key").asText() + ".thumb")
                    .put("kind", "STILL").put("assetSha256", sha).put("alt", "A room.");
        }
        return root;
    }

    private static ObjectNode thumbOf(ObjectNode root, int choice) {
        return (ObjectNode) choiceAt(root, 0, choice).get("thumb");
    }

    @Test
    void aThumbOnEveryChoiceOfANodeValidatesAndCountsTowardTheSchema2Rules() {
        assertThat(mediaErrors(withThumbs(scenario("k", 1), T43))).isEmpty();
        assertThat(mediaErrors(withThumbs(scenario("k", 1), T43_NEAR))).isEmpty(); // 4:3 within 1%

        ObjectNode s1 = withThumbs(scenario("k", 1), T43); s1.put("schema", 1);
        assertThat(mediaErrors(s1)).anyMatch(e -> e.contains("media requires schema 2"));

        ObjectNode noStyle = withThumbs(scenario("k", 1), T43); content(noStyle).remove("mediaStyle");
        assertThat(mediaErrors(noStyle)).anyMatch(e -> e.contains("mediaStyle is required"));
    }

    @Test
    void thumbsAreAllOrNonePerNode() {
        ObjectNode mixed = withThumbs(scenario("k", 1), T43);
        choiceAt(mixed, 0, 1).remove("thumb");
        assertThat(mediaErrors(mixed)).anyMatch(e -> e.contains("choice thumbs are all-or-none per node (1 of 2)"));

        // A different node may carry none while this one carries all.
        assertThat(mediaErrors(withThumbs(scenario("k", 1), T43)))
                .noneMatch(e -> e.contains("all-or-none"));
    }

    @Test
    void aThumbIsA4by3StillWithinOnePercent() {
        assertThat(mediaErrors(withThumbs(scenario("k", 1), STILL))).anyMatch(e -> e.contains("a choice thumb is 4:3 (asset is 1280x720)"));
        assertThat(mediaErrors(withThumbs(scenario("k", 1), T43_OFF))).anyMatch(e -> e.contains("a choice thumb is 4:3 (asset is 1000x780)"));

        ObjectNode clip = withThumbs(scenario("k", 1), CLIP);
        thumbOf(clip, 0).put("kind", "CLIP").put("posterSha256", T43).put("durationMs", 8000);
        assertThat(mediaErrors(clip)).anyMatch(e -> e.contains("a choice thumb is a still"));
    }

    @Test
    void aThumbNeedsItsSlotKeyTheManifestAndTheContentStyle() {
        ObjectNode badSlot = withThumbs(scenario("k", 1), T43);
        thumbOf(badSlot, 0).put("slotKey", "k.start.text.reaction");
        assertThat(mediaErrors(badSlot)).anyMatch(e -> e.contains("slotKey must be k.start.text.thumb"));

        // Another choice's slot on this choice is also wrong.
        ObjectNode swapped = withThumbs(scenario("k", 1), T43);
        thumbOf(swapped, 1).put("slotKey", "k.start.text.thumb");
        assertThat(mediaErrors(swapped)).anyMatch(e -> e.contains("slotKey must be k.start.call.thumb"));

        assertThat(mediaErrors(withThumbs(scenario("k", 1), "sha256:" + "9".repeat(64))))
                .anyMatch(e -> e.contains("not in the media manifest"));

        ObjectNode wrongStyle = withThumbs(scenario("k", 1), T43);
        content(wrongStyle).put("mediaStyle", "ILLUSTRATION");
        assertThat(mediaErrors(wrongStyle)).anyMatch(e -> e.contains("style does not match"));

        ObjectNode noAlt = withThumbs(scenario("k", 1), T43);
        thumbOf(noAlt, 0).remove("alt");
        assertThat(mediaErrors(noAlt)).anyMatch(e -> e.contains(".alt is required"));
    }

    @Test
    void choiceMediaStaysRejectedEvenAlongsideThumbs() {
        ObjectNode r = withThumbs(scenario("k", 1), T43);
        choiceAt(r, 0, 0).putObject("media").put("slotKey", "k.start.text.reaction").put("kind", "STILL");
        assertThat(mediaErrors(r)).anyMatch(e -> e.contains("choice (reaction) media is retired"));
    }

    private static ObjectNode withIntro(ObjectNode root, String sha) {
        root.put("schema", 2);
        content(root).put("mediaStyle", "REALISM");
        ((ObjectNode) content(root).get("scenario")).putObject("introMedia")
                .put("slotKey", root.get("key").asText() + ".intro")
                .put("kind", "STILL").put("assetSha256", sha).put("alt", "A calm street.");
        return root;
    }

    @Test
    void anIntroIsA4by3StillInItsSlotAndCountsTowardTheSchema2Rules() {
        assertThat(mediaErrors(withIntro(scenario("k", 1), T43))).isEmpty();
        assertThat(mediaErrors(withIntro(scenario("k", 1), STILL))).anyMatch(e -> e.contains("an intro is 4:3 (asset is 1280x720)"));

        ObjectNode badSlot = withIntro(scenario("k", 1), T43);
        ((ObjectNode) content(badSlot).get("scenario").get("introMedia")).put("slotKey", "k.start.scene");
        assertThat(mediaErrors(badSlot)).anyMatch(e -> e.contains("slotKey must be k.intro"));

        ObjectNode s1 = withIntro(scenario("k", 1), T43); s1.put("schema", 1);
        assertThat(mediaErrors(s1)).anyMatch(e -> e.contains("media requires schema 2"));

        ObjectNode clip = withIntro(scenario("k", 1), CLIP);
        ((ObjectNode) content(clip).get("scenario").get("introMedia")).put("kind", "CLIP").put("posterSha256", T43).put("durationMs", 8000);
        assertThat(mediaErrors(clip)).anyMatch(e -> e.contains("an intro is a still"));
    }
}
