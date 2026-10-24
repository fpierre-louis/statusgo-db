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
                        .isEqualTo(key.equals(COMMS) ? 4 : key.equals(EARTHQUAKE) ? 5 : key.equals(FLOOD) ? 4 : (int) d[0]))); // Comms -> EXEC-M v4, Earthquake -> v5 (Gate 1 C2), Flood -> v4 (Gate 1 C1), 2026-10-23
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
        assertThat(t.publishState()).isEqualTo(PublishState.RETIRED); // superseded by v2 (2026-10-20)
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
                .satisfies(e -> assertThat(e.preview()).isFalse())
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(2));
    }

    // -- EXEC-M3: Tornado v3 = v2 text verbatim + intro and ending stills (DRAFT) ----

    static final String TORNADO_V2_HASH = "sha256:83619bb763e6a52285b794584e553a72d3706eb2040ccf22215dcb666302b3f5";
    static final String TORNADO_V3_DRAFT_HASH = "sha256:6e98b075f086ee272d27a741dbc0256e0f20457e9d458751eb309b72e1a62b67";

    private static com.fasterxml.jackson.databind.node.ObjectNode tornadoFile(int v) throws java.io.IOException {
        return (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/resources/practice/content/tornado-warning-shelter-decision/v" + v + ".json")));
    }

    @Test
    void tornadoV3IsADraftThatAddsOnlyMediaToV2AndV2IsUntouched() throws Exception {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v2 = catalog.exact(TORNADO, 2).orElseThrow();
        Version v3 = catalog.exact(TORNADO, 3).orElseThrow();
        assertThat(PracticeContentValidator.validate(v3)).isEmpty();
        assertThat(v2.contentHash()).isEqualTo(TORNADO_V2_HASH);
        assertThat(v2.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v3.contentHash()).isEqualTo(TORNADO_V3_DRAFT_HASH);
        assertThat(v3.publishState()).isEqualTo(PublishState.DRAFT);
        assertThat(v3.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.PENDING);
        assertThat(v3.file().lifecycle().safetyReview().notes())
                .contains("MEDIA-017 v2", "MEDIA-018", "MEDIA-019", "tornado.rooms.outside", "MEDIA-021 v3", "PLANNED EMPTY SLOTS")
                .doesNotContain("SCENE warning_issued <- MEDIA-017");

        // Text verbatim: strip the media additions from v3 and the tree equals v2's content.
        var c2 = tornadoFile(2).get("content");
        var c3 = tornadoFile(3).get("content").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) c3).remove("mediaStyle");
        ((com.fasterxml.jackson.databind.node.ObjectNode) c3.get("scenario")).remove("introMedia");
        c3.get("scenario").get("nodes").forEach(n -> ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove(java.util.List.of("media", "explanationMedia")));
        c3.get("scenario").get("outcomes").forEach(o -> ((com.fasterxml.jackson.databind.node.ObjectNode) o).remove("media"));
        assertThat(c3).isEqualTo(c2);

        // Media (EXEC-M4): the intro, the warning_issued SCENE (MEDIA-017 v2) and the single outcome's ending, plus the in_shelter EXPLANATION (MEDIA-021 v3); no thumb yet.
        assertThat(v3.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.ILLUSTRATION);
        assertThat(v3.body().scenario().introMedia().assetSha256()).endsWith("3cbf96be93c67f4e9c466159e79ed94b0160997e031df3d7806d2f142f6b08cf");
        assertThat(v3.body().scenario().outcomes()).singleElement()
                .satisfies(o -> assertThat(o.media().assetSha256()).endsWith("2909a881eaf03d2d231861d65bc2050ff38cc2b001d5b174b15144fb62eb385f"));
        assertThat(v3.body().scenario().nodes()).allSatisfy(n -> {
            if (n.key().equals("warning_issued")) {
                assertThat(n.media().slotKey()).isEqualTo("tornado-warning-shelter-decision.warning_issued.scene");
                assertThat(n.media().assetSha256()).endsWith("659b8f0ec14c3d9e25e256c15c8639be78494f36c3042897b529b4018c24a724");
            } else {
                assertThat(n.media()).isNull();
            }
            if (n.key().equals("in_shelter")) {
                assertThat(n.explanationMedia().slotKey()).isEqualTo("tornado-warning-shelter-decision.in_shelter.explanation");
                assertThat(n.explanationMedia().assetSha256()).endsWith("c0952566b34c3474c730d29e1b334f4838a11d0ccd8e16e60032a055ef8830ff");
                assertThat(n.explanationMedia().alt()).hasSizeLessThanOrEqualTo(200)
                        .contains("neck", "blanket", "windowless");
            } else {
                assertThat(n.explanationMedia()).isNull();
            }
            assertThat(n.choices()).allSatisfy(ch -> assertThat(ch.thumb()).isNull());
        });
    }

    @Test
    void productionStillStartsTornadoV2WhileV3IsPreviewOnly() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PracticeAvailabilityService(catalog, repo, true, false, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(TORNADO)).singleElement()
                .satisfies(e -> assertThat(e.preview()).isFalse())
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(2));
        assertThat(new PracticeAvailabilityService(catalog, repo, true, true, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(TORNADO)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(3))
                .satisfies(e -> assertThat(e.preview()).isTrue());
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

    // -- EXEC-M7: Comms v5 = v4 text + MEDIA-030 intro + MEDIA-031 ending (DRAFT) ----

    static final String COMMS_V5_DRAFT_HASH = "sha256:10be289accac8e014cb3ed05e785cbaed0f7d2417d901b43a55d9660be798912";

    /** Strips the two EXEC-M7 additions: the intro and the ending assets (slot keys stay). */
    private static com.fasterxml.jackson.databind.JsonNode withoutCommsV5Media(com.fasterxml.jackson.databind.JsonNode content) {
        var c = (com.fasterxml.jackson.databind.node.ObjectNode) content.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) c.get("scenario")).remove("introMedia");
        c.get("scenario").get("outcomes").forEach(o -> {
            var m = (com.fasterxml.jackson.databind.node.ObjectNode) o.get("media");
            m.remove("assetSha256");
            m.remove("alt");
        });
        return c;
    }

    @Test
    void commsV5IsADraftThatOnlyAddsTheIntroAndSwapsTheEndingAndV4IsUntouched() throws Exception {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v4 = catalog.exact(COMMS, 4).orElseThrow();
        Version v5 = catalog.exact(COMMS, 5).orElseThrow();
        assertThat(PracticeContentValidator.validate(v5)).isEmpty();
        assertThat(v4.contentHash()).isEqualTo(COMMS_V4_DRAFT_HASH);
        assertThat(v4.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v5.contentHash()).isEqualTo(COMMS_V5_DRAFT_HASH);
        assertThat(v5.publishState()).isEqualTo(PublishState.DRAFT);
        assertThat(v5.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.PENDING);
        assertThat(v5.file().lifecycle().safetyReview().reviewedContentHash()).isNull();
        assertThat(v5.file().lifecycle().safetyReview().notes()).contains("MEDIA-030", "MEDIA-031", "0fe5b545", "Gate 1");
        assertThat(v5.file().schema()).isEqualTo(2);
        assertThat(v5.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.REALISM);

        // Text and scene stills verbatim: strip the intro and the ending assets and the trees are equal.
        assertThat(withoutCommsV5Media(contentFile(COMMS, 5).get("content")))
                .isEqualTo(withoutCommsV5Media(contentFile(COMMS, 4).get("content")));
        var n4 = v4.body().scenario().nodes();
        var n5 = v5.body().scenario().nodes();
        for (int i = 0; i < n5.size(); i++) {
            assertThat(n5.get(i).media()).as(n5.get(i).key() + " scene still kept").isEqualTo(n4.get(i).media());
        }

        assertThat(v4.body().scenario().introMedia()).isNull();
        assertStill(v5.body().scenario().introMedia(), "comms-outage-family-reconnect.intro",
                "e6baa291194114e3cc4cb492863469887bbd1bc884d05917449d9765ed8e8758");
        assertThat(v5.body().scenario().introMedia().alt()).hasSizeLessThanOrEqualTo(200);
        assertThat(v5.body().scenario().outcomes()).hasSize(2).allSatisfy(o -> {
            assertThat(o.media().assetSha256()).isEqualTo(
                    "sha256:d7133dc01fddb7b280fe29831aac2ce0a772916513b3f97f1dd5d4ff171e4cb5");
            assertThat(o.media().alt()).hasSizeLessThanOrEqualTo(200);
        });
        assertThat(v5.body().scenario().outcomes()).extracting(o -> o.media().slotKey()).containsExactly(
                "comms-outage-family-reconnect.reconnected.ending",
                "comms-outage-family-reconnect.reconnected_check_sources.ending");
        assertThat(ScenarioService.mediaDto(v5.body().scenario().introMedia()).url())
                .startsWith("/images/practice/comms-intro-e6baa291");
        assertThat(ScenarioService.mediaDto(v5.body().scenario().outcomes().get(0).media()).url())
                .startsWith("/images/practice/comms-reconnected-d7133dc0");
    }

    @Test
    void productionStartsCommsV4WhileV5IsPreviewOnly() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        assertThat(availability(catalog, false).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(COMMS)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(4))
                .satisfies(e -> assertThat(e.preview()).isFalse());
        assertThat(availability(catalog, true).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(COMMS)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).isEqualTo(5))
                .satisfies(e -> assertThat(e.preview()).isTrue());
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
        // Retired 2026-10-23 when v5 (v4 text + four aligned scene labels + approved media) was published.
        assertThat(v4.publishState()).isEqualTo(PublishState.RETIRED);
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
                .satisfies(e -> assertThat(e.version().version()).as("production starts v5 (v4 text + aligned labels + approved media); runs pinned to v2-v4 still finish").isEqualTo(5));
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

    // -- Flash Flood v3: a second low spot (warning meaning, walking) -----------

    static final String FLOOD = "flash-flood-turn-around-route";
    static final String FLOOD_V3_DRAFT_HASH = "sha256:60992c7a449e2cf7bf0840f4796dbc1916fdaf322bce1d244f75c49295208fae";

    @Test
    void flashFloodV3BranchesToASecondLowSpotAndKeepsOneEnding() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v3 = catalog.exact(FLOOD, 3).orElseThrow();
        assertThat(PracticeContentValidator.validate(v3)).isEmpty();
        assertThat(v3.contentHash()).isEqualTo(FLOOD_V3_DRAFT_HASH);
        // Retired 2026-10-23 when v4 (same text + approved media) was published.
        assertThat(v3.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v3.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(FLOOD_V3_DRAFT_HASH);
        assertThat(catalog.exact(FLOOD, 2).orElseThrow().publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v3.body().sources()).allSatisfy(src -> assertThat(src.url()).startsWith("https://www.weather.gov/safety/"));

        var sc = v3.body().scenario();
        Map<String, Node> nodes = new HashMap<>();
        sc.nodes().forEach(n -> nodes.put(n.key(), n));
        assertThat(nodes.get("choose_route").choices())
                .filteredOn(c -> !c.key().equals("official_updates"))
                .allSatisfy(c -> assertThat(c.next()).isEqualTo("second_low_spot"));
        assertThat(nodes.get("second_low_spot").choices()).extracting(Choice::key)
                .contains("walk_to_check", "wait_by_creek", "turn_back_official");
        assertThat(longestPath(sc.startNode(), nodes)).isLessThanOrEqualTo(4);
        assertThat(sc.outcomes()).singleElement().satisfies(o -> assertThat(o.body())
                .doesNotContain("three decision points").contains("talk it over together"));

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PracticeAvailabilityService(catalog, repo, true, false, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(FLOOD)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).as("production starts v4 (v3 text + approved media); runs pinned to v2/v3 still finish").isEqualTo(4));
    }

    // -- Tornado v2: the storm gets closer (no more silent move to the bathroom) --

    static final String TORNADO_V2_DRAFT_HASH = "sha256:83619bb763e6a52285b794584e553a72d3706eb2040ccf22215dcb666302b3f5";

    @Test
    void tornadoV2GivesUpstairsAndOutsideAConsequenceInsteadOfMovingEveryoneToTheBathroom() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v2 = catalog.exact(TORNADO, 2).orElseThrow();
        assertThat(PracticeContentValidator.validate(v2)).isEmpty();
        assertThat(v2.contentHash()).isEqualTo(TORNADO_V2_DRAFT_HASH);
        assertThat(v2.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v2.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(TORNADO_V2_DRAFT_HASH);
        assertThat(v2.body().sources()).allSatisfy(src ->
                assertThat(src.url()).matches("https://www\\.(weather\\.gov|ready\\.gov|spc\\.noaa\\.gov)/.*"));

        var sc = v2.body().scenario();
        Map<String, Node> nodes = new HashMap<>();
        sc.nodes().forEach(n -> nodes.put(n.key(), n));
        assertThat(nodes.get("warning_issued").choices())
                .filteredOn(c -> !c.key().equals("interior_room"))
                .allSatisfy(c -> assertThat(c.next()).isEqualTo("storm_closer"));
        // The waiting step no longer says where everyone is: every way in arrives there.
        assertThat(nodes.get("in_shelter").body()).doesNotContain("bathroom");
        assertThat(nodes.get("in_shelter").scene()).doesNotContain("bathroom");
        assertThat(longestPath(sc.startNode(), nodes)).isLessThanOrEqualTo(4);
        assertThat(sc.outcomes()).singleElement().satisfies(o -> assertThat(o.body()).contains("talk it over together"));

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PracticeAvailabilityService(catalog, repo, true, false, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(TORNADO)).singleElement()
                .satisfies(e -> assertThat(e.version().version()).as("production starts v2; runs pinned to v1 still finish").isEqualTo(2));
    }

    // -- Evacuating with Pets v1 ------------------------------------------------

    static final String PETS = "evacuating-with-pets";
    static final String PETS_V1_DRAFT_HASH = "sha256:578235f3db181e8d97c71cf24313148b1b81dcd15eeb0dfc80351162134b545a";

    @Test
    void evacuatingWithPetsIsPublishedWithASecondChanceAndOpensThePetDrills() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v1 = catalog.exact(PETS, 1).orElseThrow();
        assertThat(PracticeContentValidator.validate(v1)).isEmpty();
        assertThat(v1.contentHash()).isEqualTo(PETS_V1_DRAFT_HASH);
        assertThat(v1.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v1.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(PETS_V1_DRAFT_HASH);
        assertThat(v1.body().sources()).allSatisfy(src ->
                assertThat(src.url()).matches("https://www\\.(ready\\.gov|redcross\\.org)/.*"));

        var sc = v1.body().scenario();
        Map<String, Node> nodes = new HashMap<>();
        sc.nodes().forEach(n -> nodes.put(n.key(), n));
        assertThat(nodes.get("order_issued").choices()).filteredOn(c -> !c.key().equals("bring_pets"))
                .allSatisfy(c -> assertThat(c.next()).isEqualTo("loading_car"));
        assertThat(longestPath(sc.startNode(), nodes)).isLessThanOrEqualTo(4);
        // Both practice lines open a real household drill (FE challenges.js PET_DRILLS).
        assertThat(v1.body().tags().values()).filteredOn(t -> t.action() != null)
                .extracting(t -> t.params().get("drillId"))
                .containsExactlyInAnyOrder("pet-buddy-and-place", "pet-carrier-round");
        assertThat(sc.outcomes()).singleElement().satisfies(o -> assertThat(o.body()).contains("talk it over together"));

        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PracticeAvailabilityService(catalog, repo, true, false, clock).startable(PracticeKind.ADULT_SCENARIO))
                .filteredOn(e -> e.version().key().equals(PETS)).singleElement()
                .satisfies(e -> assertThat(e.preview()).isFalse());
    }

    // -- EXEC-M4: Flood v4 and Pets v2 = published text verbatim + approved stills (DRAFT) ----

    static final String FLOOD_V4_DRAFT_HASH = "sha256:0ec42254f52403cd5d5f1e1a34cc4700e704b3bd5ffd60b46de527e7a0921fbc";
    static final String PETS_V2_DRAFT_HASH = "sha256:e6664765761cbf02f138fbc16badafa513b95855fcabf92c0340aaefd1365179";

    private static com.fasterxml.jackson.databind.node.ObjectNode contentFile(String key, int v) throws java.io.IOException {
        return (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                java.nio.file.Files.readString(java.nio.file.Path.of(
                        "src/main/resources/practice/content/" + key + "/v" + v + ".json")));
    }

    /** Strips the media additions (style, scene/explanation media, outcome media) from a content tree. */
    private static com.fasterxml.jackson.databind.JsonNode withoutMedia(com.fasterxml.jackson.databind.JsonNode content) {
        var c = (com.fasterxml.jackson.databind.node.ObjectNode) content.deepCopy();
        c.remove("mediaStyle");
        c.get("scenario").get("nodes").forEach(n -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove("media");
            ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove("explanationMedia");
        });
        c.get("scenario").get("outcomes").forEach(o -> ((com.fasterxml.jackson.databind.node.ObjectNode) o).remove("media"));
        return c;
    }

    private static void assertStill(PracticeContent.Media m, String slot, String masterSha) {
        assertThat(m.slotKey()).isEqualTo(slot);
        assertThat(m.kind()).isEqualTo(PracticeContent.MediaKind.STILL);
        assertThat(m.assetSha256()).isEqualTo("sha256:" + masterSha);
    }

    private static PracticeAvailabilityService availability(PracticeCatalog catalog, boolean preview) {
        PracticeContentControlRepo repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
        return new PracticeAvailabilityService(catalog, repo, true, preview, clock);
    }

    @Test
    void floodV4IsPublishedAtItsGate1HashAndAddsOnlyApprovedMediaToV3() throws Exception {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v3 = catalog.exact(FLOOD, 3).orElseThrow();
        Version v4 = catalog.exact(FLOOD, 4).orElseThrow();
        assertThat(PracticeContentValidator.validate(v4)).isEmpty();
        assertThat(v3.contentHash()).isEqualTo(FLOOD_V3_DRAFT_HASH);
        assertThat(v3.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v4.contentHash()).isEqualTo(FLOOD_V4_DRAFT_HASH);
        // Gate 1 APPROVED as content version C1, final under the single-gate ruling (2026-10-23).
        assertThat(v4.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v4.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(v4.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(FLOOD_V4_DRAFT_HASH);
        assertThat(v4.file().lifecycle().safetyReview().notes()).contains("MEDIA-024", "MEDIA-025", "MEDIA-013", "neutral post-practice");
        assertThat(v4.file().schema()).isEqualTo(2);
        assertThat(v4.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.REALISM);

        // Text verbatim: strip the media additions and the tree equals v3's content.
        assertThat(withoutMedia(contentFile(FLOOD, 4).get("content"))).isEqualTo(contentFile(FLOOD, 3).get("content"));

        var byKey = new HashMap<String, Node>();
        v4.body().scenario().nodes().forEach(n -> byKey.put(n.key(), n));
        assertStill(byKey.get("water_over_road").media(), "flash-flood-turn-around-route.water_over_road.scene",
                "a0de6c004ed9d05a5245f1298575933f5a8254d1665bcdde450416debe6531f9");
        assertStill(byKey.get("barricade").media(), "flash-flood-turn-around-route.barricade.scene",
                "75a2a4881ac7b585b51e80b48376b875f133dafb1b18eb7b14d3b7e6bb13af08");
        assertThat(byKey.get("choose_route").media()).isNull();
        assertThat(byKey.get("second_low_spot").media()).isNull();
        assertThat(byKey.values()).allSatisfy(n -> {
            assertThat(n.explanationMedia()).isNull();
            assertThat(n.choices()).allSatisfy(ch -> { assertThat(ch.thumb()).isNull(); assertThat(ch.media()).isNull(); });
        });
        assertThat(v4.body().scenario().introMedia()).isNull();
        assertThat(v4.body().scenario().outcomes()).singleElement().satisfies(o -> assertStill(o.media(),
                "flash-flood-turn-around-route.route_rehearsed.ending",
                "d90094c47c5b22b8d21a44a6cd5497511bd19ab70a706f541efab81812868963"));
    }

    @Test
    void productionStartsFloodV4AndPetsV1WhilePetsV2IsPreviewOnly() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        for (boolean preview : new boolean[] {false, true}) {
            assertThat(availability(catalog, preview).startable(PracticeKind.ADULT_SCENARIO))
                    .filteredOn(e -> e.version().key().equals(FLOOD)).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isFalse())
                    .satisfies(e -> assertThat(e.version().version()).as("flood, preview gate " + preview).isEqualTo(4));
        }
        for (Object[] c : new Object[][] {{PETS, 1, 2}}) {
            String key = (String) c[0];
            assertThat(availability(catalog, false).startable(PracticeKind.ADULT_SCENARIO))
                    .filteredOn(e -> e.version().key().equals(key)).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isFalse())
                    .satisfies(e -> assertThat(e.version().version()).as(key + " production").isEqualTo(c[1]));
            assertThat(availability(catalog, true).startable(PracticeKind.ADULT_SCENARIO))
                    .filteredOn(e -> e.version().key().equals(key)).singleElement()
                    .satisfies(e -> assertThat(e.preview()).isTrue())
                    .satisfies(e -> assertThat(e.version().version()).as(key + " preview").isEqualTo(c[2]));
        }
    }

    @Test
    void petsV2IsADraftThatAddsOnlyApprovedMediaToV1AndV1IsUntouched() throws Exception {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v1 = catalog.exact(PETS, 1).orElseThrow();
        Version v2 = catalog.exact(PETS, 2).orElseThrow();
        assertThat(PracticeContentValidator.validate(v2)).isEmpty();
        assertThat(v1.contentHash()).isEqualTo(PETS_V1_DRAFT_HASH);
        assertThat(v1.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v2.contentHash()).isEqualTo(PETS_V2_DRAFT_HASH);
        assertThat(v2.publishState()).isEqualTo(PublishState.DRAFT);
        assertThat(v2.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.PENDING);
        assertThat(v2.file().lifecycle().safetyReview().reviewedContentHash()).isNull();
        assertThat(v2.file().lifecycle().safetyReview().notes()).contains("MEDIA-027", "MEDIA-014", "MEDIA-026", "PLANNED EMPTY SLOTS");
        assertThat(v2.file().schema()).isEqualTo(2);
        assertThat(v2.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.REALISM);

        assertThat(withoutMedia(contentFile(PETS, 2).get("content"))).isEqualTo(contentFile(PETS, 1).get("content"));

        var byKey = new HashMap<String, Node>();
        v2.body().scenario().nodes().forEach(n -> byKey.put(n.key(), n));
        var cat = byKey.get("cat_hiding").explanationMedia();
        assertStill(cat, "evacuating-with-pets.cat_hiding.explanation",
                "483cbd78b48acfcacfe5ef77a6bd9785336578cf80db143cf8b81692f742d74d");
        assertThat(cat.alt()).isEqualTo("A calm gray tabby is found in its usual hiding spot under the bed, with its familiar carrier ready nearby.");
        assertThat(byKey.values()).allSatisfy(n -> {
            assertThat(n.media()).as(n.key() + " has no pre-choice scene").isNull();
            assertThat(n.choices()).allSatisfy(ch -> { assertThat(ch.thumb()).isNull(); assertThat(ch.media()).isNull(); });
        });
        assertThat(byKey.get("order_issued").explanationMedia()).isNull();
        assertThat(v2.body().scenario().introMedia()).isNull();
        assertThat(v2.body().scenario().outcomes()).singleElement().satisfies(o -> assertStill(o.media(),
                "evacuating-with-pets.pets_plan_rehearsed.ending",
                "0f67ca77d3e7ba4bfe72a88187364d193923f956214a62b91e894f6bc057b155"));
    }

    @Test
    void m4ManifestEntriesAreHashNamedAndMatchTheirMasters() {
        var manifest = PracticeMediaManifest.getDefault();
        for (String[] a : new String[][] {
                {"659b8f0ec14c3d9e25e256c15c8639be78494f36c3042897b529b4018c24a724", "ILLUSTRATION", "tornado-warning"},
                {"a0de6c004ed9d05a5245f1298575933f5a8254d1665bcdde450416debe6531f9", "REALISM", "flood-water-over-road"},
                {"75a2a4881ac7b585b51e80b48376b875f133dafb1b18eb7b14d3b7e6bb13af08", "REALISM", "flood-barricade"},
                {"d90094c47c5b22b8d21a44a6cd5497511bd19ab70a706f541efab81812868963", "REALISM", "flood-ending"},
                {"0f67ca77d3e7ba4bfe72a88187364d193923f956214a62b91e894f6bc057b155", "REALISM", "pets-ending"},
                {"483cbd78b48acfcacfe5ef77a6bd9785336578cf80db143cf8b81692f742d74d", "REALISM", "pets-cat-hiding"}}) {
            var asset = manifest.find("sha256:" + a[0]).orElseThrow();
            assertThat(asset.style().name()).isEqualTo(a[1]);
            assertThat(asset.stem()).isEqualTo("/images/practice/" + a[2] + "-" + a[0].substring(0, 8));
            assertThat(asset.widths()).containsExactly(640, 960, 1254);
            assertThat(asset.width()).isEqualTo(1254);
            assertThat(asset.height()).isEqualTo(705);
        }
        assertThat(manifest.problems()).isEmpty();
    }

    // -- EXEC-M5: Earthquake v5 = v4 text + four kitchen scene labels + approved stills (DRAFT) ----

    static final String EARTHQUAKE_V5_DRAFT_HASH = "sha256:c50b30cbe00434be7016990ed292453f48fbfefcfadc034f8b2573c657fddb2d";

    @Test
    void earthquakeV5IsPublishedAtItsGate1HashAndChangesOnlyFourSceneLabelsAndAddsApprovedMedia() throws Exception {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        Version v4 = catalog.exact(EARTHQUAKE, 4).orElseThrow();
        Version v5 = catalog.exact(EARTHQUAKE, 5).orElseThrow();
        assertThat(PracticeContentValidator.validate(v5)).isEmpty();
        assertThat(v4.contentHash()).isEqualTo(EARTHQUAKE_V4_DRAFT_HASH);
        assertThat(v4.publishState()).isEqualTo(PublishState.RETIRED);
        assertThat(v5.contentHash()).isEqualTo(EARTHQUAKE_V5_DRAFT_HASH);
        // Gate 1 APPROVED as content version C2, final under the single-gate ruling (2026-10-23).
        assertThat(v5.publishState()).isEqualTo(PublishState.PUBLISHED);
        assertThat(v5.file().lifecycle().safetyReview().status()).isEqualTo(SafetyReviewStatus.APPROVED);
        assertThat(v5.file().lifecycle().safetyReview().reviewedContentHash()).isEqualTo(EARTHQUAKE_V5_DRAFT_HASH);
        assertThat(v5.file().lifecycle().safetyReview().notes()).contains("MEDIA-022", "MEDIA-023", "kitchen", "6:15 PM", "design decision 2026-10-23");
        assertThat(v5.file().schema()).isEqualTo(2);
        assertThat(v5.body().mediaStyle()).isEqualTo(PracticeContent.MediaStyle.ILLUSTRATION);

        // Text verbatim except exactly the four scene labels: strip media and scene labels, trees are equal.
        var c4 = contentFile(EARTHQUAKE, 4).get("content");
        var c5 = withoutMedia(contentFile(EARTHQUAKE, 5).get("content"));
        var labels = new java.util.LinkedHashMap<String, String>();
        c5.get("scenario").get("nodes").forEach(n -> labels.put(n.get("key").asText(), n.get("scene").asText()));
        assertThat(labels).containsExactly(
                Map.entry("shaking_starts", "Tuesday, 6:15 PM \u00b7 Kitchen"),
                Map.entry("still_shaking", "6:15 PM \u00b7 Still shaking"),
                Map.entry("shaking_stops", "6:16 PM \u00b7 Inside, right after the shaking"),
                Map.entry("aftershock", "6:19 PM \u00b7 A few minutes later"));
        var s4 = (com.fasterxml.jackson.databind.node.ObjectNode) c4.deepCopy();
        s4.get("scenario").get("nodes").forEach(n -> ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove("scene"));
        c5.get("scenario").get("nodes").forEach(n -> ((com.fasterxml.jackson.databind.node.ObjectNode) n).remove("scene"));
        assertThat(c5).isEqualTo(s4);

        var byKey = new HashMap<String, Node>();
        v5.body().scenario().nodes().forEach(n -> byKey.put(n.key(), n));
        assertStill(byKey.get("shaking_starts").media(), "earthquake-first-five-minutes.shaking_starts.scene",
                "771f448ea8eb11d7fcb8636eaea2880a06578f9eb44933c3cc7003d18e3f7711");
        assertThat(byKey.get("shaking_starts").media().alt()).startsWith("An intact kitchen where a pendant lamp");
        var expl = byKey.get("shaking_starts").explanationMedia();
        assertStill(expl, "earthquake-first-five-minutes.shaking_starts.explanation",
                "3441f1fe22b004cb8bfe9812a4675aa39d9a28d9d475c78b524fc03ddc6c61a2");
        assertThat(expl.alt()).startsWith("An adult and child shelter under a sturdy kitchen table");
        byKey.forEach((k, n) -> {
            if (!k.equals("shaking_starts")) {
                assertThat(n.media()).as(k).isNull();
                assertThat(n.explanationMedia()).as(k).isNull();
            }
            assertThat(n.choices()).allSatisfy(ch -> { assertThat(ch.thumb()).isNull(); assertThat(ch.media()).isNull(); });
        });
        assertThat(v5.body().scenario().introMedia()).isNull();
        assertThat(v5.body().scenario().outcomes()).allSatisfy(o -> assertThat(o.media()).isNull());

        // Published 2026-10-23: production and preview both serve v5 (runs pinned to v2-v4 still finish).
        for (boolean preview : new boolean[] {false, true}) {
            assertThat(availability(catalog, preview).startable(PracticeKind.ADULT_SCENARIO))
                    .filteredOn(e -> e.version().key().equals(EARTHQUAKE)).singleElement()
                    .satisfies(e -> assertThat(e.version().version()).isEqualTo(5))
                    .satisfies(e -> assertThat(e.preview()).isFalse());
        }
    }

    @Test
    void m5ManifestEntriesAreHashNamedAndMatchTheirMasters() {
        var manifest = PracticeMediaManifest.getDefault();
        for (String[] a : new String[][] {
                {"771f448ea8eb11d7fcb8636eaea2880a06578f9eb44933c3cc7003d18e3f7711", "quake-shaking"},
                {"3441f1fe22b004cb8bfe9812a4675aa39d9a28d9d475c78b524fc03ddc6c61a2", "quake-drop-cover"}}) {
            var asset = manifest.find("sha256:" + a[0]).orElseThrow();
            assertThat(asset.style().name()).isEqualTo("ILLUSTRATION");
            assertThat(asset.stem()).isEqualTo("/images/practice/" + a[1] + "-" + a[0].substring(0, 8));
            assertThat(asset.widths()).containsExactly(640, 960, 1254);
            assertThat(asset.width()).isEqualTo(1254);
            assertThat(asset.height()).isEqualTo(705);
        }
        assertThat(manifest.problems()).isEmpty();
    }
}
