package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.EmergencyContactGroupDto;
import io.sitprep.sitprepapi.dto.EmergencyContactGroupDto.ContactDto;
import io.sitprep.sitprepapi.dto.MeDto.PillarCounts;
import io.sitprep.sitprepapi.dto.MeDto.PillarRollup;
import io.sitprep.sitprepapi.dto.MeetingPlaceDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.CommsReadinessDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.CommsRecommendationDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.PulseDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine guards: the comms-pillar evaluation over typed FEMA/Red Cross
 * contracts, and the pulse formula parity with the retired
 * {@code useReadinessPulse.js} (soft defaults, recommendedMin denominators,
 * roster-derived family pillar, tier thresholds).
 */
@SpringBootTest
@ActiveProfiles("test")
class HouseholdReadinessServiceTest {

    @Autowired HouseholdReadinessService engine;

    private static ContactDto contact(String contactType, String phone, String email, String address) {
        return new ContactDto(1L, "Person", phone, email, address, "Role",
                contactType, null, null, null, null, null);
    }

    private static MeetingPlaceDto place(String tierKey, String meetingTier) {
        return new MeetingPlaceDto(1L, "Spot", null, null, null,
                tierKey, meetingTier, null, null, null, null, null, false);
    }

    @Test
    void comms_detectsCoverageAndTierGaps() {
        EmergencyContactGroupDto group = new EmergencyContactGroupDto(1L, "Family", List.of(
                contact("OUT_OF_AREA", "801-555-1212", null, "12 Elm St, Boise"),
                contact("LOCAL", null, null, null)));
        List<MeetingPlaceDto> places = List.of(
                place("safe_room", "INDOOR_SAFE_ROOM"),
                place("in_town", "OUT_OF_TOWN"));

        CommsReadinessDto c = engine.evaluateComms(List.of(group), places);

        assertThat(c.score()).isEqualTo(80);
        assertThat(c.contactsScore()).isEqualTo(100);
        assertThat(c.meetingPlacesScore()).isEqualTo(67);
        assertThat(c.hasContacts()).isTrue();
        assertThat(c.contactCount()).isEqualTo(2);
        assertThat(c.hasOutOfAreaContact()).isTrue();
        assertThat(c.hasLocalContact()).isTrue();
        assertThat(c.hasMedicalContact()).isFalse();
        assertThat(c.contactsMissingPhoneNumbers()).isTrue();  // neighbor has none
        assertThat(c.contactsMissingAltMethod()).isFalse();    // aunt has an address
        assertThat(c.missingIndoorSafeRoom()).isFalse();
        assertThat(c.missingOutsideHomeMeetup()).isTrue();
        assertThat(c.missingOutOfTownMeetup()).isFalse();
        assertThat(c.missingNearHomeMeetup()).isTrue();
        assertThat(c.missingInTownMeetup()).isFalse();
        assertThat(c.missingNeighborhoodMeetup()).isTrue();
        assertThat(c.missingOutOfAreaMeetup()).isTrue();

        List<String> recKeys = c.recommendations().stream()
                .map(CommsRecommendationDto::key).toList();
        assertThat(recKeys).containsExactly(
                "medical_contact", "contact_phones",
                "meetup_outside_home");
    }

    @Test
    void comms_emptyState_leadsWithAddContacts() {
        CommsReadinessDto c = engine.evaluateComms(List.of(), List.of());
        assertThat(c.hasContacts()).isFalse();
        assertThat(c.score()).isZero();
        // No phone/alt nags when there are no contacts at all — one clear ask.
        assertThat(c.contactsMissingPhoneNumbers()).isFalse();
        assertThat(c.recommendations().get(0).key()).isEqualTo("add_contacts");
        assertThat(c.recommendations()).extracting(CommsRecommendationDto::key)
                .contains("meetup_indoor_safe_room", "meetup_outside_home", "meetup_out_of_town");
    }

    @Test
    void pulse_noTasksNoHousehold_scoresStrictZero() {
        // No soft defaults: no tasks + no household → every pillar 0.
        // Readiness is strictly requirement satisfaction, never a floor.
        PulseDto p = engine.pulseFor(null, null);
        assertThat(p.pillars()).extracting(x -> x.pct())
                .containsExactly(0, 0, 0, 0);
        assertThat(p.overall()).isZero();
        assertThat(p.tierKey()).isEqualTo("start");
        assertThat(p.tierLabel()).isEqualTo("Just getting started");
        assertThat(p.pillars().get(0).hint()).isEqualTo("No supplies tasks yet");
        assertThat(p.pillars().get(3).hint()).isEqualTo("No household yet");
    }

    @Test
    void pulse_countsFormula_usesRecommendedMinDenominator() {
        PillarRollup counts = new PillarRollup(
                new PillarCounts(1, 1),   // supplies: 1/max(1,5) = 20 (not 100!)
                new PillarCounts(5, 3),   // plan: (3+0)/max(5+1,5) = 50 — null household ⇒ no
                                          // primary route, so the +1 required route unit is unmet (V35).
                new PillarCounts(0, 0),   // practice: no tasks → 0 (no soft default)
                new PillarCounts(6, 6));  // family: 6/6 = 100
        PulseDto p = engine.pulseFor(counts, null);
        assertThat(p.pillars()).extracting(x -> x.pct())
                .containsExactly(20, 50, 0, 100);
        assertThat(p.pillars().get(0).hint()).isEqualTo("1 of 5 done");
        assertThat(p.overall()).isEqualTo(43); // round((20+50+0+100)/4) = round(42.5)
    }

    @Test
    void planPillar_primaryRouteIsARequiredBaselineUnit() {
        // WITH a primary route the +1 required unit is satisfied, so the same
        // tasks score higher than WITHOUT it — the route is a baseline requirement.
        assertThat(HouseholdReadinessService.planPillarPct(new PillarCounts(5, 3), true)).isEqualTo(67);  // (3+1)/(5+1)
        assertThat(HouseholdReadinessService.planPillarPct(new PillarCounts(5, 3), false)).isEqualTo(50); // 3/(5+1)
        // A set primary route alone (no plan tasks) earns baseline credit.
        assertThat(HouseholdReadinessService.planPillarPct(null, true)).isEqualTo(20);  // 1/max(1,5)
        // Nothing set → 0 (no soft floor).
        assertThat(HouseholdReadinessService.planPillarPct(null, false)).isZero();
    }

    @Test
    void tierThresholds_matchReadyTier() {
        assertThat(HouseholdReadinessService.tierLabel(92)).isEqualTo("We're set");
        assertThat(HouseholdReadinessService.tierLabel(70)).isEqualTo("In good shape");
        assertThat(HouseholdReadinessService.tierLabel(50)).isEqualTo("Coming along");
        assertThat(HouseholdReadinessService.tierLabel(30)).isEqualTo("Some gaps to close");
        assertThat(HouseholdReadinessService.tierLabel(10)).isEqualTo("Just getting started");
    }
}
