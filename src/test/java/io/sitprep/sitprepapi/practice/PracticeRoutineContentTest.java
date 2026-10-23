package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.M;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.routine;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** EXEC-R1: the ROUTINE content kind. Validator rules, registry cross-references, the six DRAFT files, the offers. */
class PracticeRoutineContentTest {

    /** The hashes the independent reviewer must read. Any edit to a routine fails here first. */
    static final Map<String, String> DRAFT_HASHES = Map.of(
            "tornado-night-routine", "sha256:eaecfc959c3987634c45ba4dfc573516030741b2b6d9fa34d057f6d725dec680",
            "comms-reconnect-routine", "sha256:cdd5e258a0d674a01ffc8d1ae07dc95a2f09bc44d7d81cdca8b42d964dcbdd3e",
            "fire-exit-routine", "sha256:ea8fc76762aebc09196a745a3d8d1eb35746396dc356f99fe356dc34c649b706",
            "earthquake-protect-routine", "sha256:2b2474351285be2097c97e048ff31092c1dcc81daaaa4aaa3a0928bd7d37d2ea",
            "flood-road-routine", "sha256:e6f33253c167e3c1d2d3cb712c351e86b50f2e1186511a4f5de4df034f59db68",
            "pets-evac-routine", "sha256:ee868c8d36ed2644975a529836dfd0ced4318ef8f6473083a918017f35fb8cfa");

    private static final PracticeCatalog SHIPPED = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);

    private static ObjectNode r(ObjectNode root) {
        return (ObjectNode) root.get("content").get("routine");
    }

    private static ObjectNode slot(ObjectNode root, int i) {
        return (ObjectNode) r(root).get("slots").get(i);
    }

    private static List<String> errors(ObjectNode root) {
        return PracticeContentValidator.validate(version(root));
    }

    private static void assertRejected(ObjectNode root, String fragment) {
        assertThat(errors(root)).anySatisfy(e -> assertThat(e).contains(fragment));
    }

    // -- the six shipped DRAFT routines ---------------------------------------

    @Test
    void sixRoutinesShipAsPendingDraftsAtPinnedHashes() {
        assertThat(SHIPPED.rejected()).isEmpty();
        assertThat(SHIPPED.keys(PracticeKind.ROUTINE)).containsExactlyInAnyOrderElementsOf(DRAFT_HASHES.keySet());
        for (var e : DRAFT_HASHES.entrySet()) {
            Version v = SHIPPED.exact(e.getKey(), 1).orElseThrow();
            assertThat(v.contentHash()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(v.publishState()).isEqualTo(PublishState.DRAFT);
            assertThat(v.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.PENDING);
            assertThat(SHIPPED.latestStartable(e.getKey(), false)).as("production never serves " + e.getKey()).isEmpty();
            assertThat(SHIPPED.latestStartable(e.getKey(), true)).isPresent();
            assertThat(v.body().sources()).hasSizeBetween(1, 3);
        }
    }

    @Test
    void reviewTiersAndOfferedByFollowTheContract() {
        Map<String, PracticeContent.ReviewTier> tiers = Map.of(
                "tornado-night-routine", PracticeContent.ReviewTier.LIFE_SAFETY,
                "fire-exit-routine", PracticeContent.ReviewTier.LIFE_SAFETY,
                "earthquake-protect-routine", PracticeContent.ReviewTier.LIFE_SAFETY,
                "flood-road-routine", PracticeContent.ReviewTier.LIFE_SAFETY,
                "comms-reconnect-routine", PracticeContent.ReviewTier.PREP,
                "pets-evac-routine", PracticeContent.ReviewTier.PREP);
        Map<String, String> offeredBy = Map.of(
                "tornado-night-routine", "tornado-warning-shelter-decision",
                "comms-reconnect-routine", "comms-outage-family-reconnect",
                "earthquake-protect-routine", "earthquake-first-five-minutes",
                "flood-road-routine", "flash-flood-turn-around-route",
                "pets-evac-routine", "evacuating-with-pets");
        for (var e : tiers.entrySet()) {
            assertThat(SHIPPED.exact(e.getKey(), 1).orElseThrow().body().routine().reviewTier()).as(e.getKey()).isEqualTo(e.getValue());
        }
        for (var e : offeredBy.entrySet()) {
            assertThat(SHIPPED.exact(e.getKey(), 1).orElseThrow().body().routine().offeredBy().scenarios()).containsExactly(e.getValue());
            assertThat(SHIPPED.keys(PracticeKind.ADULT_SCENARIO)).contains(e.getValue());
        }
        assertThat(SHIPPED.exact("fire-exit-routine", 1).orElseThrow().body().routine().offeredBy().activities())
                .containsExactly("together-get-out-and-meet-up");
    }

    @Test
    void theIfThenTextIsTheOwnerApprovedWording() {
        Map<String, String[]> wording = Map.of(
                "tornado-night-routine", new String[]{"If a tornado warning comes at night", "{who} wakes everyone and we all go to {place}."},
                "comms-reconnect-routine", new String[]{"If we can't reach each other", "We text first, then contact {contact}."},
                "fire-exit-routine", new String[]{"If the smoke alarm sounds", "We use the nearest safe exit and meet at {place}. Once we're out, we stay out."},
                "earthquake-protect-routine", new String[]{"If the ground shakes", "We drop, cover and hold on where we are. When it stops, {who} checks on everyone."},
                "flood-road-routine", new String[]{"If water covers the road", "We turn around and take {route}."},
                "pets-evac-routine", new String[]{"If we have to leave", "{who} gets the pets into carriers or on leashes, and they come with us."});
        for (var e : wording.entrySet()) {
            PracticeContent.Routine rt = SHIPPED.exact(e.getKey(), 1).orElseThrow().body().routine();
            assertThat(rt.ifText()).isEqualTo(e.getValue()[0]);
            assertThat(rt.thenTemplate()).isEqualTo(e.getValue()[1]);
        }
    }

    @Test
    void everySourceIsAPageTheRepoAlreadyCitesElsewhere() {
        Set<String> elsewhere = new java.util.HashSet<>();
        for (Version v : SHIPPED.all()) {
            if (v.kind() == PracticeKind.ROUTINE || v.body().sources() == null) continue;
            v.body().sources().forEach(s -> elsewhere.add(s.url()));
        }
        for (String key : DRAFT_HASHES.keySet()) {
            for (PracticeContent.Source s : SHIPPED.exact(key, 1).orElseThrow().body().sources()) {
                assertThat(elsewhere).as(key + " cites " + s.url()).contains(s.url());
            }
        }
    }

    @Test
    void theShippedSlotsMatchTheContract() {
        PracticeContent.Routine tornado = SHIPPED.exact("tornado-night-routine", 1).orElseThrow().body().routine();
        assertThat(tornado.slots()).extracting(PracticeContent.RoutineSlot::key, PracticeContent.RoutineSlot::source,
                        PracticeContent.RoutineSlot::tier, PracticeContent.RoutineSlot::fallback)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("who", PracticeContent.RoutineSlotSource.ADULT, null, "a grown-up"),
                        org.assertj.core.groups.Tuple.tuple("place", PracticeContent.RoutineSlotSource.PLACE, "INDOOR_SAFE_ROOM", "our safe room"));
        PracticeContent.RoutineSlot contact = SHIPPED.exact("comms-reconnect-routine", 1).orElseThrow().body().routine().slots().get(0);
        assertThat(contact.source()).isEqualTo(PracticeContent.RoutineSlotSource.CONTACT);
        assertThat(contact.contactType()).isEqualTo("OUT_OF_AREA");
        assertThat(contact.fallback()).isEqualTo("our out-of-area contact");
        PracticeContent.RoutineSlot spot = SHIPPED.exact("fire-exit-routine", 1).orElseThrow().body().routine().slots().get(0);
        assertThat(spot.tier()).isEqualTo("OUTSIDE_HOME");
        assertThat(spot.fallback()).isEqualTo("our meeting spot outside");
        PracticeContent.RoutineSlot route = SHIPPED.exact("flood-road-routine", 1).orElseThrow().body().routine().slots().get(0);
        assertThat(route.source()).isEqualTo(PracticeContent.RoutineSlotSource.TEXT);
        assertThat(route.fallback()).isEqualTo("our backup route");
    }

    @Test
    void theExistingSkillRoutineKeysResolveAndPublishedHashesAreUntouched() {
        assertThat(SHIPPED.keys(PracticeKind.ROUTINE)).contains("tornado-night-routine", "earthquake-protect-routine");
        assertThat(PracticeRegistryCheck.problems(SHIPPED.all(), PracticeRegistryCheck.contractDrillIds())).isEmpty();
    }

    // -- validator rules -------------------------------------------------------

    @Test
    void theFixtureValidatesAndRendersWithFallbacks() {
        ObjectNode root = routine("fixture-routine", "reconnect", null);
        assertThat(errors(root)).isEmpty();
        assertThat(PracticeRoutines.preview(version(root).body().routine()))
                .isEqualTo("a grown-up calls our out-of-area contact right away; we go to our safe room and take our backup route.");
    }

    @Test
    void renderUsesTheValueAndFallsBackWhenBlank() {
        PracticeContent.Routine rt = version(routine("fixture-routine", "reconnect", null)).body().routine();
        String out = PracticeRoutines.render(rt, k -> k.equals("who") ? "Rosa $1 \\n" : k.equals("route") ? "  " : null);
        assertThat(out).startsWith("Rosa $1 \\n calls").endsWith("take our backup route.");
    }

    @Test
    void aTemplateSlotMustBeDeclaredAndEveryDeclaredSlotUsed() {
        ObjectNode undeclared = routine("fixture-routine", "reconnect", null);
        r(undeclared).put("thenTemplate", "{who} calls {contact} {when}; we go to {place} and take {route} with {nobody}.");
        assertRejected(undeclared, "{nobody} which is not a declared slot");

        ObjectNode unused = routine("fixture-routine", "reconnect", null);
        r(unused).put("thenTemplate", "{who} calls {contact} {when}; we go to {place}.");
        assertRejected(unused, "slot route is declared but not used");

        ObjectNode stray = routine("fixture-routine", "reconnect", null);
        r(stray).put("thenTemplate", "{who} calls {contact} {when}; we go to {place} and take {route} {");
        assertRejected(stray, "stray brace");
    }

    @Test
    void lengthsAreCapped() {
        ObjectNode longIf = routine("fixture-routine", "reconnect", null);
        r(longIf).put("ifText", "If " + "x".repeat(118));
        assertRejected(longIf, "routine.ifText is longer than 120");

        ObjectNode longThen = routine("fixture-routine", "reconnect", null);
        slot(longThen, 0).put("fallback", "w".repeat(80));
        slot(longThen, 1).put("fallback", "c".repeat(80));
        slot(longThen, 3).put("fallback", "p".repeat(80));
        assertRejected(longThen, "renders to");

        ObjectNode longFallback = routine("fixture-routine", "reconnect", null);
        slot(longFallback, 0).put("fallback", "w".repeat(81));
        assertRejected(longFallback, "fallback is longer than 80");
    }

    @Test
    void theCopyProhibitionsApplyToEveryRoutineLine() {
        ObjectNode inIf = routine("fixture-routine", "reconnect", null);
        r(inIf).put("ifText", "If someone is dead");
        assertRejected(inIf, "prohibited practice copy");
        ObjectNode inThen = routine("fixture-routine", "reconnect", null);
        r(inThen).put("thenTemplate", "{who} keeps score. {contact} {when} {place} {route}");
        assertRejected(inThen, "prohibited practice copy");
        ObjectNode inFallback = routine("fixture-routine", "reconnect", null);
        slot(inFallback, 4).put("fallback", "the fatal route");
        assertRejected(inFallback, "prohibited practice copy");
        ObjectNode inOption = routine("fixture-routine", "reconnect", null);
        ((ArrayNode) slot(inOption, 2).get("options")).add("you failed");
        assertRejected(inOption, "prohibited practice copy");
    }

    @Test
    void slotShapeRulesAreEnforced() {
        ObjectNode noTier = routine("fixture-routine", "reconnect", null);
        slot(noTier, 3).remove("tier");
        assertRejected(noTier, "a PLACE slot needs tier");

        ObjectNode badTier = routine("fixture-routine", "reconnect", null);
        slot(badTier, 3).put("tier", "OTHER");
        assertRejected(badTier, "a PLACE slot needs tier");

        ObjectNode tierOnText = routine("fixture-routine", "reconnect", null);
        slot(tierOnText, 4).put("tier", "OUTSIDE_HOME");
        assertRejected(tierOnText, "tier belongs only to a PLACE slot");

        ObjectNode noContactType = routine("fixture-routine", "reconnect", null);
        slot(noContactType, 1).remove("contactType");
        assertRejected(noContactType, "a CONTACT slot needs contactType");

        ObjectNode noOptions = routine("fixture-routine", "reconnect", null);
        slot(noOptions, 2).remove("options");
        assertRejected(noOptions, "a CHOICE slot needs 1-8 options");

        ObjectNode optionsOnText = routine("fixture-routine", "reconnect", null);
        slot(optionsOnText, 4).putArray("options").add("x");
        assertRejected(optionsOnText, "options belong only to a CHOICE slot");

        ObjectNode noFallback = routine("fixture-routine", "reconnect", null);
        slot(noFallback, 4).remove("fallback");
        assertRejected(noFallback, "fallback is required");

        ObjectNode dup = routine("fixture-routine", "reconnect", null);
        slot(dup, 4).put("key", "who");
        assertRejected(dup, "duplicate slot key");

        ObjectNode badKey = routine("fixture-routine", "reconnect", null);
        slot(badKey, 4).put("key", "Route Home");
        assertRejected(badKey, "key must match");
    }

    @Test
    void theKeyMatchesTheFileTheKindOwnsItsBodyAndTheTitlesAgree() {
        ObjectNode badKey = routine("Fixture_Routine", "reconnect", null);
        assertRejected(badKey, "key must be lower-kebab-case");

        ObjectNode wrongPath = routine("fixture-routine", "reconnect", null);
        assertThat(PracticeContentValidator.validate(PracticeFixtures.versionAt(wrongPath, "practice/content/other/v1.json")))
                .anySatisfy(e -> assertThat(e).contains("file must live at practice/content/fixture-routine/v1.json"));

        ObjectNode none = routine("fixture-routine", "reconnect", null);
        ((ObjectNode) none.get("content")).remove("routine");
        assertRejected(none, "a ROUTINE needs content.routine");

        ObjectNode onScenario = scenario("fixture-scenario", 1);
        ((ObjectNode) onScenario.get("content")).set("routine", r(routine("x-routine", null, null)));
        assertRejected(onScenario, "only a ROUTINE carries content.routine");

        ObjectNode tagged = routine("fixture-routine", "reconnect", null);
        ((ObjectNode) tagged.get("content")).putObject("tags").putObject("communicated_clearly").put("text", "x");
        assertRejected(tagged, "carries no debrief tags");

        ObjectNode minutes = routine("fixture-routine", "reconnect", null);
        ((ObjectNode) minutes.get("content")).put("estimatedMinutes", 5);
        assertRejected(minutes, "a ROUTINE has no estimatedMinutes");

        ObjectNode titles = routine("fixture-routine", "reconnect", null);
        r(titles).put("title", "Another title");
        assertRejected(titles, "routine.title must equal content.title");

        ObjectNode noSources = routine("fixture-routine", "reconnect", null);
        ((ObjectNode) noSources.get("content")).putArray("sources");
        assertRejected(noSources, "needs at least one official source");
    }

    @Test
    void aRoutineIsNeverPublishedUnreviewed() {
        ObjectNode published = routine("fixture-routine", "reconnect", null);
        ((ObjectNode) published.get("lifecycle")).put("publishState", "PUBLISHED");
        assertRejected(published, "needs safetyReview.status APPROVED");
    }

    // -- registry cross-references ---------------------------------------------

    private static List<String> registry(Version... versions) {
        return PracticeRegistryCheck.problems(List.of(versions), Set.of());
    }

    @Test
    void offeredByMustNameExistingContentOfTheRightKind() {
        Version routine = version(routine("fixture-routine", "missing-scenario", "missing-activity"));
        assertThat(registry(routine))
                .anyMatch(p -> p.contains("offeredBy scenario missing-scenario does not exist"))
                .anyMatch(p -> p.contains("offeredBy activity missing-activity does not exist"));
        Version scenario = version(scenario("reconnect", 1));
        assertThat(registry(version(routine("fixture-routine", "reconnect", null)), scenario)).isEmpty();
        // A scenario key in the activities list is not an activity.
        assertThat(registry(version(routine("fixture-routine", null, "reconnect")), scenario))
                .anyMatch(p -> p.contains("offeredBy activity reconnect does not exist"));
    }

    @Test
    void eachScenarioIsOfferedByAtMostOneRoutine() {
        Version scenario = version(scenario("reconnect", 1));
        assertThat(registry(version(routine("first-routine", "reconnect", null)),
                version(routine("second-routine", "reconnect", null)), scenario))
                .anyMatch(p -> p.contains("scenario reconnect is already offered by routine"));
    }

    @Test
    void aSkillIfThenMustNameARoutine() {
        ObjectNode tornado = skillPilot();
        Version skill = version(tornado);
        assertThat(PracticeRegistryCheck.problems(List.of(skill), PracticeRegistryCheck.contractDrillIds()))
                .anyMatch(p -> p.contains("ifThen.routine tornado-night-routine is not a routine"));
    }

    private static ObjectNode skillPilot() {
        try (var in = PracticeRoutineContentTest.class.getResourceAsStream("/practice/content/earthquake-protect-in-place/v1.json")) {
            ObjectNode root = (ObjectNode) M.readTree(in);
            ((ObjectNode) root.get("content").get("skill").get("ifThen")).put("routine", "tornado-night-routine");
            return root;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // -- the offers on the Practice side ---------------------------------------

    private static PracticeAvailabilityService availability(PracticeCatalog catalog, boolean preview) {
        PracticeContentControlRepo controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        return new PracticeAvailabilityService(catalog, controls, true, preview,
                Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void anOfferCarriesTheKeyTitleAndTheFallbackPreviewOnlyWhenServable() {
        PracticeCatalog catalog = PracticeCatalog.of(List.of(
                version(routine("fixture-routine", "reconnect", "family-sort"))));
        assertThat(PracticeRoutines.offerFor(availability(catalog, false), true, "reconnect")).as("DRAFT, no preview").isNull();

        PracticeRoutines.RoutineOfferDto offer = PracticeRoutines.offerFor(availability(catalog, true), true, "reconnect");
        assertThat(offer.routineKey()).isEqualTo("fixture-routine");
        assertThat(offer.title()).isEqualTo("Fixture routine");
        assertThat(offer.preview()).isEqualTo("a grown-up calls our out-of-area contact right away; we go to our safe room and take our backup route.");
        assertThat(PracticeRoutines.offerFor(availability(catalog, true), true, "other-scenario")).isNull();
        // Offered to the activity list, not the scenario list.
        assertThat(PracticeRoutines.offerFor(availability(catalog, true), false, "reconnect")).isNull();
        assertThat(PracticeRoutines.offerFor(availability(catalog, true), false, "family-sort")).isNotNull();
        assertThat(new ArrayList<>(List.of(offer)).get(0).preview()).doesNotContain("{");
    }

    @Test
    void aDisabledRoutineIsNotOffered() {
        PracticeCatalog catalog = PracticeCatalog.of(List.of(version(routine("fixture-routine", "reconnect", null))));
        PracticeContentControlRepo controls = mock(PracticeContentControlRepo.class);
        PracticeContentControl c = new PracticeContentControl();
        c.setContentKey("fixture-routine");
        c.setDisabledAt(Instant.parse("2026-10-23T12:00:00Z"));
        when(controls.findAll()).thenReturn(List.of(c));
        when(controls.findById("fixture-routine")).thenReturn(Optional.of(c));
        PracticeAvailabilityService a = new PracticeAvailabilityService(catalog, controls, true, true,
                Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC));
        assertThat(PracticeRoutines.offerFor(a, true, "reconnect")).isNull();
        assertThat(PracticeRoutines.servable(a, "fixture-routine")).isEmpty();
    }
}
