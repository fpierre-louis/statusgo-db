package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Location-Based Risk Engine (Phase 1 MVP) guards: the exact resolution
 * precedence (saved_home → household_zip → last_known_zip → unknown), the
 * static hazard rule engine, and the pure zip/state resolvers.
 */
@ExtendWith(MockitoExtension.class)
class RiskProfileServiceTest {

    @Mock UserSavedLocationService savedLocationService;
    @Mock UserInfoRepo userInfoRepo;
    @Mock AlertIngestService alertIngestService;

    private RiskProfileService service() {
        return new RiskProfileService(savedLocationService, userInfoRepo, alertIngestService);
    }

    private static Group group(String zip) {
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setOwnerEmail("owner@x.com");
        g.setZipCode(zip);
        return g;
    }

    private static UserSavedLocation savedHome(String state, String zipBucket) {
        UserSavedLocation l = new UserSavedLocation();
        l.setOwnerEmail("owner@x.com");
        l.setName("Home");
        l.setHome(true);
        l.setState(state);
        l.setZipBucket(zipBucket);
        return l;
    }

    // --- Precedence 1: saved_home ---------------------------------------

    @Test
    void savedHome_california_appendsEarthquakeAndWildfire() {
        when(savedLocationService.homeFor("owner@x.com"))
                .thenReturn(Optional.of(savedHome("California", "941")));

        RiskProfileDto p = service().resolveFor(group("94105"));

        assertThat(p.locationBasis()).isEqualTo("saved_home");
        assertThat(p.geoKey()).isEqualTo("CA");
        assertThat(p.regionLabel()).isEqualTo("California");
        assertThat(p.risks()).extracting(RiskDto::hazard)
                .containsExactly("earthquake", "wildfire"); // very_high before high
        assertThat(p.risks().get(0).tier()).isEqualTo("very_high");
        assertThat(p.riskAdjustedRequirements()).extracting(RiskAdjustedRequirementDto::key)
                .contains("earthquake_utility_wrench", "earthquake_bed_kit", "wildfire_respirators");
        assertThat(p.riskAdjustedRequirements()).allMatch(r -> "risk_added".equals(r.origin()));
        // Priority-sorted, earthquake (very_high) requirements ahead of wildfire.
        assertThat(p.riskAdjustedRequirements().get(0).hazard()).isEqualTo("earthquake");
    }

    // --- Live active-alert upgrade -------------------------------------

    @Test
    void activeSevereAlert_addsActiveAlert_andPriorityZeroUpgrade() {
        UserSavedLocation home = savedHome("Oklahoma", "730");
        home.setLatitude(35.46);
        home.setLongitude(-97.51);
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.of(home));
        AlertIngestService.NormalizedAlert alert = new AlertIngestService.NormalizedAlert(
                "X1", "NWS", "Severe", "Tornado Warning for Oklahoma County",
                "A tornado warning is in effect.", "Oklahoma County, OK",
                "2026-07-09T21:00:00Z", "2026-07-09T21:30:00Z", Map.of("type", "Polygon"));
        when(alertIngestService.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new AlertIngestService.Snapshot(List.of(alert), Instant.EPOCH, Instant.EPOCH));

        RiskProfileDto p = service().resolveFor(group(null));

        // Live alert surfaced, hazard inferred, backend-authored action attached.
        assertThat(p.activeAlerts()).hasSize(1);
        assertThat(p.activeAlerts().get(0).hazard()).isEqualTo("tornado");
        assertThat(p.activeAlerts().get(0).severity()).isEqualTo("Severe");
        assertThat(p.activeAlerts().get(0).instruction()).contains("Take cover");
        // Priority-0 active_alert_upgraded requirement floats above every risk_added item.
        RiskAdjustedRequirementDto top = p.riskAdjustedRequirements().get(0);
        assertThat(top.origin()).isEqualTo("active_alert_upgraded");
        assertThat(top.priority()).isZero();
        assertThat(top.hazard()).isEqualTo("tornado");
    }

    @Test
    void moderateAlert_isIgnored_asNotLifeThreatening() {
        UserSavedLocation home = savedHome("Oklahoma", "730");
        home.setLatitude(35.46);
        home.setLongitude(-97.51);
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.of(home));
        AlertIngestService.NormalizedAlert minor = new AlertIngestService.NormalizedAlert(
                "X2", "NWS", "Moderate", "Wind Advisory", "Breezy afternoon.", "OK",
                null, null, Map.of("type", "Polygon"));
        when(alertIngestService.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new AlertIngestService.Snapshot(List.of(minor), Instant.EPOCH, Instant.EPOCH));

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.activeAlerts()).isEmpty();
        assertThat(p.riskAdjustedRequirements()).noneMatch(r -> "active_alert_upgraded".equals(r.origin()));
    }

    // --- Precedence 2: household_zip (no saved home) --------------------

    @Test
    void householdZip_usedWhenNoSavedHome() {
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.empty());

        RiskProfileDto p = service().resolveFor(group("94105")); // SF → CA

        assertThat(p.locationBasis()).isEqualTo("household_zip");
        assertThat(p.geoKey()).isEqualTo("CA");
        assertThat(p.risks()).extracting(RiskDto::hazard).contains("earthquake");
    }

    // --- Precedence 3: last_known_zip (no saved, no group zip) ----------

    @Test
    void lastKnownZip_usedWhenNoSavedAndNoGroupZip() {
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.empty());
        UserInfo u = new UserInfo();
        u.setUserEmail("owner@x.com");
        u.setLastKnownZip("33101"); // Miami → FL
        when(userInfoRepo.findByUserEmailIgnoreCase("owner@x.com")).thenReturn(Optional.of(u));

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.locationBasis()).isEqualTo("last_known_zip");
        assertThat(p.geoKey()).isEqualTo("FL");
        assertThat(p.risks()).extracting(RiskDto::hazard).contains("hurricane", "flood");
    }

    // --- Precedence 4: unknown fallback + CTA ---------------------------

    @Test
    void unknown_whenNoLocationSignal_returnsBaselinePlusSetLocationCta() {
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.empty());
        lenient().when(userInfoRepo.findByUserEmailIgnoreCase("owner@x.com"))
                .thenReturn(Optional.empty());

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.locationBasis()).isEqualTo("unknown");
        assertThat(p.geoKey()).isNull();
        assertThat(p.risks()).isEmpty();
        assertThat(p.riskAdjustedRequirements()).hasSize(1);
        RiskAdjustedRequirementDto cta = p.riskAdjustedRequirements().get(0);
        assertThat(cta.key()).isEqualTo("set_home_location");
        assertThat(cta.priority()).isZero();          // top priority
        assertThat(cta.origin()).isEqualTo("location_prompt");
        assertThat(cta.hazard()).isNull();
        assertThat(cta.label()).contains("Set your home location");
    }

    @Test
    void nullHousehold_isUnknown() {
        RiskProfileDto p = service().resolveFor(null);
        assertThat(p.locationBasis()).isEqualTo("unknown");
        assertThat(p.riskAdjustedRequirements().get(0).key()).isEqualTo("set_home_location");
    }

    // --- Pure resolvers -------------------------------------------------

    @Test
    void zipToStateCode_mapsPrefixRanges() {
        assertThat(RiskProfileService.zipToStateCode("94103")).isEqualTo("CA"); // SF
        assertThat(RiskProfileService.zipToStateCode("33101")).isEqualTo("FL"); // Miami
        assertThat(RiskProfileService.zipToStateCode("73008")).isEqualTo("OK"); // OK
        assertThat(RiskProfileService.zipToStateCode("100")).isEqualTo("NY");   // bucket only
        assertThat(RiskProfileService.zipToStateCode("ab")).isNull();           // <3 digits
        assertThat(RiskProfileService.zipToStateCode(null)).isNull();
        assertThat(RiskProfileService.zipToStateCode("00501")).isNull();        // prefix 005 unmapped
    }

    @Test
    void stateCodeFromName_normalizesNameOrCode() {
        assertThat(RiskProfileService.stateCodeFromName("California")).isEqualTo("CA");
        assertThat(RiskProfileService.stateCodeFromName("california")).isEqualTo("CA");
        assertThat(RiskProfileService.stateCodeFromName("CA")).isEqualTo("CA");
        assertThat(RiskProfileService.stateCodeFromName("Florida")).isEqualTo("FL");
        assertThat(RiskProfileService.stateCodeFromName("Nowhere")).isNull();
        assertThat(RiskProfileService.stateCodeFromName(null)).isNull();
    }
}
