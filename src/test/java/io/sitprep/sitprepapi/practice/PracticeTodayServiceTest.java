package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.InProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.LocalHazardDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioCardDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioProgressDto;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Today on the Practice home (redesign FR-5): one story, the re-entry line, local hazards. */
class PracticeTodayServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T18:00:00Z");

    private static ScenarioCardDto card(String key) {
        return new ScenarioCardDto(key, 1, key, "summary", 5, false, null, "tornado", 4);
    }

    private static CatalogDto catalog(ScenarioCardDto... cards) {
        return new CatalogDto(ScenarioDtos.SCHEMA_VERSION, true, null, List.of(cards), List.of(), null);
    }

    @Test
    void recommendsTheFirstStoryInRiskOrder() {
        assertThat(PracticeTodayService.recommend(catalog(card("a"), card("b")), Map.of(), null, NOW).key())
                .isEqualTo("a");
    }

    @Test
    void skipsWhatIsBeingContinuedInProgressOrJustDone() {
        InProgressDto running = new InProgressDto(7, "a", "a", 2, "n");
        Map<String, ScenarioProgressDto> progress = Map.of(
                "b", new ScenarioProgressDto("b", 1, NOW.minus(Duration.ofDays(3)), null),
                "c", new ScenarioProgressDto("c", 0, null, 9L),
                "d", new ScenarioProgressDto("d", 2, NOW.minus(Duration.ofDays(45)), null));
        assertThat(PracticeTodayService.recommend(catalog(card("a"), card("b"), card("c"), card("d")), progress, running, NOW).key())
                .as("a continues, b was done 3 days ago, c is in progress; d was last done 45 days ago")
                .isEqualTo("d");
    }

    @Test
    void nothingToRecommendIsNull_neverAFiller() {
        Map<String, ScenarioProgressDto> progress = Map.of("a", new ScenarioProgressDto("a", 1, NOW.minusSeconds(60), null));
        assertThat(PracticeTodayService.recommend(catalog(card("a")), progress, null, NOW)).isNull();
        assertThat(PracticeTodayService.recommend(catalog(), Map.of(), null, NOW)).isNull();
    }

    @Test
    void reentryIsFirstGapOrRecent() {
        assertThat(PracticeTodayService.reentryFor(null, NOW)).isEqualTo("FIRST");
        assertThat(PracticeTodayService.reentryFor(NOW.minus(Duration.ofDays(14)), NOW)).isEqualTo("GAP");
        assertThat(PracticeTodayService.reentryFor(NOW.minus(Duration.ofDays(13)), NOW)).isEqualTo("RECENT");
    }

    @Test
    void localHazardsAreHighestRiskFirst_oneRowEach_lowLeftOut() {
        List<LocalHazardDto> out = ScenarioRelevance.localHazardsFrom(List.of(
                new RiskDto("flood", "Flood", "moderate", null, null),
                new RiskDto("tornado", "Tornado", "high", null, null),
                new RiskDto("flood", "Flood", "very_high", null, null),
                new RiskDto("wildfire", "Wildfire", "low", null, null),
                new RiskDto(null, "?", "high", null, null)));
        assertThat(out).extracting(LocalHazardDto::hazard).containsExactly("flood", "tornado");
        assertThat(out.get(0).tier()).isEqualTo("very_high");
        assertThat(ScenarioRelevance.localHazardsFrom(null)).isEmpty();
    }
}
