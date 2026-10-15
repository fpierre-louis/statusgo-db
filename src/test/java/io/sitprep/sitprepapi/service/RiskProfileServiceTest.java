package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
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
 *
 * <p>The live-alert half runs against the REAL dispatch templates and the real
 * {@link AlertSafetyPolicy}, not a mock: owner ruling R2 (2026-10-13) is that
 * Home's alert text comes from the same policy-gated path as the feed card, and
 * a mocked template would only prove the wiring, not the gate.</p>
 */
@ExtendWith(MockitoExtension.class)
class RiskProfileServiceTest {

    @Mock UserSavedLocationService savedLocationService;
    @Mock UserInfoRepo userInfoRepo;
    @Mock AlertIngestService alertIngestService;

    private static AlertDispatchService dispatch;
    private static AlertFeedService feed;

    /** Far future, so the lifecycle gate never ages a fixture out under a real clock. */
    private static final String IN_FORCE_UNTIL = "2099-01-01T00:00:00Z";

    /** The strings the deleted ALERT_ACTION map authored. None may reappear anywhere. */
    private static final List<String> RETIRED_DIRECTIVES = List.of(
            "Take cover now", "Follow evacuation orders now", "Move to higher ground immediately",
            "evacuate immediately", "Stay indoors and off the roads", "Drop, cover, and hold on",
            "Follow official guidance and monitor local alerts");

    @BeforeAll
    static void loadTemplates() {
        dispatch = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
        feed = new AlertFeedService(null, dispatch);
    }

    private RiskProfileService service() {
        return new RiskProfileService(savedLocationService, userInfoRepo, alertIngestService, dispatch);
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

    /** Home in Oklahoma City with the given alerts in the point snapshot. */
    private void homeWithAlerts(AlertIngestService.NormalizedAlert... alerts) {
        UserSavedLocation home = savedHome("Oklahoma", "730");
        home.setLatitude(35.46);
        home.setLongitude(-97.51);
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.of(home));
        AlertIngestService.Snapshot snap =
                new AlertIngestService.Snapshot(List.of(alerts), Instant.EPOCH, Instant.EPOCH);
        when(alertIngestService.getSnapshotForPoint(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(snap);
        lenient().when(alertIngestService.getSnapshot()).thenReturn(snap);
    }

    private static List<RiskAdjustedRequirementDto> actNow(RiskProfileDto p) {
        return p.riskAdjustedRequirements().stream()
                .filter(r -> "active_alert_upgraded".equals(r.origin()))
                .toList();
    }

    /** Every string the profile ships, so a retired directive cannot hide in any field. */
    private static String allText(RiskProfileDto p) {
        StringBuilder sb = new StringBuilder();
        for (ActiveAlertDto a : p.activeAlerts()) {
            sb.append(a.headline()).append('|').append(a.instruction()).append('|')
                    .append(a.whatToDo()).append('|');
        }
        for (RiskAdjustedRequirementDto r : p.riskAdjustedRequirements()) {
            sb.append(r.label()).append('|').append(r.detail()).append('|').append(r.cta()).append('|');
        }
        return sb.toString();
    }

    private static AlertCardDto cardFor(AlertIngestService.NormalizedAlert a) {
        return feed.toCard(a, AlertIngestService.MatchType.ZONE, 50);
    }

    @Test
    void alertActionMap_isGone() {
        // R2(a): the hand-written hazard→directive map is deleted, not just unused.
        assertThat(Arrays.stream(RiskProfileService.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("ALERT_ACTION");
    }

    @Test
    void severeFloodWatch_noActNow_noAuthoredDirective_instructionVerbatim() {
        // Flood Watch ships at Severe (it passes the Severe/Extreme filter, which
        // is how it used to get "Move to higher ground immediately" as its
        // `instruction` and an "act now" row). A watch is a prepare message.
        String verbatim = "You should monitor later forecasts and be alert for possible "
                + "Flood Warnings.";
        AlertIngestService.NormalizedAlert withText = TestAlerts.nwsWatch("Flood Watch")
                .id("FW1").instruction(verbatim).endsAt(IN_FORCE_UNTIL)
                .ugc(List.of("OKZ025")).build();
        AlertIngestService.NormalizedAlert noText = TestAlerts.nwsWatch("Flood Watch")
                .id("FW2").instruction(null).endsAt(IN_FORCE_UNTIL)
                .ugc(List.of("OKZ026")).build();
        homeWithAlerts(withText, noText);

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.activeAlerts()).hasSize(2);
        ActiveAlertDto a = p.activeAlerts().get(0);
        assertThat(a.tier()).isEqualTo("watch");
        assertThat(a.instruction()).isEqualTo(verbatim);
        assertThat(p.activeAlerts().get(1).instruction()).isNull();
        // Whatever SitPrep says is the reviewed watch copy the feed card says —
        // the same policy path, not a second opinion — and nothing else.
        assertThat(a.whatToDo()).isEqualTo(cardFor(withText).whatToDo());
        assertThat(a.issuer()).isEqualTo("the National Weather Service (NOAA)");
        assertThat(actNow(p)).as("a watch never produces an act-now row").isEmpty();
        assertThat(allText(p)).doesNotContain(RETIRED_DIRECTIVES.toArray(String[]::new));
    }

    @Test
    void activeAlert_carriesGuidanceAttributionAndDedupeKey() {
        // S2 C2 rule 7 (U5 follow-up): whatToDo travels with whose words it is,
        // the same line the feed card puts in precautionsSource, and the alert
        // carries the feed's dedupeKey so Home links to the same record.
        AlertIngestService.NormalizedAlert guided = TestAlerts.nwsWatch("Flood Watch")
                .id("urn:oid:FW1").endsAt(IN_FORCE_UNTIL).ugc(List.of("OKZ025")).build();
        AlertIngestService.NormalizedAlert official = TestAlerts.nws("Fire Warning")
                .id("FIRE1").severity("Extreme").response("Evacuate")
                .instruction("Leave now.").endsAt(IN_FORCE_UNTIL).build();
        homeWithAlerts(guided, official);

        RiskProfileDto p = service().resolveFor(group(null));

        ActiveAlertDto g = p.activeAlerts().get(0);
        assertThat(g.whatToDo()).isNotNull();
        assertThat(g.whatToDoSource()).isNotBlank().isEqualTo(cardFor(guided).precautionsSource());
        assertThat(g.dedupeKey()).isEqualTo("NWS:urn:oid:FW1").isEqualTo(cardFor(guided).dedupeKey());

        ActiveAlertDto o = p.activeAlerts().get(1);
        assertThat(o.whatToDo()).isNull();
        assertThat(o.whatToDoSource()).as("no attribution without guidance").isNull();
        assertThat(o.dedupeKey()).isEqualTo("NWS:FIRE1");
    }

    @Test
    void officialOnlyWarning_verbatimInstruction_noWhatToDo_neutralActNow() {
        // Fire Warning is approved as official_only: the issuer's words, never ours.
        String verbatim = "Evacuate now via Highway 9 eastbound. Do not return until "
                + "authorities say it is safe.";
        AlertIngestService.NormalizedAlert a = TestAlerts.nws("Fire Warning")
                .id("FIRE1").severity("Extreme").response("Evacuate")
                .instruction(verbatim).endsAt(IN_FORCE_UNTIL).build();
        homeWithAlerts(a);

        RiskProfileDto p = service().resolveFor(group(null));

        ActiveAlertDto dto = p.activeAlerts().get(0);
        assertThat(dto.instruction()).isEqualTo(verbatim);
        assertThat(dto.whatToDo()).isNull();
        assertThat(dto.guidanceMode()).isEqualTo("official_only");
        assertThat(dto.tier()).isEqualTo("warning");
        assertThat(dto.issuer()).isEqualTo("the National Weather Service (NOAA)");

        // Still a warning, so it is still act-now — but the row points at the
        // issuer's instructions rather than restating or inventing them.
        assertThat(actNow(p)).hasSize(1);
        RiskAdjustedRequirementDto row = actNow(p).get(0);
        assertThat(row.detail()).isEqualTo("Open the alert for the official instructions.");
        assertThat(row.label()).isEqualTo("Fire Warning in effect — act now");
        assertThat(row.route()).isEqualTo("/hazards?scope=home&alert=NWS%3AFIRE1");
        assertThat(allText(p)).doesNotContain(RETIRED_DIRECTIVES.toArray(String[]::new));
    }

    @Test
    void hurricaneWarningWithEvacInstruction_reviewedWhatToDo_separateFromVerbatim() {
        String verbatim = "Evacuate now if you are in zones A or B. Follow the routes "
                + "given by Miami-Dade Emergency Management.";
        AlertIngestService.NormalizedAlert a = TestAlerts.nws("Hurricane Warning")
                .id("HU1").severity("Extreme").response("Evacuate")
                .instruction(verbatim).endsAt(IN_FORCE_UNTIL).build();
        homeWithAlerts(a);

        RiskProfileDto p = service().resolveFor(group(null));

        ActiveAlertDto dto = p.activeAlerts().get(0);
        String reviewed = cardFor(a).whatToDo();
        assertThat(reviewed).as("the approved Hurricane Warning template covers Evacuate").isNotBlank();
        assertThat(dto.whatToDo()).isEqualTo(reviewed);
        assertThat(dto.instruction()).isEqualTo(verbatim);
        assertThat(dto.whatToDo()).isNotEqualTo(dto.instruction());
        assertThat(dto.guidanceMode()).isEqualTo("supplement_official");
        assertThat(dto.tier()).isEqualTo("warning");
        assertThat(dto.hazard()).isEqualTo("hurricane");

        RiskAdjustedRequirementDto row = actNow(p).get(0);
        assertThat(row.detail()).isEqualTo(reviewed);
        assertThat(row.priority()).isZero();
        assertThat(allText(p)).doesNotContain(RETIRED_DIRECTIVES.toArray(String[]::new));
    }

    /*
     * Replaces activeSevereAlert_addsActiveAlert_andPriorityZeroUpgrade, which
     * asserted `instruction` contained "Take cover" — i.e. it locked in the
     * SitPrep-authored ALERT_ACTION text that R2 removes, under the CAP field
     * name. What it pinned that is still true is kept: the alert surfaces, the
     * hazard key, the priority-0 row on top, the source-qualified deep link.
     */
    @Test
    void tornadoWarning_addsActiveAlert_andPriorityZeroUpgrade_fromReviewedCopy() {
        String verbatim = "TAKE COVER NOW! Move to a basement or an interior room on the "
                + "lowest floor of a sturdy building.";
        AlertIngestService.NormalizedAlert alert = TestAlerts.nws("Tornado Warning")
                .id("X1").response("Shelter")
                .headline("Tornado Warning for Oklahoma County")
                .description("A tornado warning is in effect.")
                .instruction(verbatim)
                .area("Oklahoma County, OK")
                .startedAt("2026-07-09T21:00:00Z").endsAt(IN_FORCE_UNTIL)
                .geometry(Map.of("type", "Polygon"))
                .ugc(List.of("OKC109")).same(List.of("040109"))
                .build();
        homeWithAlerts(alert);

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.activeAlerts()).hasSize(1);
        ActiveAlertDto dto = p.activeAlerts().get(0);
        assertThat(dto.hazard()).isEqualTo("tornado");
        assertThat(dto.severity()).isEqualTo("Severe");
        assertThat(dto.instruction()).isEqualTo(verbatim);
        assertThat(dto.whatToDo()).isEqualTo(cardFor(alert).whatToDo()).isNotBlank();
        // Priority-0 active_alert_upgraded requirement floats above every risk_added item.
        RiskAdjustedRequirementDto top = p.riskAdjustedRequirements().get(0);
        assertThat(top.origin()).isEqualTo("active_alert_upgraded");
        assertThat(top.priority()).isZero();
        assertThat(top.hazard()).isEqualTo("tornado");
        assertThat(top.detail()).isEqualTo(dto.whatToDo());
        assertThat(top.route()).isEqualTo("/hazards?scope=home&alert=NWS%3AX1");
    }

    @Test
    void cancelledWarningInsideItsWindow_isNotAnActiveAlert() {
        // A CAP Cancel still inside its endsAt window used to ship as an active
        // alert with "Take cover now". It is a retraction, not a hazard.
        AlertIngestService.NormalizedAlert cancelled = TestAlerts.nws("Tornado Warning")
                .id("X9").messageType("Cancel").response("Shelter")
                .instruction("The tornado warning has been cancelled.")
                .endsAt(IN_FORCE_UNTIL).build();
        homeWithAlerts(cancelled);

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.activeAlerts()).isEmpty();
        assertThat(actNow(p)).isEmpty();
    }

    @Test
    void replacedAlert_isDropped_whileItsReplacementShows() {
        // Both ends of a CAP `references` edge in one snapshot: the old message is
        // no longer in force, so it must not present its instruction as current.
        AlertIngestService.NormalizedAlert old = TestAlerts.nws("Tornado Warning")
                .id("OLD").response("Shelter").instruction("Old wording.")
                .endsAt(IN_FORCE_UNTIL).build();
        AlertIngestService.NormalizedAlert replacement = TestAlerts.nws("Tornado Warning")
                .id("NEW").messageType("Update").response("Shelter").instruction("New wording.")
                .references(List.of("OLD")).endsAt(IN_FORCE_UNTIL).build();
        homeWithAlerts(old, replacement);

        RiskProfileDto p = service().resolveFor(group(null));

        assertThat(p.activeAlerts()).extracting(ActiveAlertDto::id).containsExactly("NEW");
        assertThat(actNow(p)).singleElement()
                .extracting(RiskAdjustedRequirementDto::route)
                .isEqualTo("/hazards?scope=home&alert=NWS%3ANEW");
    }

    @Test
    void moderateAlert_isIgnored_asNotLifeThreatening() {
        UserSavedLocation home = savedHome("Oklahoma", "730");
        home.setLatitude(35.46);
        home.setLongitude(-97.51);
        when(savedLocationService.homeFor("owner@x.com")).thenReturn(Optional.of(home));
        AlertIngestService.NormalizedAlert minor = TestAlerts.nws("Wind Advisory")
                .id("X2").severity("Moderate")
                .headline("Wind Advisory").description("Breezy afternoon.").area("OK")
                .geometry(Map.of("type", "Polygon"))
                .ugc(List.of("OKZ025")).same(List.of("040109"))
                .build();
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
