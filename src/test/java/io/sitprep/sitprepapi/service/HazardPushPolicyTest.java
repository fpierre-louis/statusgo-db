package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Hazard-push policy guards (audit P1-4).
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code PushPolicyService} defines {@code NWS_SEVERE_EXTREME},
 * {@code USGS_QUAKE_MAJOR} and {@code WILDFIRE_NEAR};
 * {@code AlertPreferencesPage} renders a toggle for each. <b>No code path ever
 * passed those categories to {@code evaluate()}</b> — the hazard push went
 * straight to {@code sendHazardAlertBatch}. So all three toggles were
 * decorative: a user who unchecked "NWS weather alerts" still received them,
 * which is worse than not offering the switch.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HazardPushPolicyTest {

    @Mock UserAlertPreferenceRepo repo;
    @Mock RateLimiterService rateLimiter;

    private PushPolicyService policy;
    private static AlertDispatchService dispatcher;

    @BeforeAll
    static void loadTemplates() {
        dispatcher = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatcher.loadTemplates();
    }

    @BeforeEach
    void setUp() {
        policy = new PushPolicyService(repo, rateLimiter);
        when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
    }

    private void prefs(UserAlertPreference p) {
        p.setUserEmail("u@x.com");
        when(repo.findByEmail("u@x.com")).thenReturn(Optional.of(p));
    }

    private static UserAlertPreference defaults() {
        return new UserAlertPreference();
    }

    // ==================================================================
    // The toggles do something now
    // ==================================================================

    @Test
    void mutingNwsAlertsActuallyStopsNwsPushes() {
        UserAlertPreference p = defaults();
        p.setNwsAlerts(false);
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe"))
                .isEqualTo(Lane.DROP);
    }

    @Test
    void mutingEarthquakesDoesNotMuteWeather() {
        UserAlertPreference p = defaults();
        p.setEarthquakes(false);
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "6.1")).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe")).isEqualTo(Lane.A);
    }

    @Test
    void mutingWildfiresDoesNotMuteFloods() {
        UserAlertPreference p = defaults();
        p.setWildfires(false);
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.WILDFIRE_NEAR, "Severe")).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe")).isEqualTo(Lane.A);
    }

    @Test
    void theDefaultIsStillOptedIn() {
        prefs(defaults());
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "6.1")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.WILDFIRE_NEAR, "Severe")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.WEEKLY_DRILL_REMINDER, null)).isEqualTo(Lane.B);
    }

    @Test
    void mutingDrillsStopsWeeklyDrillNudgesOnly() {
        UserAlertPreference p = defaults();
        p.setDrills(false);
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.WEEKLY_DRILL_REMINDER, null))
                .isEqualTo(Lane.DROP);
        assertThat(policy.evaluate("u@x.com", Category.HOUSEHOLD_RITUAL_REMINDER, null))
                .isEqualTo(Lane.B);
    }

    // ==================================================================
    // Quiet hours still do not suppress a life-safety warning
    // ==================================================================

    @Test
    void aSevereWarningBypassesQuietHours() {
        // Widened from Extreme-only with P1-4. NWS rates a Flash Flood Warning
        // "Severe", and most flash-flood deaths happen at night — the one
        // category the narrow rule deferred to 7am was among the most
        // time-critical things we send.
        UserAlertPreference p = defaults();
        p.setQuietHoursEnabled(true);
        p.setQuietStart(LocalTime.of(0, 0));
        p.setQuietEnd(LocalTime.of(23, 59));   // always quiet
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe"))
                .as("a Flash Flood Warning at 2am must still interrupt")
                .isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Extreme"))
                .isEqualTo(Lane.A);
    }

    @Test
    void anExplicitOptOutStillBeatsTheCriticalBypass() {
        // Muting is a stronger, more deliberate signal than a quiet window.
        UserAlertPreference p = defaults();
        p.setNwsAlerts(false);
        p.setQuietHoursEnabled(false);
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Extreme"))
                .isEqualTo(Lane.DROP);
    }

    @Test
    void quietHoursStillDeferANonCriticalCategory() {
        UserAlertPreference p = defaults();
        p.setQuietHoursEnabled(true);
        p.setQuietStart(LocalTime.of(0, 0));
        p.setQuietEnd(LocalTime.of(23, 59));
        prefs(p);

        // A minor quake is not on the bypass list.
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "5.6"))
                .isEqualTo(Lane.B);
    }

    // ==================================================================
    // Category mapping
    // ==================================================================

    @Test
    void hazardTypeChoosesTheToggleTheUserActuallySees() {
        DispatchTemplate redFlag = dispatcher
                .matchForAlert(TestAlerts.nws("Red Flag Warning").build()).orElseThrow();
        DispatchTemplate flood = dispatcher
                .matchForAlert(TestAlerts.nws("Flood Warning").build()).orElseThrow();

        assertThat(AlertDispatchService.pushCategoryFor(
                TestAlerts.nws("Red Flag Warning").build(), redFlag))
                .as("someone who mutes the Wildfires toggle means the Red Flag Warning")
                .isEqualTo(Category.WILDFIRE_NEAR);

        assertThat(AlertDispatchService.pushCategoryFor(
                TestAlerts.nws("Flood Warning").build(), flood))
                .isEqualTo(Category.NWS_SEVERE_EXTREME);

        NormalizedAlert quake = TestAlerts.usgs("M6.2 — somewhere").build();
        assertThat(AlertDispatchService.pushCategoryFor(quake, null))
                .isEqualTo(Category.USGS_QUAKE_MAJOR);
    }
}
