package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
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

    @Test
    void anUnknownHouseholdOrAnUnreadableProfileChangesNothing() {
        GroupRepo groups = mock(GroupRepo.class);
        RiskProfileService risk = mock(RiskProfileService.class);
        ScenarioRelevance relevance = new ScenarioRelevance(groups, risk);

        assertThat(relevance.ranks(null)).isEmpty();
        verifyNoInteractions(groups, risk);

        when(groups.findByGroupId("org")).thenReturn(Optional.of(group("org", "Organization")));
        assertThat(relevance.ranks("org")).isEmpty();

        when(groups.findByGroupId("hh")).thenReturn(Optional.of(group("hh", "Household")));
        when(risk.resolveFor(any())).thenThrow(new IllegalStateException("geocoder down"));
        assertThat(relevance.ranks("hh")).isEmpty();
    }

    private static Group group(String id, String type) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType(type);
        return g;
    }
}
