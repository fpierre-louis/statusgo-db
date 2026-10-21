package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdPet;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import io.sitprep.sitprepapi.service.RiskProfileService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** EXEC-I3: which scenarios match the household's regional hazards, and how strongly. */
class ScenarioRelevanceTest {

    private static RiskDto risk(String hazard, String tier) {
        return new RiskDto(hazard, hazard, tier, "reason", "source");
    }

    @Test
    void ranksScenariosByTheirHazardsTierAndIgnoresLowAndUnmappedHazards() {
        Map<String, Integer> ranks = ScenarioRelevance.ranksFor(List.of(
                risk("earthquake", "very_high"), risk("flood", "moderate"), risk("wildfire", "high")));
        assertThat(ranks).containsExactlyInAnyOrderEntriesOf(Map.of(
                "earthquake-first-five-minutes", 0, "flash-flood-turn-around-route", 2));
        assertThat(ScenarioRelevance.ranksFor(List.of(risk("flood", "low")))).isEmpty();
        assertThat(ScenarioRelevance.ranksFor(null)).isEmpty();
    }

    private final GroupRepo groups = mock(GroupRepo.class);
    private final RiskProfileService risk = mock(RiskProfileService.class);
    private final HouseholdPetRepo pets = mock(HouseholdPetRepo.class);
    private final DemographicRepo demographics = mock(DemographicRepo.class);
    private final ScenarioRelevance relevance = new ScenarioRelevance(groups, risk, pets, demographics);

    @Test
    void anUnknownHouseholdOrAnUnreadableProfileChangesNothing() {
        assertThat(relevance.matches(null)).isEmpty();
        verifyNoInteractions(groups, risk, pets, demographics);

        when(groups.findByGroupId("org")).thenReturn(Optional.of(group("org", "Organization")));
        assertThat(relevance.matches("org")).isEmpty();

        when(groups.findByGroupId("hh")).thenReturn(Optional.of(group("hh", "Household")));
        when(risk.resolveFor(any())).thenThrow(new IllegalStateException("geocoder down"));
        when(pets.findByHouseholdIdOrderByCreatedAtAsc("hh")).thenReturn(List.of());
        when(demographics.findFirstByHouseholdIdOrderByIdDesc("hh")).thenReturn(Optional.empty());
        assertThat(relevance.matches("hh")).isEmpty();
    }

    // -- I10: a household with pets sees the pets scenario first ----------------

    @Test
    void aNamedPetPutsThePetsScenarioNearTheTopEvenWhenTheProfileFails() {
        when(groups.findByGroupId("hh")).thenReturn(Optional.of(group("hh", "Household")));
        when(risk.resolveFor(any())).thenThrow(new IllegalStateException("geocoder down"));
        when(pets.findByHouseholdIdOrderByCreatedAtAsc("hh")).thenReturn(List.of(new HouseholdPet()));
        assertThat(relevance.matches("hh")).containsExactly(Map.entry("evacuating-with-pets",
                new ScenarioRelevance.Match(1, "Your household has pets")));
    }

    @Test
    void aPetCountOnTheDemographicsCountsTooAndNoPetsMeansNoNote() {
        when(groups.findByGroupId("hh")).thenReturn(Optional.of(group("hh", "Household")));
        when(pets.findByHouseholdIdOrderByCreatedAtAsc("hh")).thenReturn(List.of());
        Demographic d = new Demographic();
        d.setCats(1);
        when(demographics.findFirstByHouseholdIdOrderByIdDesc("hh")).thenReturn(Optional.of(d));
        assertThat(relevance.matches("hh")).containsKey("evacuating-with-pets");

        d.setCats(0);
        assertThat(relevance.matches("hh")).doesNotContainKey("evacuating-with-pets");
    }

    @Test
    void aLocalHazardAndPetsBothMatchAndAVeryHighHazardStaysFirst() {
        when(groups.findByGroupId("hh")).thenReturn(Optional.of(group("hh", "Household")));
        when(risk.resolveFor(any())).thenReturn(new io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto(
                "saved_home", "CA", "California", List.of(risk("earthquake", "very_high")), List.of(), List.of(), null, null));
        when(pets.findByHouseholdIdOrderByCreatedAtAsc("hh")).thenReturn(List.of(new HouseholdPet()));
        var m = relevance.matches("hh");
        assertThat(m.get("earthquake-first-five-minutes").rank()).isLessThan(m.get("evacuating-with-pets").rank());
        assertThat(m.get("earthquake-first-five-minutes").note()).isEqualTo("A risk where you live");
    }

    private static Group group(String id, String type) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType(type);
        return g;
    }
}
