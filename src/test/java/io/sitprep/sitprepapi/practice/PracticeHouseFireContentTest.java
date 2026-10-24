package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CT-06: House Fire at Night v1 and Fire exit routine v2, both DRAFT until the independent Gate 1 review. */
class PracticeHouseFireContentTest {

    static final String FIRE = "house-fire-at-night";
    static final String ROUTINE = "fire-exit-routine";
    /** The hashes Gate 1 must read. Any edit to either file fails here first. */
    static final String FIRE_V1_DRAFT_HASH = "sha256:edc68812ca2a4d4d82cd0cd2440bed1340f2a6733e8bf87efa50cf4467d89a99";
    static final String ROUTINE_V2_DRAFT_HASH = "sha256:5bab24d612b3a1aedfd754e3f5010c27f30d7c7194f7749fca5421a733e5106e";
    static final String ROUTINE_V1_HASH = "sha256:38d93ca7e9788474883eb64f5bf9bb07b25e093f7eb1f2de2ea3e91291065349";

    private static final PracticeCatalog CATALOG = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);

    private static PracticeAvailabilityService availability(boolean preview) {
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
        return new PracticeAvailabilityService(CATALOG, repo, true, preview, clock);
    }

    @Test
    void houseFireV1IsAPinnedLifeSafetyDraftWithTwoApprovedStills() {
        Version v = CATALOG.exact(FIRE, 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(v)).isEmpty();
        assertThat(v.contentHash()).isEqualTo(FIRE_V1_DRAFT_HASH);
        assertThat(v.publishState()).isEqualTo(PublishState.DRAFT);
        assertThat(v.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.PENDING);
        assertThat(v.file().lifecycle().safetyReview().notes()).contains("LIFE_SAFETY", "MEDIA-028 v2", "MEDIA-029 v2");
        assertThat(v.file().schema()).isEqualTo(2);
        assertThat(v.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.ILLUSTRATION);
        assertThat(ScenarioService.hazardTag(FIRE)).isEqualTo("home-fire");

        Map<String, Node> nodes = new HashMap<>();
        v.body().scenario().nodes().forEach(n -> nodes.put(n.key(), n));
        assertThat(v.body().scenario().nodes()).extracting(Node::key).containsExactly("alarm", "door_check", "outside");

        // The pre-choice scene is MEDIA-028 v2; the explanation is MEDIA-029 v2 and only after the commit.
        assertThat(nodes.get("alarm").media().slotKey()).isEqualTo("house-fire-at-night.alarm.scene");
        assertThat(nodes.get("alarm").media().assetSha256())
                .isEqualTo("sha256:6e923217ec941d93e3f259306bb7684f12a16f476a576374773b0f959c8aeba7");
        assertThat(nodes.get("alarm").explanationMedia()).isNull();
        assertThat(nodes.get("door_check").media()).isNull();
        assertThat(nodes.get("door_check").explanationMedia().slotKey()).isEqualTo("house-fire-at-night.door_check.explanation");
        assertThat(nodes.get("door_check").explanationMedia().assetSha256())
                .isEqualTo("sha256:255c36b15ffe0dcde3d7e80eebcc1b71a301bc7950d49fe0232d9afc572cef75");
        // Every third node works with no media; no intro, no thumbs, no ending (none approved).
        assertThat(nodes.get("outside").media()).isNull();
        assertThat(nodes.get("outside").explanationMedia()).isNull();
        assertThat(v.body().scenario().introMedia()).isNull();
        assertThat(v.body().scenario().outcomes()).allSatisfy(o -> assertThat(o.media()).isNull());
        assertThat(nodes.values()).allSatisfy(n -> assertThat(n.choices())
                .allSatisfy(c -> { assertThat(c.thumb()).isNull(); assertThat(c.media()).isNull(); }));
    }

    @Test
    void theStepTextMatchesTheApprovedPictures() {
        Version v = CATALOG.exact(FIRE, 1).orElseThrow();
        Map<String, Node> nodes = new HashMap<>();
        v.body().scenario().nodes().forEach(n -> nodes.put(n.key(), n));
        // MEDIA-028 v2 is inside the closed bedroom with a thin high haze: not "in the hall", not "thick".
        String alarm = nodes.get("alarm").body() + " " + nodes.get("alarm").media().alt();
        assertThat(alarm).contains("near the ceiling", "thin haze", "closed").doesNotContainIgnoringCase("hall")
                .doesNotContainIgnoringCase("thick").doesNotContainIgnoringCase("heavy");
        // MEDIA-029 v2 shows the back of the hand: every path's feedback says so, so it never reads as a knock.
        assertThat(nodes.get("door_check").choices())
                .allSatisfy(c -> assertThat(c.feedback()).contains("back of your hand").doesNotContainIgnoringCase("knock"));
        // The check the sources teach: the door and doorknob with the back of the hand, and smoke around the door is a
        // stop sign too (USFA pictographs; Ready.gov). The picture shows the hand not gripping or turning the knob.
        assertThat(nodes.get("door_check").choices()).filteredOn(c -> c.key().equals("feel_door")).singleElement()
                .satisfies(c -> assertThat(c.feedback()).contains("back of your hand", "doorknob", "smoke is coming around it"));
        assertThat(nodes.get("door_check").explanationMedia().alt()).contains("without gripping or turning the knob")
                .doesNotContain("untouched");
        // Gate 1 C7: both door-check paths teach the cool-door step (Ready.gov: open slowly, ready to shut it).
        assertThat(nodes.get("door_check").choices())
                .allSatisfy(c -> assertThat(c.feedback()).containsIgnoringCase("if it feels cool, open it slowly").contains("shut it quickly"));
        // Back of the hand cites the live U.S. Fire Administration pictographs page, not only the archived factsheet.
        assertThat(v.body().sources()).extracting(PracticeContent.Source::url)
                .contains("https://www.usfa.fema.gov/gallery/pictographs/escape-planning.html");
        // The wrong-answer distractors are ones the sources address, never an unsupported "don't open windows".
        assertThat(v.body().scenario().nodes().toString()).doesNotContainIgnoringCase("window");
        // No unsupported wording survives.
        assertThat(CATALOG.exact(FIRE, 1).orElseThrow().file().content().toString())
                .doesNotContain("leave the knob alone").doesNotContain("called 911 yet").doesNotContain("less than 2 minutes")
                .doesNotContain("Things can be replaced").doesNotContainIgnoringCase("count everyone");
    }

    @Test
    void routineV2OffersHouseFireAndV1StaysPublishedUntouched() {
        Version v1 = CATALOG.exact(ROUTINE, 1).orElseThrow();
        Version v2 = CATALOG.exact(ROUTINE, 2).orElseThrow();
        assertThat(v1.contentHash()).isEqualTo(ROUTINE_V1_HASH);
        assertThat(v1.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v1.body().routine().offeredBy().scenarios()).isEmpty();
        assertThat(PracticeContentValidator.validate(v2)).isEmpty();
        assertThat(v2.contentHash()).isEqualTo(ROUTINE_V2_DRAFT_HASH);
        assertThat(v2.publishState()).isEqualTo(PublishState.DRAFT);
        assertThat(v2.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.PENDING);
        assertThat(v2.body().routine().offeredBy().scenarios()).containsExactly(FIRE);
        assertThat(v2.body().routine().offeredBy().activities()).isEqualTo(v1.body().routine().offeredBy().activities());
        // v1 text verbatim: only the offer differs.
        assertThat(v2.body().routine().ifText()).isEqualTo(v1.body().routine().ifText());
        assertThat(v2.body().routine().thenTemplate()).isEqualTo(v1.body().routine().thenTemplate());
        assertThat(v2.body().routine().slots()).isEqualTo(v1.body().routine().slots());
        assertThat(v2.body().sources()).isEqualTo(v1.body().sources());
        assertThat(v2.body().title()).isEqualTo(v1.body().title());
        assertThat(v2.body().summary()).isEqualTo(v1.body().summary());
    }

    @Test
    void productionServesNeitherDraftAndPreviewServesBoth() {
        assertThat(availability(false).startable(PracticeKind.ADULT_SCENARIO))
                .noneMatch(e -> e.version().key().equals(FIRE));
        assertThat(availability(false).startable(PracticeKind.ROUTINE))
                .filteredOn(e -> e.version().key().equals(ROUTINE)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(1))
                .satisfies(e -> assertThat(e.preview()).isFalse());
        assertThat(availability(true).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(FIRE)).singleElement()
                .satisfies(e -> assertThat(e.preview()).isTrue());
        assertThat(availability(true).startable(PracticeKind.ROUTINE))
                .filteredOn(e -> e.version().key().equals(ROUTINE)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(2))
                .satisfies(e -> assertThat(e.preview()).isTrue());
    }

    @Test
    void everyPublishedVersionStillMatchesItsReviewedHashAndTheRegistryIsClean() {
        assertThat(CATALOG.rejected()).isEmpty();
        assertThat(PracticeRegistryCheck.problems(CATALOG.all(), PracticeRegistryCheck.contractDrillIds())).isEmpty();
        CATALOG.all().stream().filter(v -> v.publishState() == PublishState.PUBLISHED).forEach(v ->
                assertThat(v.file().lifecycle().safetyReview().reviewedContentHash())
                        .as(v.key() + " v" + v.version()).isEqualTo(v.contentHash()));
    }
}
