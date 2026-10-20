package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Choice;
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

/**
 * The build gate for real content: every file under
 * {@code src/main/resources/practice/content} must load and validate. At
 * runtime an invalid file is only excluded and logged; here it fails the
 * build, which is where it should be caught.
 */
class PracticeShippedContentTest {

    @Test
    void everyShippedVersionValidates() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        assertThat(catalog.rejected()).as("rejected practice content").isEmpty();
    }

    /**
     * Comms Outage v1 was approved at this hash and is RETIRED since v2 shipped
     * (2026-10-10): no new run starts on it, runs pinned to it finish. Adding
     * the optional {@code consequence} field must not move that hash or give v1
     * a consequence — the client falls back to feedback alone.
     */
    @Test
    void commsOutageV1KeepsItsReviewedHashAndHasNoConsequences() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContent.Version v1 = catalog.exact("comms-outage-family-reconnect", 1).orElseThrow();
        assertThat(v1.contentHash())
                .isEqualTo("sha256:6c6346a3f56315aefa29adec7dbc95bb8e83603d5570896cc652ffa00a837543")
                .isEqualTo(v1.file().lifecycle().safetyReview().reviewedContentHash());
        assertThat(v1.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v1.file().lifecycle().retiredAt()).isNotNull();
        assertThat(v1.body().scenario().nodes())
                .allSatisfy(n -> assertThat(n.choices()).allSatisfy(c -> assertThat(c.consequence()).isNull()));
    }

    private static final String COMMS = "comms-outage-family-reconnect";
    private static final String KIT_BUILDER_REVIEWED_HASH =
            "sha256:b14907d09e98a713572ce4de7f6a78503f71af516c05736896f728ab4db666c2";

    /**
     * The hash the v2 review packet (SitPrep FE
     * docs/epics/readiness/scenarios-and-youth/EXEC-D2-content-v2-review-packet.md)
     * shows the owner. Any edit to v2 fails here first: regenerate the packet
     * with the new hash before asking for review again.
     */
    static final String COMMS_V2_DRAFT_HASH =
            "sha256:8a370c0f555643f8701712f66c4f6ecb1c03c816bf66b1a9e81952871571bb5d";

    /**
     * Comms Outage v2 (spec Q10: two-part reflections) is PUBLISHED: the owner
     * approved it on 2026-10-10 against the review packet at exactly this hash.
     * It must validate clean and change copy only: the graph, tags and sources
     * are v1's.
     */
    @Test
    void commsOutageV2IsPublishedAtItsApprovedHashWithAConsequenceOnEveryChoice() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v1 = catalog.exact(COMMS, 1).orElseThrow();
        Version v2 = catalog.exact(COMMS, 2).orElseThrow();

        assertThat(PracticeContentValidator.validate(v2)).isEmpty();
        assertThat(v2.contentHash()).isEqualTo(COMMS_V2_DRAFT_HASH);

        var lifecycle = v2.file().lifecycle();
        assertThat(lifecycle.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(lifecycle.publishedAt()).isNotNull();
        assertThat(lifecycle.retiredAt()).isNull();
        var review = lifecycle.safetyReview();
        assertThat(review.status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(review.reviewedBy()).contains("product owner");
        assertThat(review.reviewedContentHash()).isEqualTo(COMMS_V2_DRAFT_HASH);

        List<Choice> v2Choices = v2.body().scenario().nodes().stream().flatMap(n -> n.choices().stream()).toList();
        assertThat(v2Choices).hasSize(13).allSatisfy(c -> {
            assertThat(c.consequence()).as(c.key()).isNotBlank();
            assertThat(c.consequence().trim().split("\\s+")).as("%s consequence words (spec Q10: <= 15)", c.key())
                    .hasSizeLessThanOrEqualTo(15);
        });

        // Copy changes only: same nodes, choices, edges, tags, actions and sources as v1.
        assertThat(shape(v2)).isEqualTo(shape(v1));
        assertThat(v2.body().sources()).isEqualTo(v1.body().sources());
        assertThat(v2.body().tags()).isEqualTo(v1.body().tags());
    }

    @Test
    void kitBuilderIsPublishedAtTheIndependentlyReviewedHash() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version kit = catalog.exact("kit-builder", 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(kit)).isEmpty();
        assertThat(kit.contentHash()).isEqualTo(KIT_BUILDER_REVIEWED_HASH);
        assertThat(kit.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(KIT_BUILDER_REVIEWED_HASH);
        assertThat(kit.kind()).isEqualTo(PracticeKind.FAMILY_ACTIVITY);
        assertThat(kit.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(kit.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(kit.body().activity().items()).hasSize(10);
    }

    @Test
    void productionListsThePublishedKitBuilderAsReal() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        PracticeAvailabilityService production = new PracticeAvailabilityService(catalog, repo, true, false, clock);
        PracticeAvailabilityService preview = new PracticeAvailabilityService(catalog, repo, true, true, clock);

        // Published: real in production and in preview builds alike, so its completions are stored.
        for (PracticeAvailabilityService env : List.of(production, preview)) {
            assertThat(env.startable(PracticeKind.FAMILY_ACTIVITY))
                    .filteredOn(e -> e.version().key().equals("kit-builder")).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isFalse());
        }
    }

    private static final String WEATHER_WATCH_REVIEWED_HASH =
            "sha256:96e2e4ef73f9cd61ce0b1198b9e09da58958f1aff092c149d90b2a602818af3f";

    @Test
    void weatherWatchIsPublishedAtTheReviewedHashWithNoArtInTheDecisionLoop() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version ww = catalog.exact("weather-watch", 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(ww)).isEmpty();
        assertThat(ww.contentHash()).isEqualTo(WEATHER_WATCH_REVIEWED_HASH);
        assertThat(ww.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(ww.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(ww.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(WEATHER_WATCH_REVIEWED_HASH);
        assertThat(ww.body().activity().items()).hasSize(6)
                .allSatisfy(i -> {
                    assertThat(i.artKey()).isNull();
                    assertThat(i.label()).startsWith("Practice card");
                });
        // "All clear" is not an NWS product; the ended card says ended, and the recap says re-check.
        assertThat(ww.body().activity().items()).extracting(PracticeContent.ActivityItem::label)
                .noneMatch(l -> l.toLowerCase().contains("all clear") || l.toLowerCase().contains("safe"));
        assertThat(ww.body().activity().recapBody()).contains("the danger hasn't always passed");
    }

    @Test
    void productionListsThePublishedWeatherWatchAsReal() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        PracticeAvailabilityService production = new PracticeAvailabilityService(catalog, repo, true, false, clock);
        PracticeAvailabilityService preview = new PracticeAvailabilityService(catalog, repo, true, true, clock);

        for (PracticeAvailabilityService env : List.of(production, preview)) {
            assertThat(env.startable(PracticeKind.FAMILY_ACTIVITY))
                    .filteredOn(e -> e.version().key().equals("weather-watch")).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isFalse()); // real, so completions are stored
        }
    }

    /** Production starts v2 now; a run already pinned to the retired v1 can still finish. */
    @Test
    void productionStartsV2AndARunPinnedToV1StillFinishes() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v1 = catalog.exact(COMMS, 1).orElseThrow();
        assertThat(catalog.latestStartable(COMMS, false)).map(Version::version).contains(2);

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        PracticeAvailabilityService prod = new PracticeAvailabilityService(catalog, repo, true, false,
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
        assertThat(prod.startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(COMMS)).singleElement()
                .satisfies(e -> {
                    assertThat(e.version().version()).isEqualTo(2);
                    assertThat(e.preview()).isFalse();
                });
        assertThat(prod.checkStart(COMMS).entry().version().version()).isEqualTo(2);
        assertThat(prod.checkRun(COMMS, 1, v1.contentHash()).canContinue()).isTrue();
    }

    /** Node keys, choice keys, next pointers, tags and actions: everything but the words. */
    private static List<String> shape(Version v) {
        var s = v.body().scenario();
        List<String> out = new java.util.ArrayList<>();
        out.add("start=" + s.startNode());
        for (Node n : s.nodes()) {
            for (Choice c : n.choices()) out.add(n.key() + "." + c.key() + "->" + c.next() + " " + c.tags());
        }
        s.outcomes().forEach(o -> out.add("outcome " + o.key() + " " + o.defaultAction() + " " + o.defaultParams()));
        return out;
    }

    @Test
    void aKeyNeverChangesKindAcrossVersions() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Map<String, PracticeKind> kinds = new HashMap<>();
        catalog.all().forEach(v -> {
            PracticeKind prior = kinds.putIfAbsent(v.key(), v.kind());
            assertThat(prior == null || prior == v.kind())
                    .as("key %s changes kind between versions", v.key()).isTrue();
        });
    }
}
