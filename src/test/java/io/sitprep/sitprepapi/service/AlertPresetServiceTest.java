package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.dto.AlertPresetDtos.AlertPresetDto;
import io.sitprep.sitprepapi.dto.AlertPresetDtos.AlertPresetsResponse;
import io.sitprep.sitprepapi.dto.UserAlertPreferenceDto;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.resource.AlertPresetResource;
import io.sitprep.sitprepapi.service.AlertPresetService.Preset;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Alert presets (CONTRACT §6, Ready for More B2). Real {@link PushPolicyService}
 * over a mocked repo, the same style as {@code HazardPushPolicyTest}, so the lane
 * assertions run the one policy engine presets are applied through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertPresetServiceTest {

    private static final String ME = "u@x.com";
    private static final List<String> KEYS = List.of("BALANCED", "HAZARD_AWARE", "HOUSEHOLD_FOCUS", "QUIET_HOURS");

    @Mock UserAlertPreferenceRepo repo;
    @Mock RateLimiterService rateLimiter;

    private PushPolicyService policy;
    private AlertPresetService presets;
    private UserAlertPreference row;

    @BeforeEach
    void setUp() {
        policy = new PushPolicyService(repo, rateLimiter);
        presets = new AlertPresetService(policy);
        when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
        when(repo.save(any())).thenAnswer(returnsFirstArg());
        use(new UserAlertPreference());
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void use(UserAlertPreference p) {
        p.setUserEmail(ME);
        row = p;
        when(repo.findByEmail(ME)).thenReturn(Optional.of(p));
    }

    /** Every boolean off — the worst row a user could have built by hand. */
    private static UserAlertPreference allFalse() {
        UserAlertPreference p = new UserAlertPreference();
        p.setPushEnabled(false);
        p.setInboxEnabled(false);
        p.setNwsAlerts(false);
        p.setEarthquakes(false);
        p.setWildfires(false);
        p.setGroupAlerts(false);
        p.setPlanActivations(false);
        p.setActivationAcks(false);
        p.setTaskAssignments(false);
        p.setPendingMembers(false);
        p.setDrills(false);
        p.setQuietHoursEnabled(false);
        return p;
    }

    /**
     * A zone whose local time is about 02:00 right now, so QUIET_HOURS'
     * 21:00–07:00 window is active whenever the test runs.
     */
    private static String zoneWhereItIsTwoAm() {
        int utcMin = LocalTime.now(ZoneOffset.UTC).toSecondOfDay() / 60;
        int diff = 120 - utcMin;
        if (diff < -720) diff += 1440;
        return ZoneOffset.ofTotalSeconds(diff * 60).getId();
    }

    /** Turn quiet hours on with a window centred on now (UTC). */
    private void quietWindowActiveNow() {
        LocalTime now = LocalTime.now(ZoneOffset.UTC);
        row.setQuietHoursEnabled(true);
        row.setTimezone("UTC");
        row.setQuietStart(now.minusHours(2));
        row.setQuietEnd(now.plusHours(2));
    }

    private static Map<String, Boolean> activeByKey(AlertPresetsResponse r) {
        return r.presets().stream().collect(Collectors.toMap(AlertPresetDto::key, AlertPresetDto::active));
    }

    // ==================================================================
    // Safety floor: a preset can only strengthen safety
    // ==================================================================

    @Test
    void everyPresetRestoresTheSafetyFloorFromAnAllFalseRow() {
        for (String key : KEYS) {
            use(allFalse());
            UserAlertPreferenceDto c = presets.apply(ME, key, null).current();

            assertThat(List.of(c.pushEnabled(), c.inboxEnabled(), c.nwsAlerts(), c.earthquakes(),
                    c.wildfires(), c.groupAlerts(), c.planActivations()))
                    .as(key + " must turn every safety-floor field back on")
                    .containsOnly(true);
        }
    }

    @Test
    void afterEveryPresetTheCriticalCategoriesStillBreakThroughQuietHours() {
        for (String key : KEYS) {
            use(allFalse());
            String tz = "QUIET_HOURS".equals(key) ? zoneWhereItIsTwoAm() : null;
            presets.apply(ME, key, tz);
            // QUIET_HOURS' own window is already active (02:00 local); for the
            // others, switch quiet hours on now the way a user later could.
            if (!"QUIET_HOURS".equals(key)) quietWindowActiveNow();

            assertThat(policy.evaluate(ME, Category.NWS_SEVERE_EXTREME, "Extreme")).as(key).isEqualTo(Lane.A);
            assertThat(policy.evaluate(ME, Category.PLAN_ACTIVATION_RECEIVED, null)).as(key).isEqualTo(Lane.A);
            assertThat(policy.evaluate(ME, Category.GROUP_ALERT_HOUSEHOLD, null)).as(key).isEqualTo(Lane.A);
            assertThat(policy.evaluate(ME, Category.USGS_QUAKE_MAJOR, "Severe")).as(key).isEqualTo(Lane.A);
            assertThat(policy.evaluate(ME, Category.AGENCY_ALERT, "emergency")).as(key).isEqualTo(Lane.A);
        }
    }

    @Test
    void quietHoursDefersNonCriticalPushesToTheInbox() {
        presets.apply(ME, "QUIET_HOURS", zoneWhereItIsTwoAm());

        assertThat(policy.evaluate(ME, Category.TASK_ASSIGNED, null)).isEqualTo(Lane.B);
        assertThat(policy.evaluate(ME, Category.CHECK_IN_REQUEST, null))
                .as("check-in requests do not bypass quiet hours; the safety note must not say they do")
                .isEqualTo(Lane.B);
        assertThat(policy.evaluate(ME, Category.WILDFIRE_NEAR, "Severe"))
                .as("fire warnings do not bypass quiet hours; they go to the inbox, as the note says")
                .isEqualTo(Lane.B);
        assertThat(policy.evaluate(ME, Category.ACTIVATION_ACK, null))
                .as("QUIET_HOURS turns acknowledgment updates off")
                .isEqualTo(Lane.DROP);
    }

    /**
     * The QUIET_HOURS note, clause by clause, against the lanes it describes.
     * "Come through" is Lane A inside the window; "go to your inbox instead of
     * your lock screen" is Lane B, which writes an inbox row and sends no push
     * on every send path (hazards: {@code HazardQuietHoursInboxTest}; agency
     * alerts: {@code AgencyAlertDispatchServiceTest}).
     *
     * <p>Two clauses arrived with the owner-approved 2026-10-07 decisions.
     * "Strong earthquakes nearby": an M6.0+ quake within 80 km now pushes
     * ({@code EarthquakePushTest}) as {@code USGS_QUAKE_MAJOR} with the
     * severity word "Severe" — M5.5–5.9 stays feed-only, hence "strong".
     * "Official emergency alerts": only the agency {@code emergency} tier
     * bypasses; advisory and notice wait, hence "emergency".</p>
     */
    @Test
    void theQuietHoursNoteIsWhatThePolicyDoes() {
        Preset quiet = AlertPresetService.find("QUIET_HOURS");
        assertThat(quiet.safetyNote()).isEqualTo(
                "Severe weather warnings, strong earthquakes nearby, official emergency alerts, "
                        + "plan activations, and household alerts still come through. "
                        + "Fire warnings, check-in requests, and messages go to your inbox instead of "
                        + "your lock screen.");
        assertThat(quiet.safetyNote())
                .as("only M6.0+ quakes push; a bare \"earthquakes\" would promise M5.5-5.9 too")
                .containsIgnoringCase("strong earthquakes nearby");
        assertThat(quiet.safetyNote())
                .as("only the emergency tier bypasses; \"official alerts\" alone would promise advisories")
                .contains("official emergency alerts")
                .doesNotContainIgnoringCase("official alerts");
        assertThat(quiet.safetyNote())
                .as("feed-only warnings never reach anyone at any hour; no blanket claim about other alerts")
                .doesNotContainIgnoringCase("other alerts");

        presets.apply(ME, "QUIET_HOURS", zoneWhereItIsTwoAm());

        // "Severe weather warnings ... still come through."
        assertThat(policy.evaluate(ME, Category.NWS_SEVERE_EXTREME, "Severe")).isEqualTo(Lane.A);
        assertThat(policy.evaluate(ME, Category.NWS_SEVERE_EXTREME, "Extreme")).isEqualTo(Lane.A);
        // "strong earthquakes nearby" — the dispatch path passes the USGS severity word.
        assertThat(policy.evaluate(ME, Category.USGS_QUAKE_MAJOR, "Severe")).isEqualTo(Lane.A);
        // "official emergency alerts" — the agency's own tier, any casing.
        assertThat(policy.evaluate(ME, Category.AGENCY_ALERT, "emergency")).isEqualTo(Lane.A);
        assertThat(policy.evaluate(ME, Category.AGENCY_ALERT, "EMERGENCY")).isEqualTo(Lane.A);
        // "plan activations, and household alerts"
        assertThat(policy.evaluate(ME, Category.PLAN_ACTIVATION_RECEIVED, null)).isEqualTo(Lane.A);
        assertThat(policy.evaluate(ME, Category.GROUP_ALERT_HOUSEHOLD, null)).isEqualTo(Lane.A);

        // What the note deliberately does NOT promise: the lower agency tiers wait.
        assertThat(policy.evaluate(ME, Category.AGENCY_ALERT, "advisory")).isEqualTo(Lane.B);
        assertThat(policy.evaluate(ME, Category.AGENCY_ALERT, "notice")).isEqualTo(Lane.B);

        // "...go to your inbox instead of your lock screen." Lane B, not DROP or C.
        assertThat(policy.evaluate(ME, Category.WILDFIRE_NEAR, "Severe")).isEqualTo(Lane.B);
        assertThat(policy.evaluate(ME, Category.CHECK_IN_REQUEST, null)).isEqualTo(Lane.B);
        assertThat(policy.evaluate(ME, Category.DIRECT_MESSAGE, null)).isEqualTo(Lane.B);
    }

    @Test
    void quietHoursSetsTheWindow() {
        UserAlertPreferenceDto c = presets.apply(ME, "QUIET_HOURS", "America/Chicago").current();
        assertThat(c.quietHoursEnabled()).isTrue();
        assertThat(c.quietStart()).isEqualTo(LocalTime.of(21, 0));
        assertThat(c.quietEnd()).isEqualTo(LocalTime.of(7, 0));
        assertThat(c.timezone()).isEqualTo("America/Chicago");
        assertThat(c.activationAcks()).isFalse();
        assertThat(c.drills()).isFalse();
    }

    @Test
    void theGuardRejectsADefinitionThatBreaksTheFloor() {
        UserAlertPreferenceDto pushOff = new UserAlertPreferenceDto(false, true, true, true, true, true, true,
                null, null, null, null, true, null, null, null);
        assertThatThrownBy(() -> AlertPresetService.requireSafetyFloor(
                new Preset("BAD", "Bad", "", "", pushOff, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pushEnabled");

        UserAlertPreferenceDto wildfiresOmitted = new UserAlertPreferenceDto(true, true, true, true, null, true, true,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> AlertPresetService.requireSafetyFloor(
                new Preset("BAD", "Bad", "", "", wildfiresOmitted, false)))
                .as("omitting a floor field would let a stale false survive")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildfires");

        AlertPresetService.PRESETS.forEach(AlertPresetService::requireSafetyFloor);
    }

    // ==================================================================
    // active
    // ==================================================================

    @Test
    void presetsArePairwiseDistinct() {
        List<Preset> all = AlertPresetService.PRESETS;
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertThat(AlertPresetService.specifiedFields(all.get(i)))
                        .as(all.get(i).key() + " vs " + all.get(j).key())
                        .isNotEqualTo(AlertPresetService.specifiedFields(all.get(j)));
            }
        }
    }

    @Test
    void theDefaultRowMatchesOnlyBalanced() {
        // Entity defaults: everything on, quiet hours off.
        assertThat(presets.list(ME).presets()).filteredOn(AlertPresetDto::active)
                .extracting(AlertPresetDto::key).containsExactly("BALANCED");
    }

    @Test
    void afterApplyingEachPresetOnlyThatPresetIsActive() {
        for (String key : KEYS) {
            use(new UserAlertPreference());
            assertThat(presets.apply(ME, key, "UTC").presets()).filteredOn(AlertPresetDto::active)
                    .extracting(AlertPresetDto::key).as(key).containsExactly(key);
        }
    }

    @Test
    void hazardAwareDropsCoordinationPingsAndHouseholdFocusDropsDrills() {
        presets.apply(ME, "HAZARD_AWARE", null);
        assertThat(policy.evaluate(ME, Category.ACTIVATION_ACK, null))
                .as("the note says these are not sent at all, not even to the inbox").isEqualTo(Lane.DROP);
        assertThat(policy.evaluate(ME, Category.TASK_ASSIGNED, null)).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate(ME, Category.PENDING_MEMBER_REQUEST, null)).isEqualTo(Lane.DROP);
        assertThat(policy.evaluate(ME, Category.WEEKLY_DRILL_REMINDER, null)).isEqualTo(Lane.B);

        presets.apply(ME, "HOUSEHOLD_FOCUS", null);
        assertThat(policy.evaluate(ME, Category.TASK_ASSIGNED, null)).isEqualTo(Lane.A);
        assertThat(policy.evaluate(ME, Category.WEEKLY_DRILL_REMINDER, null)).isEqualTo(Lane.DROP);
    }

    @Test
    void activeTracksTheFieldsEachPresetSpecifies() {
        row.setTaskAssignments(false);
        assertThat(presets.list(ME).presets()).as("a hand-tuned row need not match any preset")
                .noneMatch(AlertPresetDto::active);
        row.setTaskAssignments(true);

        Map<String, Boolean> quiet = activeByKey(presets.apply(ME, "quiet_hours", "Europe/Paris"));
        assertThat(quiet).containsEntry("QUIET_HOURS", true).containsEntry("BALANCED", false)
                .containsEntry("HAZARD_AWARE", false).containsEntry("HOUSEHOLD_FOCUS", false);

        // QUIET_HOURS does not specify tasks or join requests, so changing them keeps it active.
        row.setTaskAssignments(false);
        row.setPendingMembers(false);
        assertThat(activeByKey(presets.list(ME))).containsEntry("QUIET_HOURS", true);

        row.setTimezone("Asia/Tokyo");
        assertThat(activeByKey(presets.list(ME))).as("timezone is ignored for QUIET_HOURS")
                .containsEntry("QUIET_HOURS", true);

        row.setQuietEnd(LocalTime.of(6, 0));
        assertThat(activeByKey(presets.list(ME))).containsEntry("QUIET_HOURS", false);
    }

    // ==================================================================
    // Keys, timezone, shape
    // ==================================================================

    @Test
    void anUnknownKeyIs404AndChangesNothing() {
        row.setDrills(false);
        assertThatThrownBy(() -> presets.apply(ME, "LOUD", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(row.isDrills()).isFalse();
    }

    @Test
    void anInvalidTimezoneIsIgnored() {
        row.setTimezone("America/Denver");
        UserAlertPreferenceDto c = presets.apply(ME, "QUIET_HOURS", "Mars/Olympus_Mons").current();
        assertThat(c.timezone()).isEqualTo("America/Denver");
        assertThat(c.quietHoursEnabled()).isTrue();
    }

    @Test
    void onlyQuietHoursReadsTheTimezone() {
        row.setTimezone("America/Denver");
        assertThat(presets.apply(ME, "BALANCED", "Europe/Paris").current().timezone()).isEqualTo("America/Denver");
    }

    @Test
    void getReturnsTheFourPresetsInOrderWithCopy() {
        AlertPresetsResponse r = presets.list(ME);
        assertThat(r.presets()).extracting(AlertPresetDto::key).containsExactlyElementsOf(KEYS);
        assertThat(r.presets()).extracting(AlertPresetDto::title)
                .containsExactly("Balanced", "Hazard aware", "Household focus", "Quiet hours");
        assertThat(r.presets()).allSatisfy(p -> {
            assertThat(p.description()).isNotBlank();
            assertThat(p.safetyNote()).isNotBlank();
        });
        assertThat(r.current()).isEqualTo(UserAlertPreferenceDto.fromEntity(row));
    }

    @Test
    void theResourceIsSelfOnlyAndNeedsAToken() {
        AlertPresetResource resource = new AlertPresetResource(presets);
        assertThatThrownBy(resource::list)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(ME, null, List.of()));
        assertThat(resource.apply("hazard_aware", null).getBody().presets())
                .filteredOn(AlertPresetDto::active).extracting(AlertPresetDto::key).contains("HAZARD_AWARE");
    }
}
