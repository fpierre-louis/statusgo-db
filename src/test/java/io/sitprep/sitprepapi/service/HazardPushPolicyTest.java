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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.time.ZoneOffset;
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
        prefs(quietNow());

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
        prefs(quietNow());

        // A minor quake is not on the bypass list.
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "5.6"))
                .isEqualTo(Lane.B);
    }

    @Test
    void quietHoursDeferAFireWarningAndASubSevereNwsWarning() {
        // The two non-critical shapes that really reach pushSevereAlert: the
        // Fire Warning template (WILDFIRE_NEAR, never bypasses) and a
        // critical_push template NWS stamped below Severe.
        prefs(quietNow());

        assertThat(policy.evaluate("u@x.com", Category.WILDFIRE_NEAR, "Severe")).isEqualTo(Lane.B);
        assertThat(policy.evaluate("u@x.com", Category.WILDFIRE_NEAR, "Extreme"))
                .as("the bypass is per category: wildfire is not on the list at any severity")
                .isEqualTo(Lane.B);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Moderate")).isEqualTo(Lane.B);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Unknown")).isEqualTo(Lane.B);
    }

    // ==================================================================
    // Critical bypass: inside quiet hours AND exempt from the rate cap
    // ==================================================================

    @Test
    void everyCriticalCategoryBreaksThroughQuietHours() {
        prefs(quietNow());

        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Extreme")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "Severe"))
                .as("the severity word ingest really sends for M6+").isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "6.0"))
                .as("numeric magnitude, at the threshold").isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.PLAN_ACTIVATION_RECEIVED, null)).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.GROUP_ALERT_HOUSEHOLD, null)).isEqualTo(Lane.A);
    }

    @Test
    void criticalCategoriesAreExemptFromTheRateCapAndOthersAreNot() {
        when(rateLimiter.tryConsume(any(), any())).thenReturn(false);   // every cap exhausted
        prefs(defaults());

        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Severe")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "Severe")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "6.0")).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.PLAN_ACTIVATION_RECEIVED, null)).isEqualTo(Lane.A);
        assertThat(policy.evaluate("u@x.com", Category.GROUP_ALERT_HOUSEHOLD, null)).isEqualTo(Lane.A);

        assertThat(policy.evaluate("u@x.com", Category.WILDFIRE_NEAR, "Severe"))
                .as("a capped wildfire push demotes to the inbox, it is not dropped")
                .isEqualTo(Lane.B);
        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Moderate")).isEqualTo(Lane.B);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "Moderate")).isEqualTo(Lane.B);
    }

    @Test
    void anExplicitOptOutBeatsTheBypassForEveryCriticalCategory() {
        UserAlertPreference p = quietNow();
        p.setNwsAlerts(false);
        p.setEarthquakes(false);
        p.setPlanActivations(false);
        p.setGroupAlerts(false);
        prefs(p);

        assertThat(policy.evaluate("u@x.com", Category.NWS_SEVERE_EXTREME, "Extreme")).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, "Severe")).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate("u@x.com", Category.PLAN_ACTIVATION_RECEIVED, null)).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate("u@x.com", Category.GROUP_ALERT_HOUSEHOLD, null)).isEqualTo(Lane.DROP);
    }

    // ==================================================================
    // USGS: the bypass reads the severity WORD dispatch really passes
    // ==================================================================

    /**
     * AlertDispatchService.pushSevereAlert passes {@code a.severity()}, and
     * AlertIngestService.normalizeUsgs maps M6+ to "Severe" — never a number.
     * The bypass used to parse only a magnitude, so in production no quake got
     * through quiet hours (the "6.1" tests above masked it). These go through
     * the real ingest normaliser so the string under test is the real one.
     */
    @Test
    void aMajorQuakeFromTheDispatchPathBypassesQuietHours() throws Exception {
        NormalizedAlert m62 = usgsFeature(6.2);
        assertThat(m62.severity()).as("ingest's word for M6.2").isEqualTo("Severe");

        prefs(quietNow());
        assertThat(policy.evaluate("u@x.com", AlertDispatchService.pushCategoryFor(m62, null), m62.severity()))
                .as("an M6.2 at 2am must still interrupt")
                .isEqualTo(Lane.A);
    }

    @Test
    void aModerateQuakeFromTheDispatchPathStillWaitsForMorning() throws Exception {
        NormalizedAlert m55 = usgsFeature(5.5);
        assertThat(m55.severity()).isEqualTo("Moderate");

        prefs(quietNow());
        assertThat(policy.evaluate("u@x.com", Category.USGS_QUAKE_MAJOR, m55.severity()))
                .isEqualTo(Lane.B);
    }

    private static NormalizedAlert usgsFeature(double mag) throws Exception {
        String json = "{\"type\":\"Feature\",\"id\":\"us7000test\","
                + "\"properties\":{\"mag\":" + mag + ",\"place\":\"10 km N of Somewhere\","
                + "\"time\":1790000000000,\"url\":\"https://earthquake.usgs.gov/\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[-118.2,34.0,10.0]}}";
        return new AlertIngestService(new NwsZoneService()).normalizeUsgs(new ObjectMapper().readTree(json));
    }

    /**
     * Quiet hours on, window centred on the current UTC time — in-window whenever
     * the test runs. Replaces a 00:00–23:59 window in the default zone, which was
     * out-of-window for the minute 23:59–00:00 New York time and failed then.
     */
    private static UserAlertPreference quietNow() {
        UserAlertPreference p = defaults();
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        p.setQuietHoursEnabled(true);
        p.setTimezone("UTC");
        p.setQuietStart(now.minusHours(2));
        p.setQuietEnd(now.plusHours(2));
        return p;
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
