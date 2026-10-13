package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * P0-A — current official guidance governs the current action.
 *
 * <p>An activation stored its movement directive at creation and nothing ever
 * re-resolved it, so an activation fired under "shelter in place" kept saying so
 * after officials reversed to evacuation. Stress-test Scenario 24 reproduced it
 * with a chlorine release where the reversal IS the event.
 *
 * <p>These cover the transitions and, just as importantly, the two refusals:
 * what happens when we cannot check, and what happens when we check and nothing
 * is in force. A resolver that confidently answers in those cases would be worse
 * than the bug.
 */
class ActivationDirectiveTruthTest {

    private AlertFeedService feed;
    private AlertIngestService ingest;
    private ActivationDirectiveResolver resolver;

    @BeforeEach
    void setUp() {
        feed = mock(AlertFeedService.class);
        ingest = mock(AlertIngestService.class);
        resolver = new ActivationDirectiveResolver(feed, ingest);
    }

    /** An activation at a known point, activated under {@code storedDirective}. */
    private PlanActivation activation(String storedDirective) {
        PlanActivation a = new PlanActivation();
        a.setId("act-1");
        a.setOwnerEmail("owner@x.com");
        a.setLat(40.5);
        a.setLng(-111.9);
        a.setMovementDirective(storedDirective);
        a.setGoverningAlertSource("NWS");
        a.setGoverningAlertId("ORIGINAL-1");
        a.setGoverningAlertEvent("Hazardous Materials Warning");
        a.setGoverningAlertHeadline("Shelter in place — chlorine release");
        a.setGoverningAlertLifecycleState("active");
        a.setExpiresAt(Instant.now().plus(3, ChronoUnit.HOURS));
        return a;
    }

    /**
     * A real card — AlertCardDto is a record, so it cannot be mocked.
     *
     * <p>Matched by ZONE: the point's own zone is in the alert's targets, which
     * is how 82% of NWS alerts arrive and the one match that is containment by
     * construction. R4 lets only a containment-quality match govern, so a card
     * with no match reason would — correctly — govern nothing.
     */
    private AlertCardDto card(String id, String event, String directive, String lifecycle, Instant expires) {
        return matched(id, event, directive, lifecycle, expires, "zone");
    }

    /** As {@link #card}, matched to the point by {@code matchType}. */
    private AlertCardDto matched(String id, String event, String directive, String lifecycle,
                                 Instant expires, String matchType) {
        return new AlertCardDto(
                id,                       // id
                "NWS",                    // source
                event,                    // eventType
                event,                    // eventLabel
                null,                     // tier
                false,                    // isLifeThreatening
                false,                    // evacuationRelated
                event + " in effect",     // headline
                null,                     // whatToDo
                List.of(),                // precautions
                null,                     // precautionsSource
                lifecycle,                // lifecycleState
                List.of(),                // replacesAlertIds
                null,                     // supersededBy
                null,                     // official
                new AlertCardDto.Location("Area", matchType, null, null, null),
                0,                        // radiusMi
                null,                     // effectiveAt
                expires == null ? null : expires.toString(),
                null,                     // sourceAttribution
                null,                     // detail
                new AlertCardDto.Safety(null, null, null, List.of(), directive, null));
    }

    /** The same card, with a resolved replacement edge — CAP has already replaced it. */
    private AlertCardDto replacedCard(String id, String event, String directive, String successorId) {
        AlertCardDto c = card(id, event, directive, "active", soon());
        return new AlertCardDto(c.id(), c.source(), c.eventType(), c.eventLabel(), c.tier(),
                c.isLifeThreatening(), c.evacuationRelated(), c.headline(), c.whatToDo(),
                c.precautions(), c.precautionsSource(), c.lifecycleState(), c.replacesAlertIds(),
                new AlertCardDto.SupersededBy(successorId, "Replacement in effect"),
                c.official(), c.location(), c.radiusMi(), c.effectiveAt(), c.expiresAt(),
                c.sourceAttribution(), c.detail(), c.safety());
    }

    /** A plain activation: no directive, no alert behind it — "activate my plan". */
    private PlanActivation plainActivation() {
        PlanActivation a = activation("none");
        a.setGoverningAlertSource(null);
        a.setGoverningAlertId(null);
        a.setGoverningAlertEvent(null);
        a.setGoverningAlertHeadline(null);
        a.setGoverningAlertLifecycleState(null);
        return a;
    }

    private void feedReturns(AlertCardDto... cards) {
        when(feed.feedFor(anyDouble(), anyDouble()))
                .thenReturn(new AlertFeedResponse(List.of(cards), null));
    }

    private static Instant soon() { return Instant.now().plus(2, ChronoUnit.HOURS); }

    // ── TRANSITIONS ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("shelter → evacuate: the reversal governs, and is flagged as changed")
    void shelterToEvacuate() {
        feedReturns(card("REVERSAL-1", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.changed()).isTrue();
        // The stored directive is preserved as history, not overwritten.
        assertThat(r.asActivated()).isEqualTo("shelter_in_place");
        assertThat(r.alertId()).isEqualTo("REVERSAL-1");
    }

    @Test
    @DisplayName("evacuate → shelter: the reverse transition governs too")
    void evacuateToShelter() {
        feedReturns(card("REVERSAL-2", "Shelter In Place Warning", "shelter_in_place", "active", soon()));

        var r = resolver.resolve(activation("evacuate"));

        assertThat(r.directive()).isEqualTo("shelter_in_place");
        assertThat(r.changed()).isTrue();
        assertThat(r.asActivated()).isEqualTo("evacuate");
    }

    @Test
    @DisplayName("avoid → evacuate: obsolete avoid guidance is not retained")
    void avoidIsNotRetainedIndefinitely() {
        feedReturns(card("UPGRADE-1", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(activation("avoid_area"));

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.changed()).isTrue();
    }

    @Test
    @DisplayName("updated alert, same directive: no churn — changed stays false")
    void updatedSameDirectiveDoesNotChurn() {
        feedReturns(card("UPDATED-1", "Hazardous Materials Warning", "shelter_in_place", "updated", soon()));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.directive()).isEqualTo("shelter_in_place");
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.changed()).as("same directive must not report a change").isFalse();
    }

    @Test
    @DisplayName("a directive-carrying alert wins over a louder one that carries none")
    void directiveWinsOverSeverity() {
        // Severity does not rank instructions: a directive is an instruction not
        // to proceed, and only alerts carrying one are candidates to govern.
        feedReturns(
                card("NO-DIRECTIVE", "Extreme Heat Warning", "none", "active", soon()),
                card("HAS-DIRECTIVE", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.alertId()).isEqualTo("HAS-DIRECTIVE");
    }

    // ── THE REFUSALS ────────────────────────────────────────────────────────

    @Test
    @DisplayName("original expired, no successor: stop asserting it, do not name a new action")
    void expiredWithNoSuccessorUnderclaims() {
        feedReturns(card("OLD-1", "Hazardous Materials Warning", "shelter_in_place", "expired", null));

        var r = resolver.resolve(activation("shelter_in_place"));

        // Not "shelter in place" — that is no longer in force and saying so
        // would be the original bug pointed the other way.
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.SUPERSEDED_UNRESOLVED);
        assertThat(r.directive()).isEqualTo("follow_official_instruction");
        // And NOT "none": that would let the saved destination stand as the
        // current instruction, which is the RC-1 P0.
        assertThat(r.directive()).isNotEqualTo("none");
        assertThat(r.asActivated()).isEqualTo("shelter_in_place");
    }

    @Test
    @DisplayName("an alert past its own expiry time is not in force even if lifecycle says active")
    void pastExpiryIsNotLive() {
        feedReturns(card("STALE-1", "Evacuation Immediate", "evacuate", "active",
                Instant.now().minus(10, ChronoUnit.MINUTES)));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.SUPERSEDED_UNRESOLVED);
    }

    @Test
    @DisplayName("superseded alerts are not in force")
    void supersededIsNotLive() {
        feedReturns(card("SUP-1", "Hazardous Materials Warning", "shelter_in_place", "superseded", soon()));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.SUPERSEDED_UNRESOLVED);
    }

    @Test
    @DisplayName("no coordinates: carry the stored directive, labelled UNVERIFIED")
    void noCoordinatesIsUnverifiedNotConfident() {
        PlanActivation a = activation("shelter_in_place");
        a.setLat(null);
        a.setLng(null);

        var r = resolver.resolve(a);

        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.UNVERIFIED);
        assertThat(r.directive()).isEqualTo("shelter_in_place");
        assertThat(r.changed()).isFalse();
        verifyNoInteractions(feed);
    }

    @Test
    @DisplayName("a feed failure is not evidence that guidance changed")
    void feedFailureIsUnverified() {
        when(feed.feedFor(anyDouble(), anyDouble())).thenThrow(new IllegalStateException("snapshot unavailable"));

        var r = resolver.resolve(activation("evacuate"));

        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.UNVERIFIED);
        assertThat(r.directive()).isEqualTo("evacuate");
    }

    @Test
    @DisplayName("a null activation does not throw")
    void nullActivationIsSafe() {
        var r = resolver.resolve(null);
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.UNVERIFIED);
        assertThat(r.directive()).isEqualTo("none");
    }

    @Test
    @DisplayName("falls back to a supplied point when the activation has none")
    void fallbackPointIsUsed() {
        // `location` is optional on the create request, so most real
        // activations carry no point. Without a fallback the re-resolution
        // would answer UNVERIFIED forever and be dead code in production.
        PlanActivation a = activation("shelter_in_place");
        a.setLat(null);
        a.setLng(null);
        feedReturns(card("REVERSAL-1", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(a, 40.5, -111.9);

        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.directive()).isEqualTo("evacuate");
    }

    @Test
    @DisplayName("the activation's own point wins over the fallback")
    void ownPointWins() {
        feedReturns(card("REVERSAL-1", "Evacuation Immediate", "evacuate", "active", soon()));

        resolver.resolve(activation("shelter_in_place"), 1.0, 2.0);

        verify(feed).feedFor(40.5, -111.9);
    }

    // ── D6: A PLAIN ACTIVATION IS NOT AN OFFICIAL ORDER ─────────────────────
    //
    // The lapse rule exists to retire a directive that USED to be in force. An
    // activation that never carried one has nothing to retire, and narrowing it
    // to "follow official instructions" asserted official guidance that does
    // not exist — and suppressed the household's own plan navigation, for the
    // owner and for every link holder.

    @Test
    @DisplayName("stored none, no governing alert, nothing live: stays none and unchanged")
    void plainActivationStaysNone() {
        feedReturns();

        var r = resolver.resolve(plainActivation());

        assertThat(r.directive()).isEqualTo("none");
        assertThat(r.changed()).isFalse();
        // We looked, and "no directive" is still the true answer.
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.alertId()).isNull();
        assertThat(r.source()).isNull();
    }

    @Test
    @DisplayName("stored none, a live alert that carries no directive: still none")
    void plainActivationUnderNonDirectiveAlertStaysNone() {
        feedReturns(card("HEAT-1", "Extreme Heat Warning", "none", "active", soon()));

        var r = resolver.resolve(plainActivation());

        assertThat(r.directive()).isEqualTo("none");
        assertThat(r.changed()).isFalse();
    }

    @Test
    @DisplayName("stored none under an alert that has lapsed: none, and the lapse is reported")
    void storedNoneUnderLapsedAlertStaysNone() {
        // Activated under a Watch with no directive; the Watch has since ended.
        feedReturns();

        var r = resolver.resolve(activation("none"));

        assertThat(r.directive()).isEqualTo("none");
        assertThat(r.changed()).isFalse();
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.SUPERSEDED_UNRESOLVED);
    }

    @Test
    @DisplayName("stored none under an alert still in force: CURRENT, attributed to that alert")
    void storedNoneUnderLiveAlertIsCurrent() {
        feedReturns(card("ORIGINAL-1", "Hazardous Materials Watch", "none", "active", soon()));

        var r = resolver.resolve(activation("none"));

        assertThat(r.directive()).isEqualTo("none");
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.alertId()).isEqualTo("ORIGINAL-1");
    }

    @Test
    @DisplayName("a live directive still governs a plain activation")
    void liveDirectiveGovernsPlainActivation() {
        feedReturns(card("EVAC-1", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(plainActivation());

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.changed()).isTrue();
    }

    // ── R5: SEVERAL LIVE DIRECTIVES ─────────────────────────────────────────

    @Test
    @DisplayName("several live directives: evacuate outranks the rest, whatever the snapshot order")
    void precedenceEvacuateFirst() {
        feedReturns(
                card("FOLLOW-1", "Civil Emergency Message", "follow_official_instruction", "active", soon()),
                card("AVOID-1", "Hazardous Materials Warning", "avoid_area", "active", soon()),
                card("SHELTER-1", "Shelter In Place Warning", "shelter_in_place", "active", soon()),
                card("EVAC-1", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(activation("none"));

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.alertId()).isEqualTo("EVAC-1");
    }

    @Test
    @DisplayName("precedence: shelter_in_place > avoid_area > follow_official_instruction")
    void precedenceBelowEvacuate() {
        feedReturns(
                card("FOLLOW-1", "Civil Emergency Message", "follow_official_instruction", "active", soon()),
                card("AVOID-1", "Hazardous Materials Warning", "avoid_area", "active", soon()),
                card("SHELTER-1", "Shelter In Place Warning", "shelter_in_place", "active", soon()));
        assertThat(resolver.resolve(activation("none")).alertId()).isEqualTo("SHELTER-1");

        feedReturns(
                card("FOLLOW-1", "Civil Emergency Message", "follow_official_instruction", "active", soon()),
                card("AVOID-1", "Hazardous Materials Warning", "avoid_area", "active", soon()));
        assertThat(resolver.resolve(activation("none")).alertId()).isEqualTo("AVOID-1");
    }

    @Test
    @DisplayName("the same live alerts in either order resolve to the same answer")
    void selectionIsOrderIndependent() {
        AlertCardDto shelter = card("SHELTER-1", "Shelter In Place Warning", "shelter_in_place", "active", soon());
        AlertCardDto evac = card("EVAC-1", "Evacuation Immediate", "evacuate", "active", soon());

        feedReturns(shelter, evac);
        var first = resolver.resolve(activation("none"));
        feedReturns(evac, shelter);
        var second = resolver.resolve(activation("none"));

        assertThat(first.alertId()).isEqualTo(second.alertId()).isEqualTo("EVAC-1");
    }

    @Test
    @DisplayName("live directives that disagree are flagged conflicting")
    void disagreeingDirectivesAreConflicting() {
        feedReturns(
                card("SHELTER-1", "Shelter In Place Warning", "shelter_in_place", "active", soon()),
                card("EVAC-1", "Evacuation Immediate", "evacuate", "active", soon()));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.conflicting()).isTrue();
    }

    @Test
    @DisplayName("live directives that agree are not conflicting; nor is a lone directive")
    void agreeingDirectivesAreNotConflicting() {
        feedReturns(
                card("EVAC-1", "Evacuation Immediate", "evacuate", "active", soon()),
                card("EVAC-2", "Evacuation Order", "evacuate", "updated", soon()),
                card("HEAT-1", "Extreme Heat Warning", "none", "active", soon()));
        assertThat(resolver.resolve(activation("evacuate")).conflicting()).isFalse();

        feedReturns(card("EVAC-1", "Evacuation Immediate", "evacuate", "active", soon()));
        assertThat(resolver.resolve(activation("evacuate")).conflicting()).isFalse();
    }

    // ── D3: A REPLACED ALERT NEVER GOVERNS ──────────────────────────────────

    @Test
    @DisplayName("a card with a resolved supersededBy edge never governs, even while lifecycle says active")
    void replacedCardNeverGoverns() {
        feedReturns(replacedCard("OLD-EVAC", "Evacuation Immediate", "evacuate", "NEW-SHELTER"));

        var r = resolver.resolve(activation("evacuate"));

        assertThat(r.directive()).isNotEqualTo("evacuate");
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.SUPERSEDED_UNRESOLVED);
        assertThat(r.alertId()).isNull();
    }

    @Test
    @DisplayName("a replaced card loses to its live successor, and does not count as a conflict")
    void replacedCardLosesToSuccessor() {
        feedReturns(
                replacedCard("OLD-EVAC", "Evacuation Immediate", "evacuate", "NEW-SHELTER"),
                card("NEW-SHELTER", "Shelter In Place Warning", "shelter_in_place", "updated", soon()));

        var r = resolver.resolve(activation("evacuate"));

        assertThat(r.directive()).isEqualTo("shelter_in_place");
        assertThat(r.alertId()).isEqualTo("NEW-SHELTER");
        assertThat(r.conflicting()).isFalse();
    }

    @Test
    @DisplayName("a non-current lifecycle never governs: cancelled, ended, expired, superseded")
    void nonCurrentLifecycleNeverGoverns() {
        for (String lifecycle : List.of("cancelled", "ended", "expired", "superseded", "", "unknown")) {
            feedReturns(card("X-1", "Evacuation Immediate", "evacuate", lifecycle, soon()));
            var r = resolver.resolve(activation("shelter_in_place"));
            assertThat(r.alertId()).as("lifecycle '%s' must not govern", lifecycle).isNull();
            assertThat(r.directive()).as("lifecycle '%s'", lifecycle).isEqualTo("follow_official_instruction");
        }
    }

    // ── R4: ONLY A CONTAINMENT-QUALITY MATCH GOVERNS ────────────────────────
    //
    // matchTypeFor labels a match POLYGON when the polygon's FIRST VERTEX is
    // within the 50-mile feed radius — proximity, not containment. Before R4 an
    // evacuation order drawn 40 miles away could become this household's
    // governing directive. Now a directive governs only from a polygon that
    // actually contains the point, or from an exact zone match.

    /** A closed GeoJSON polygon ring from [lng, lat] corners, in the ingest's Map shape. */
    private static Map<String, Object> polygon(double west, double south, double east, double north) {
        return Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(west, south), List.of(east, south), List.of(east, north),
                List.of(west, north), List.of(west, south))));
    }

    /** The activation point (40.5, -111.9) sits inside this one. */
    private static Map<String, Object> containingPolygon() {
        return polygon(-112.0, 40.4, -111.8, 40.6);
    }

    /**
     * About 40 miles north of the activation point. Its first vertex
     * (-111.95, 41.08) is inside the 50-mile radius, so matchTypeFor calls it
     * POLYGON; the area itself does not come near the household.
     */
    private static Map<String, Object> polygonFortyMilesAway() {
        return polygon(-111.95, 41.08, -111.85, 41.2);
    }

    /** The ingest snapshot the resolver reads geometry from. */
    private void snapshotHolds(NormalizedAlert... alerts) {
        when(ingest.getSnapshot()).thenReturn(new Snapshot(List.of(alerts), Instant.now(), Instant.now()));
    }

    private static NormalizedAlert alertWithGeometry(String id, String event, Object geometry) {
        return TestAlerts.nws(event).id(id).geometry(geometry).build();
    }

    @Test
    @DisplayName("R4 premise: matchTypeFor really does call the 40-mile polygon a POLYGON match")
    void farPolygonIsLabelledPolygonUpstream() {
        // Pins the defect this guards against, so the next test is not
        // testing a situation the feed cannot produce.
        NormalizedAlert far = alertWithGeometry("FAR-EVAC", "Evacuation Immediate", polygonFortyMilesAway());
        MatchType m = AlertIngestService.matchTypeFor(far, 40.5, -111.9, 50 * 1.609344, Set.of(), Set.of());
        assertThat(m).isEqualTo(MatchType.POLYGON);
    }

    @Test
    @DisplayName("R4: an evacuation polygon 40 mi away (first vertex near) does not govern")
    void farPolygonDoesNotGovern() {
        snapshotHolds(alertWithGeometry("FAR-EVAC", "Evacuation Immediate", polygonFortyMilesAway()));
        feedReturns(matched("FAR-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), "polygon"));

        var plain = resolver.resolve(plainActivation());
        assertThat(plain.directive()).as("a plain activation is not ordered out by a distant polygon")
                .isEqualTo("none");
        assertThat(plain.alertId()).isNull();
        assertThat(plain.changed()).isFalse();

        // And an activation under a directive does not adopt the far order:
        // it lapses conservatively, exactly as if nothing live directed.
        var directed = resolver.resolve(activation("shelter_in_place"));
        assertThat(directed.directive()).isEqualTo("follow_official_instruction");
        assertThat(directed.status()).isEqualTo(ActivationDirectiveResolver.Status.SUPERSEDED_UNRESOLVED);
        assertThat(directed.alertId()).isNull();
    }

    @Test
    @DisplayName("R4: a polygon that contains the point governs")
    void containingPolygonGoverns() {
        snapshotHolds(alertWithGeometry("NEAR-EVAC", "Evacuation Immediate", containingPolygon()));
        feedReturns(matched("NEAR-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), "polygon"));

        var r = resolver.resolve(activation("shelter_in_place"));

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.status()).isEqualTo(ActivationDirectiveResolver.Status.CURRENT);
        assertThat(r.alertId()).isEqualTo("NEAR-EVAC");
    }

    @Test
    @DisplayName("R4: containment is tested at the fallback point when the activation has none")
    void containmentUsesFallbackPoint() {
        PlanActivation a = activation("none");
        a.setLat(null);
        a.setLng(null);
        snapshotHolds(alertWithGeometry("NEAR-EVAC", "Evacuation Immediate", containingPolygon()));
        feedReturns(matched("NEAR-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), "polygon"));

        assertThat(resolver.resolve(a, 40.5, -111.9).directive()).isEqualTo("evacuate");
        assertThat(resolver.resolve(a, 41.5, -111.9).directive())
                .as("the same polygon, a point outside it").isNotEqualTo("evacuate");
    }

    @Test
    @DisplayName("R4: an exact zone match governs")
    void zoneMatchGoverns() {
        feedReturns(matched("ZONE-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), "zone"));

        var r = resolver.resolve(plainActivation());

        assertThat(r.directive()).isEqualTo("evacuate");
        assertThat(r.alertId()).isEqualTo("ZONE-EVAC");
    }

    @Test
    @DisplayName("R4: a statewide (state_prefix) or broadcast match never governs")
    void statewideAndBroadcastDoNotGovern() {
        for (String coarse : List.of("state_prefix", "broadcast")) {
            feedReturns(matched("WIDE-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), coarse));

            var plain = resolver.resolve(plainActivation());
            assertThat(plain.directive()).as("%s on a plain activation", coarse).isEqualTo("none");
            assertThat(plain.alertId()).as(coarse).isNull();

            var directed = resolver.resolve(activation("shelter_in_place"));
            assertThat(directed.alertId()).as("%s on a directed activation", coarse).isNull();
            assertThat(directed.directive()).as(coarse).isEqualTo("follow_official_instruction");
        }
    }

    @Test
    @DisplayName("R4: a card with no match reason at all does not govern")
    void missingMatchTypeDoesNotGovern() {
        feedReturns(matched("NO-MATCH", "Evacuation Immediate", "evacuate", "active", soon(), null));

        assertThat(resolver.resolve(plainActivation()).directive()).isEqualTo("none");
    }

    @Test
    @DisplayName("R4: UNKNOWN geometry does not govern — malformed, missing, or absent from the snapshot")
    void unknownGeometryDoesNotGovern() {
        // An unclosed ring: GeoContainment reads it UNKNOWN, never INSIDE.
        Map<String, Object> unclosed = Map.of("type", "Polygon", "coordinates", List.of(List.of(
                List.of(-112.0, 40.4), List.of(-111.8, 40.4), List.of(-111.8, 40.6), List.of(-112.0, 40.6))));
        AlertCardDto polygonCard = matched("BAD-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), "polygon");

        snapshotHolds(alertWithGeometry("BAD-EVAC", "Evacuation Immediate", unclosed));
        feedReturns(polygonCard);
        assertThat(resolver.resolve(plainActivation()).directive()).as("malformed ring").isEqualTo("none");

        snapshotHolds(alertWithGeometry("BAD-EVAC", "Evacuation Immediate", null));
        assertThat(resolver.resolve(plainActivation()).directive()).as("null geometry").isEqualTo("none");

        // The snapshot rolled between the feed read and this one.
        snapshotHolds();
        assertThat(resolver.resolve(plainActivation()).directive()).as("not in snapshot").isEqualTo("none");

        when(ingest.getSnapshot()).thenThrow(new IllegalStateException("snapshot unavailable"));
        assertThat(resolver.resolve(plainActivation()).directive()).as("snapshot read fails").isEqualTo("none");
    }

    @Test
    @DisplayName("R4: a governing zone shelter is not outranked, or contradicted, by a far evacuation polygon")
    void ineligibleDirectiveNeitherOutranksNorConflicts() {
        snapshotHolds(alertWithGeometry("FAR-EVAC", "Evacuation Immediate", polygonFortyMilesAway()));
        feedReturns(
                matched("FAR-EVAC", "Evacuation Immediate", "evacuate", "active", soon(), "polygon"),
                card("ZONE-SHELTER", "Shelter In Place Warning", "shelter_in_place", "active", soon()));

        var r = resolver.resolve(activation("none"));

        assertThat(r.directive()).isEqualTo("shelter_in_place");
        assertThat(r.alertId()).isEqualTo("ZONE-SHELTER");
        assertThat(r.conflicting()).isFalse();
    }

    // ── PROVENANCE IS PRESERVED ─────────────────────────────────────────────

    @Test
    @DisplayName("resolving never mutates the stored activation")
    void storedProvenanceIsNotOverwritten() {
        PlanActivation a = activation("shelter_in_place");
        feedReturns(card("REVERSAL-1", "Evacuation Immediate", "evacuate", "active", soon()));

        resolver.resolve(a);

        // History is history. The row still says what the household activated
        // under and which alert it was fired against.
        assertThat(a.getMovementDirective()).isEqualTo("shelter_in_place");
        assertThat(a.getGoverningAlertId()).isEqualTo("ORIGINAL-1");
        assertThat(a.getGoverningAlertEvent()).isEqualTo("Hazardous Materials Warning");
    }
}
