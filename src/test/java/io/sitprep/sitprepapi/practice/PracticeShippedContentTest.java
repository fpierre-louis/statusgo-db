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
import java.util.Set;
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
    private static final Map<String, String> NEXT_PRIORITY_DRAFT_HASHES = Map.of(
            "flash-flood-turn-around-route",
            "sha256:0a5e24bb845c5cbd3900086e16319275754d543474293e8dd33f163d412400f0",
            "earthquake-first-five-minutes",
            "sha256:5ee94fd79ede6107a38a6eed22ff397ebbb47529309b6708b195e58bda367edc");
    /** Published one hazard at a time (Stage C), each at its independently reviewed hash. */
    private static final Set<String> NEXT_PRIORITY_PUBLISHED =
            Set.of("flash-flood-turn-around-route", "earthquake-first-five-minutes");
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
        // Superseded by the EXEC-I5 story-touch v3 (2026-10-20): RETIRED, so pinned runs still finish.
        assertThat(lifecycle.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(lifecycle.publishedAt()).isNotNull();
        assertThat(lifecycle.retiredAt()).isNotNull();
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
    void nextPriorityScenariosAreAtTheirReviewedHashesWithTwoPartReflections() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);

        for (String key : NEXT_PRIORITY_DRAFT_HASHES.keySet()) {
            Version draft = catalog.exact(key, 1).orElseThrow();
            assertThat(PracticeContentValidator.validate(draft)).as(key).isEmpty();
            assertThat(draft.contentHash()).as(key).isEqualTo(NEXT_PRIORITY_DRAFT_HASHES.get(key));
            var review = draft.file().lifecycle().safetyReview();
            if (NEXT_PRIORITY_PUBLISHED.contains(key)) {
                // v1 was published, then superseded by its EXEC-I5 story-touch v2 (2026-10-20).
                assertThat(draft.publishState()).as(key).isEqualTo(PublishState.RETIRED);
                assertThat(review.status()).as(key).isEqualTo(SafetyReviewStatus.APPROVED);
                assertThat(review.reviewedContentHash()).as(key).isEqualTo(NEXT_PRIORITY_DRAFT_HASHES.get(key));
            } else {
                assertThat(draft.publishState()).as(key).isEqualTo(PublishState.DRAFT);
                assertThat(review.status()).as(key).isEqualTo(SafetyReviewStatus.PENDING);
            }
            assertThat(draft.body().scenario().nodes())
                    .as(key)
                    .allSatisfy(node -> assertThat(node.choices())
                            .allSatisfy(choice -> assertThat(choice.consequence()).isNotBlank()));
        }
    }

    @Test
    void nextPriorityDraftsStayOutOfProductionUntilPublished() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

        PracticeAvailabilityService production = new PracticeAvailabilityService(catalog, repo, true, false, clock);
        PracticeAvailabilityService preview = new PracticeAvailabilityService(catalog, repo, true, true, clock);

        assertThat(production.startable(PracticeKind.ADULT_SCENARIO))
                .extracting(e -> e.version().key())
                .filteredOn(NEXT_PRIORITY_DRAFT_HASHES::containsKey)
                .containsExactlyInAnyOrderElementsOf(NEXT_PRIORITY_PUBLISHED);
        // Preview lists both; it may show a newer story-touch draft (EXEC-I5) over the published v1.
        assertThat(preview.startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> NEXT_PRIORITY_DRAFT_HASHES.containsKey(e.version().key()))
                .hasSize(2);
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

    private static final String SAFE_ROOM_REVIEWED_HASH =
            "sha256:9752cf72f1486537c51f82bb01fe1c8d42150d1ba7e57da7caa3f6d75ce95344";

    @Test
    void safeRoomDetectiveIsPublishedAtTheReviewedHashWithItsSafetyCautions() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version sr = catalog.exact("safe-room-detective", 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(sr)).isEmpty();
        assertThat(sr.contentHash()).isEqualTo(SAFE_ROOM_REVIEWED_HASH);
        assertThat(sr.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(sr.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(sr.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(SAFE_ROOM_REVIEWED_HASH);
        assertThat(sr.body().activity().items()).hasSize(5).allSatisfy(i -> assertThat(i.artKey()).isNull());
        String cards = String.join(" | ", sr.body().activity().items().stream().map(PracticeContent.ActivityItem::label).toList());
        // The cautions research flagged: mobile homes, the doorway myth, leaving when told, never the attic.
        assertThat(cards).contains("Mobile homes aren't safe").contains("lowest floor").contains("inside wall").contains("avoid doorways").contains("Leave if officials tell you to");
        assertThat(cards.toLowerCase()).doesNotContain("attic").doesNotContain("doorway is").doesNotContain("stand in a doorway");
        assertThat(sr.body().facilitation()).contains("including if they tell you to leave");
        assertThat(sr.body().activity().summaryBody()).contains("Show your family each spot, in person");
    }

    @Test
    void productionListsThePublishedSafeRoomDetective() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        PracticeAvailabilityService production = new PracticeAvailabilityService(catalog, repo, true, false, clock);
        PracticeAvailabilityService preview = new PracticeAvailabilityService(catalog, repo, true, true, clock);
        for (PracticeAvailabilityService env : List.of(production, preview)) {
            assertThat(env.startable(PracticeKind.FAMILY_ACTIVITY))
                    .filteredOn(e -> e.version().key().equals("safe-room-detective")).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isFalse());
        }
    }

    private static final String MESSAGE_RELAY_REVIEWED_HASH =
            "sha256:437db0f49ef6973b8de041c50abd75294dbd42c15bebdf798ee94a8279e37a73";

    @Test
    void messageRelayIsPublishedAtTheReviewedHashWithNoFreeText() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version mr = catalog.exact("message-relay", 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(mr)).isEmpty();
        assertThat(mr.contentHash()).isEqualTo(MESSAGE_RELAY_REVIEWED_HASH);
        assertThat(mr.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(mr.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(mr.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(MESSAGE_RELAY_REVIEWED_HASH);
        assertThat(mr.body().activity().items()).hasSize(6).allSatisfy(i -> assertThat(i.artKey()).isNull());
        assertThat(mr.body().facilitation()).contains("Nothing is sent from this activity").contains("private text to family only");
        assertThat(mr.body().sources()).noneMatch(src -> src.url().contains("fcc.gov")); // unverifiable from review hosts; Ready Kids carries the claim
        // Unsourced extras are labelled as SitPrep's own suggestion, never attributed to Ready.gov/FCC.
        assertThat(mr.body().activity().recapBody()).contains("SitPrep's suggestion, not an official rule")
                .contains("unless you are in danger, send a text");
    }

    @Test
    void productionListsThePublishedMessageRelay() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        PracticeAvailabilityService production = new PracticeAvailabilityService(catalog, repo, true, false, clock);
        PracticeAvailabilityService preview = new PracticeAvailabilityService(catalog, repo, true, true, clock);
        for (PracticeAvailabilityService env : List.of(production, preview)) {
            assertThat(env.startable(PracticeKind.FAMILY_ACTIVITY))
                    .filteredOn(e -> e.version().key().equals("message-relay")).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isFalse());
        }
    }

    /** Production starts v2 now; a run already pinned to the retired v1 can still finish. */
    @Test
    void productionStartsTheNewestAndRunsPinnedToOlderVersionsStillFinish() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v1 = catalog.exact(COMMS, 1).orElseThrow();
        Version v2 = catalog.exact(COMMS, 2).orElseThrow();
        // v4 = EXEC-M (reviewed v3 text + hashed stills, 2026-10-20); v1-v3 are RETIRED.
        assertThat(catalog.latestStartable(COMMS, false)).map(Version::version).contains(4);

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        PracticeAvailabilityService prod = new PracticeAvailabilityService(catalog, repo, true, false,
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC));
        assertThat(prod.startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(COMMS)).singleElement()
                .satisfies(e -> {
                    assertThat(e.version().version()).isEqualTo(4);
                    assertThat(e.preview()).isFalse();
                });
        assertThat(prod.checkStart(COMMS).entry().version().version()).isEqualTo(4);
        Version v3 = catalog.exact(COMMS, 3).orElseThrow();
        assertThat(prod.checkRun(COMMS, 3, v3.contentHash()).canContinue()).isTrue();
        assertThat(prod.checkRun(COMMS, 1, v1.contentHash()).canContinue()).isTrue();
        assertThat(prod.checkRun(COMMS, 2, v2.contentHash()).canContinue()).isTrue();
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

    // -- EXEC-I5: story-touch drafts ----------------------------------------

    /** key → [draft version, published version it extends, draft hash]. */
    private static final Map<String, Object[]> STORY_DRAFTS = Map.of(
            "comms-outage-family-reconnect", new Object[] {3, 2, "sha256:caa0718aa542b55a7aa56b358c797132ee7f619dd4feac52002082949df7aa25"},
            "flash-flood-turn-around-route", new Object[] {2, 1, "sha256:9f4b5036959c0c30893da159391c76fe9753055dcffa4edfea62d41af0246952"},
            "earthquake-first-five-minutes", new Object[] {2, 1, "sha256:dee4313256c6f90bd191082a207fa76606277eb2c2cc228508016140713bc422"});

    /** The only non-story edits: a body whose quote moved into its message bubble. */
    private static final Set<String> STORY_BODY_EDITS = Set.of(
            "comms-outage-family-reconnect.where_to_meet", "comms-outage-family-reconnect.rumor");

    @Test
    void storyDraftsAddOnlySceneAndMessagesToThePublishedVersion() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        STORY_DRAFTS.forEach((key, d) -> {
            Version draft = catalog.exact(key, (int) d[0]).orElseThrow();
            Version base = catalog.exact(key, (int) d[1]).orElseThrow();
            assertThat(PracticeContentValidator.validate(draft)).as(key).isEmpty();
            assertThat(draft.contentHash()).as(key).isEqualTo(d[2]);
            // Published at EXEC-I5; Comms v3 has since been superseded by the media v4 (RETIRED).
            assertThat(draft.publishState()).as(key).isIn(PublishState.PUBLISHED, PublishState.RETIRED);
            assertThat(draft.file().lifecycle().safetyReview().status()).as(key).isEqualTo(SafetyReviewStatus.APPROVED);
            assertThat(draft.file().lifecycle().safetyReview().reviewedContentHash()).as(key).isEqualTo(d[2]);
            assertThat(base.publishState()).as(key + " base").isEqualTo(PublishState.RETIRED);

            var db = draft.body();
            var bb = base.body();
            assertThat(db.title()).as(key).isEqualTo(bb.title());
            assertThat(db.summary()).as(key).isEqualTo(bb.summary());
            assertThat(db.objective()).as(key).isEqualTo(bb.objective());
            assertThat(db.sources()).as(key).isEqualTo(bb.sources());
            assertThat(db.tags()).as(key).isEqualTo(bb.tags());
            assertThat(db.scenario().outcomes()).as(key).isEqualTo(bb.scenario().outcomes());
            assertThat(db.scenario().startNode()).as(key).isEqualTo(bb.scenario().startNode());
            assertThat(db.scenario().nodes()).as(key).hasSameSizeAs(bb.scenario().nodes());
            for (int i = 0; i < db.scenario().nodes().size(); i++) {
                Node dn = db.scenario().nodes().get(i);
                Node bn = bb.scenario().nodes().get(i);
                String at = key + "." + dn.key();
                assertThat(dn.scene()).as(at + " scene").isNotBlank();
                assertThat(dn.key()).as(at).isEqualTo(bn.key());
                assertThat(dn.title()).as(at).isEqualTo(bn.title());
                assertThat(dn.prompt()).as(at).isEqualTo(bn.prompt());
                assertThat(dn.choices()).as(at + " choices (labels, consequences, whys, tags, next)").isEqualTo(bn.choices());
                if (!STORY_BODY_EDITS.contains(at)) assertThat(dn.body()).as(at + " body").isEqualTo(bn.body());
            }
        });
    }

    @Test
    void productionStartsTheStoryVersions() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        PracticeAvailabilityService production = new PracticeAvailabilityService(catalog, repo, true, false, clock);
        STORY_DRAFTS.forEach((key, d) -> assertThat(production.startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(key))
                .singleElement()
                .satisfies(e -> assertThat(e.version().version()).as(key)
                        .isEqualTo(key.equals(COMMS) ? 4 : key.equals(EARTHQUAKE) ? 4 : (int) d[0]))); // Comms -> EXEC-M v4, Earthquake -> branching v3
    }

    // -- EXEC-I6: Tornado draft ---------------------------------------------

    static final String TORNADO = "tornado-warning-shelter-decision";
    static final String TORNADO_DRAFT_HASH = "sha256:e9722cca5ff35d7404334100e827021dc437b3090f73d0a091d580959201c954";

    @Test
    void tornadoIsPublishedAtItsReviewedHashWithAStoryOnEveryStep() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version t = catalog.exact(TORNADO, 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(t)).isEmpty();
        assertThat(t.contentHash()).isEqualTo(TORNADO_DRAFT_HASH);
        assertThat(t.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(t.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(t.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(TORNADO_DRAFT_HASH);
        assertThat(t.body().scenario().nodes()).hasSize(3).allSatisfy(n -> {
            assertThat(n.scene()).isNotBlank();
            assertThat(n.choices()).hasSize(3).allSatisfy(c -> assertThat(c.consequence()).isNotBlank());
        });
        assertThat(t.body().sources()).allSatisfy(src ->
                assertThat(src.url()).matches("https://www\\.(weather|ready)\\.gov/.*")); // raw-verifiable hosts only

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PracticeAvailabilityService(catalog, repo, true, false, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(TORNADO)).singleElement()
                .satisfies(e -> assertThat(e.preview()).isFalse());
    }

    // -- EXEC-M: Comms v4 = reviewed v3 text + hashed stills ------------------

    static final String COMMS_V4_DRAFT_HASH = "sha256:75cc5709c6edcd2b2d84dce577c5d9ced8bab673a3093e85f07b9a8e2916b654";

    @Test
    void commsV4AddsOnlyHashedStillsAndAfternoonScenesToTheReviewedV3() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v4 = catalog.exact(COMMS, 4).orElseThrow();
        Version v3 = catalog.exact(COMMS, 3).orElseThrow();
        assertThat(PracticeContentValidator.validate(v4)).isEmpty();
        assertThat(v4.contentHash()).isEqualTo(COMMS_V4_DRAFT_HASH);
        assertThat(v4.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v4.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(COMMS_V4_DRAFT_HASH);
        assertThat(v3.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v4.file().schema()).isEqualTo(2);
        assertThat(v4.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.REALISM);

        var b4 = v4.body(); var b3 = v3.body();
        assertThat(b4.title()).isEqualTo(b3.title());
        assertThat(b4.summary()).isEqualTo(b3.summary());
        assertThat(b4.sources()).isEqualTo(b3.sources());
        assertThat(b4.tags()).isEqualTo(b3.tags());
        for (int i = 0; i < b4.scenario().nodes().size(); i++) {
            Node n4 = b4.scenario().nodes().get(i), n3 = b3.scenario().nodes().get(i);
            assertThat(n4.key()).isEqualTo(n3.key());
            assertThat(n4.title()).isEqualTo(n3.title());
            assertThat(n4.body()).isEqualTo(n3.body());
            assertThat(n4.prompt()).isEqualTo(n3.prompt());
            assertThat(n4.messages()).isEqualTo(n3.messages());
            assertThat(n4.choices()).as(n4.key() + " choices unchanged, no reaction media").isEqualTo(n3.choices());
            if (n4.key().equals("finding_numbers")) {
                assertThat(n4.media()).as("finding_numbers: no still (review: the table objects pre-taught the card)").isNull();
                continue;
            }
            assertThat(n4.media()).as(n4.key()).isNotNull();
            assertThat(ScenarioService.mediaDto(n4.media()).url()).as(n4.key() + " resolves").startsWith("/images/realism/");
        }
        for (int i = 0; i < b4.scenario().outcomes().size(); i++) {
            var o4 = b4.scenario().outcomes().get(i); var o3 = b3.scenario().outcomes().get(i);
            assertThat(o4.title()).isEqualTo(o3.title());
            assertThat(o4.body()).isEqualTo(o3.body());
            assertThat(o4.media()).isNotNull();
            assertThat(ScenarioService.mediaDto(o4.media()).alt()).isNotBlank();
        }
        // No ending reads as the reward: both share one still.
        assertThat(b4.scenario().outcomes()).extracting(o -> o.media().assetSha256()).containsOnly(
                b4.scenario().outcomes().get(0).media().assetSha256());
        assertThat(catalog.latestStartable(COMMS, false)).map(Version::version).contains(4);
    }

    // -- Earthquake v3: a mid-shaking branch and two calm endings (handoff Phase 5) --

    static final String EARTHQUAKE = "earthquake-first-five-minutes";
    static final String EARTHQUAKE_V3_DRAFT_HASH = "sha256:3f76d021e87eeb805cb0357701b1775553465325e09a963b4df63f1cadff2b10";

    @Test
    void earthquakeV3IsPublishedAtItsReviewedHashBranchingMidShakingWithTwoCalmEndings() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v3 = catalog.exact(EARTHQUAKE, 3).orElseThrow();
        Version v2 = catalog.exact(EARTHQUAKE, 2).orElseThrow();
        assertThat(PracticeContentValidator.validate(v3)).isEmpty();
        assertThat(v3.contentHash()).isEqualTo(EARTHQUAKE_V3_DRAFT_HASH);
        assertThat(v3.publishState()).isEqualTo(PublishState.RETIRED); // superseded by the audited v4
        assertThat(v3.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(v3.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(EARTHQUAKE_V3_DRAFT_HASH);
        assertThat(v2.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v3.body().sources()).isEqualTo(v2.body().sources()); // Ready.gov + Ready Kids only
        assertThat(v3.body().tags()).isEqualTo(v2.body().tags());

        var sc = v3.body().scenario();
        Map<String, Node> nodes = new HashMap<>();
        sc.nodes().forEach(n -> nodes.put(n.key(), n));
        assertThat(nodes.get("shaking_starts").choices())
                .filteredOn(c -> !c.key().equals("drop_cover_hold"))
                .allSatisfy(c -> assertThat(c.next()).isEqualTo("still_shaking"));
        assertThat(sc.nodes()).allSatisfy(n -> assertThat(n.scene()).isNotBlank());
        assertThat(longestPath(sc.startNode(), nodes)).as("decision steps on the longest path").isLessThanOrEqualTo(4);

        // Two endings by where you finish, not by how you did: same title, both calm, both open the drill.
        assertThat(sc.outcomes()).hasSize(2);
        assertThat(sc.outcomes()).extracting(PracticeContent.Outcome::title).containsOnly(sc.outcomes().get(0).title());
        assertThat(sc.outcomes()).allSatisfy(o -> assertThat(o.body()).contains("talk it over together"));
    }

    // -- Earthquake v4: retrospective audit of live v3 ------------------------

    static final String EARTHQUAKE_V4_DRAFT_HASH = "sha256:2569733145eae2b04e9c875885cc62ee5944be4974e7d3ec1c67d6f63a83cd4d";

    @Test
    void earthquakeV4KeepsTheV3GraphAndSaysTheInsideOutsideRuleInBothEndings() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v4 = catalog.exact(EARTHQUAKE, 4).orElseThrow();
        Version v3 = catalog.exact(EARTHQUAKE, 3).orElseThrow();
        assertThat(PracticeContentValidator.validate(v4)).isEmpty();
        assertThat(v4.contentHash()).isEqualTo(EARTHQUAKE_V4_DRAFT_HASH);
        assertThat(v4.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v4.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(v4.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(EARTHQUAKE_V4_DRAFT_HASH);
        assertThat(v4.body().tags()).isEqualTo(v3.body().tags());
        assertThat(v4.body().sources()).containsAll(v3.body().sources())
                .allSatisfy(src -> assertThat(src.url()).matches("https://www\\.(ready\\.gov|earthquakecountry\\.org)/.*"));

        // Same graph: same nodes, same choices per node (order may differ), same edges.
        Map<String, Node> n3 = new HashMap<>(), n4 = new HashMap<>();
        v3.body().scenario().nodes().forEach(n -> n3.put(n.key(), n));
        v4.body().scenario().nodes().forEach(n -> n4.put(n.key(), n));
        assertThat(n4.keySet()).isEqualTo(n3.keySet());
        n4.forEach((k, n) -> assertThat(edges(n)).as(k).isEqualTo(edges(n3.get(k))));

        // The protective option is not always listed first.
        assertThat(v4.body().scenario().nodes()).extracting(n -> n.choices().get(0).tags())
                .anySatisfy(tags -> assertThat(tags).contains("needs_earthquake_drill"));
        var outcomes = v4.body().scenario().outcomes();
        assertThat(outcomes).extracting(PracticeContent.Outcome::title).containsOnly(outcomes.get(0).title());
        assertThat(outcomes).allSatisfy(o -> assertThat(o.body())
                .contains("if you are inside, stay and do not run outside, and if you are outside, stay there")
                .contains("talk it over together"));
        assertThat(n4.get("aftershock").choices()).filteredOn(c -> c.key().equals("protect_again"))
                .singleElement().satisfies(c -> assertThat(c.label()).contains("lock wheels"));

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PracticeAvailabilityService(catalog, repo, true, false, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(EARTHQUAKE)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).as("production starts v4; runs pinned to v3 and v2 still finish").isEqualTo(4));
    }

    private static Map<String, String> edges(Node n) {
        Map<String, String> out = new HashMap<>();
        n.choices().forEach(c -> out.put(c.key(), c.next() + " " + c.tags()));
        return out;
    }

    /**
     * Every adult scenario that can be started or finished: every step on every
     * path offers at least one choice that earns a strong debrief line, and
     * every path ends at an outcome. The validator already rejects cycles and
     * unreachable nodes; this guards that no branch skips the protective action.
     */
    @Test
    void everyStepOfEveryAdultScenarioOffersAProtectiveChoice() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        List<Version> scenarios = catalog.all().stream()
                .filter(v -> v.file().kind() == PracticeKind.ADULT_SCENARIO)
                .filter(v -> v.publishState() == PublishState.PUBLISHED || v.publishState() == PublishState.RETIRED
                        || v.publishState() == PublishState.DRAFT)
                .toList();
        assertThat(scenarios).isNotEmpty();
        for (Version v : scenarios) {
            String at = v.key() + " v" + v.version();
            Map<String, Node> nodes = new HashMap<>();
            v.body().scenario().nodes().forEach(n -> nodes.put(n.key(), n));
            Set<String> outcomes = new java.util.HashSet<>();
            v.body().scenario().outcomes().forEach(o -> outcomes.add(o.key()));
            for (Node n : nodes.values()) {
                assertThat(n.choices()).as(at + " " + n.key() + " offers a strong choice").anySatisfy(c ->
                        assertThat(c.tags()).anySatisfy(t -> assertThat(DebriefTag.fromWire(t))
                                .hasValueSatisfying(d -> assertThat(d.polarity()).isEqualTo(DebriefTag.Polarity.STRONG))));
                n.choices().forEach(c -> assertThat(nodes.containsKey(c.next()) || outcomes.contains(c.next()))
                        .as(at + " " + n.key() + "." + c.key() + " leads somewhere").isTrue());
            }
        }
    }

    private static int longestPath(String key, Map<String, Node> nodes) {
        Node n = nodes.get(key);
        if (n == null) return 0; // an outcome
        return 1 + n.choices().stream().mapToInt(c -> longestPath(c.next(), nodes)).max().orElse(0);
    }
}
