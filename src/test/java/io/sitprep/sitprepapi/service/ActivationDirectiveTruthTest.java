package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
    private ActivationDirectiveResolver resolver;

    @BeforeEach
    void setUp() {
        feed = mock(AlertFeedService.class);
        resolver = new ActivationDirectiveResolver(feed);
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

    /** A real card — AlertCardDto is a record, so it cannot be mocked. */
    private AlertCardDto card(String id, String event, String directive, String lifecycle, Instant expires) {
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
                null,                     // location
                0,                        // radiusMi
                null,                     // effectiveAt
                expires == null ? null : expires.toString(),
                null,                     // sourceAttribution
                null,                     // detail
                new AlertCardDto.Safety(null, null, null, List.of(), directive, null));
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
        // to proceed, and the first alert carrying one governs.
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
