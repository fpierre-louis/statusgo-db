package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P0-B — a reminder must not make a hidden person's phone audible.
 *
 * <p>Every push SitPrep sends was built with {@code sound("default")} at
 * Android {@code HIGH} / APNs {@code 10}: one volume, always audible. Correct
 * almost everywhere, and wrong for the case where being audible IS the danger —
 * stress-test Scenario 28, a 12-year-old in a locked silent classroom while her
 * family taps Nudge.
 *
 * <p>The classification comes from the template contract
 * ({@code sitprep.concealmentSensitive}), never from matching words in an event
 * name. These tests lock both halves: the hazard is recognised, and an ordinary
 * hazard is left alone.
 */
class ConcealmentSafetyTest {

    private AlertFeedService feed;
    private AlertDispatchService dispatch;
    private ConcealmentSafetyService service;

    @BeforeEach
    void setUp() {
        feed = mock(AlertFeedService.class);
        dispatch = mock(AlertDispatchService.class);
        service = new ConcealmentSafetyService(feed, dispatch);
    }

    private AlertCardDto card(String event, String lifecycle, Instant expires) {
        return new AlertCardDto(
                "alert-" + event, "NWS", event, event, null, false, false,
                event + " in effect", null, List.of(), null, lifecycle, List.of(),
                null, null, null, 0, null,
                expires == null ? null : expires.toString(), null, null,
                new AlertCardDto.Safety(null, null, null, List.of(), "follow_official_instruction", null));
    }

    private void feedReturns(AlertCardDto... cards) {
        when(feed.feedFor(anyDouble(), anyDouble()))
                .thenReturn(new AlertFeedResponse(List.of(cards), null));
    }

    /**
     * A REAL reviewed template for {@code event}. DispatchTemplate and its
     * sitprep metadata are final classes, so these are built through the same
     * JSON factory production uses — which also means the test exercises the
     * actual `concealmentSensitive` parsing rather than a stubbed getter.
     */
    private void templateFor(String event, boolean concealmentSensitive) {
        String json = "{"
                + "\"source\":\"NWS\","
                + "\"eventAny\":[\"" + event + "\"],"
                + "\"tier\":\"warning\","
                + "\"hazardType\":\"other\","
                + "\"headline\":\"h\","
                + "\"body\":\"b\","
                + "\"sitprep\":{\"dispatchMode\":\"critical_push\",\"concealmentSensitive\":"
                + concealmentSensitive + "}"
                + "}";
        try {
            JsonNode node = new ObjectMapper().readTree(json);
            AlertDispatchService.DispatchTemplate t = AlertDispatchService.DispatchTemplate.fromJson(node);
            when(dispatch.templateForEvent(event)).thenReturn(Optional.of(t));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant soon() { return Instant.now().plus(2, ChronoUnit.HOURS); }

    @Test
    @DisplayName("a live concealment-sensitive hazard silences the device")
    void lockdownIsConcealmentSensitive() {
        feedReturns(card("Law Enforcement Warning", "active", soon()));
        templateFor("Law Enforcement Warning", true);

        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isTrue();
    }

    @Test
    @DisplayName("an ordinary hazard does not — reminders stay usable")
    void ordinaryHazardIsNotSilenced() {
        // The regression that matters in the other direction: silencing a
        // reminder somebody needed is its own harm.
        feedReturns(card("Tornado Warning", "active", soon()));
        templateFor("Tornado Warning", false);

        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isFalse();
    }

    @Test
    @DisplayName("an expired lockdown does not silence anything")
    void expiredLockdownIsNotLive() {
        feedReturns(card("Law Enforcement Warning", "expired", null));
        templateFor("Law Enforcement Warning", true);

        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isFalse();
    }

    @Test
    @DisplayName("past its own expiry time is not live either")
    void pastExpiryIsNotLive() {
        feedReturns(card("Law Enforcement Warning", "active",
                Instant.now().minus(5, ChronoUnit.MINUTES)));
        templateFor("Law Enforcement Warning", true);

        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isFalse();
    }

    @Test
    @DisplayName("no location means no claim — fail toward noise, not toward silence")
    void noLocationFailsOpen() {
        assertThat(service.isConcealmentSensitiveAt(null, null)).isFalse();
        verifyNoInteractions(feed);
    }

    @Test
    @DisplayName("a feed failure fails toward noise")
    void feedFailureFailsOpen() {
        when(feed.feedFor(anyDouble(), anyDouble())).thenThrow(new IllegalStateException("no snapshot"));
        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isFalse();
    }

    @Test
    @DisplayName("an unclassified event is not assumed sensitive")
    void unknownTemplateIsNotSensitive() {
        feedReturns(card("Some Unreviewed Event", "active", soon()));
        when(dispatch.templateForEvent(anyString())).thenReturn(Optional.empty());

        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isFalse();
    }

    @Test
    @DisplayName("the classification comes from the template, not from words in the name")
    void classificationIsNotStringMatching() {
        // An event whose NAME suggests nothing, classified sensitive by review,
        // must silence; and the reverse must not. This is the architecture the
        // brief requires: no `if (event.contains("shooting"))`.
        feedReturns(card("Civil Danger Warning", "active", soon()));
        templateFor("Civil Danger Warning", true);
        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9)).isTrue();

        reset(feed, dispatch);
        feedReturns(card("Active Assailant Advisory", "active", soon()));
        templateFor("Active Assailant Advisory", false);
        assertThat(service.isConcealmentSensitiveAt(40.5, -111.9))
                .as("an alarming name with no reviewed flag is still not sensitive")
                .isFalse();
    }
}
