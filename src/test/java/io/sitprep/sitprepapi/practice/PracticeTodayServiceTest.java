package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.InProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.LocalHazardDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioCardDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioProgressDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private static ScenarioRun run(long id, String key, ScenarioRun.Status status, Instant started, Instant updated, Instant completed) {
        ScenarioRun r = new ScenarioRun();
        r.setId(id);
        r.setScenarioKey(key);
        r.setStatus(status);
        r.setStartedAt(started);
        r.setUpdatedAt(updated);
        r.setCompletedAt(completed);
        return r;
    }

    @Test
    void aRunIsDatedByItsLastStep_notItsStart() {
        ScenarioRun advancedYesterday = run(1, "a", ScenarioRun.Status.IN_PROGRESS,
                NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofDays(1)), null);
        assertThat(PracticeTodayService.lastTouched(advancedYesterday)).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(PracticeTodayService.reentryFor(PracticeTodayService.lastTouched(advancedYesterday), NOW)).isEqualTo("RECENT");
    }

    @Test
    void personalRunsBuildTheSameProgress_soAJustFinishedStoryIsNotRecommendedAgain() {
        Map<String, ScenarioProgressDto> p = PracticeTodayService.progressFrom(List.of(
                run(3, "a", ScenarioRun.Status.COMPLETED, NOW.minus(Duration.ofDays(2)), null, NOW.minus(Duration.ofDays(1))),
                run(2, "b", ScenarioRun.Status.IN_PROGRESS, NOW.minus(Duration.ofDays(3)), null, null),
                run(1, "a", ScenarioRun.Status.COMPLETED, NOW.minus(Duration.ofDays(90)), null, NOW.minus(Duration.ofDays(90)))));
        assertThat(p.get("a").completedCount()).isEqualTo(2);
        assertThat(p.get("a").lastCompletedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(p.get("b").inProgressRunId()).isEqualTo(2L);
        assertThat(PracticeTodayService.recommend(catalog(card("a"), card("b"), card("c")), p, null, NOW).key())
                .isEqualTo("c");
    }

    // -- FR-7: a due check-in outranks the story ----------------------------------

    private static PracticeCheckInDtos.DueDto dueOf(int n) {
        List<PracticeCheckInDtos.CardDto> cards = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            cards.add(new PracticeCheckInDtos.CardDto("checkin-" + i, 1, "skill-" + i, "Prompt " + i, List.of(), true));
        }
        return new PracticeCheckInDtos.DueDto(1, "tornado", "Tornado", cards, null);
    }

    private PracticeTodayService todayWith(PracticeCheckInService checkIns, CatalogDto catalog) {
        ScenarioService scenarios = mock(ScenarioService.class);
        when(scenarios.catalog(anyString(), anyString())).thenReturn(catalog);
        when(scenarios.progress(anyString(), anyString())).thenReturn(new ScenarioDtos.ProgressDto(1, "hh", List.of()));
        ScenarioRunRepo runs = mock(ScenarioRunRepo.class);
        when(runs.findByHouseholdIdOrderByStartedAtDesc(anyString())).thenReturn(List.of());
        GroupRepo groups = mock(GroupRepo.class);
        when(groups.findByGroupId(anyString())).thenReturn(java.util.Optional.empty());
        FamilyPracticeCompletionRepo family = mock(FamilyPracticeCompletionRepo.class);
        when(family.findByHouseholdIdOrderByCompletedAtDesc(anyString())).thenReturn(List.of());
        return new PracticeTodayService(scenarios, null, runs, groups, family, checkIns,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @Test
    void todayCarriesTheRefreshWhenACheckInIsDue() {
        PracticeCheckInService checkIns = mock(PracticeCheckInService.class);
        when(checkIns.dueCards("hh")).thenReturn(dueOf(2));

        ScenarioDtos.TodayDto t = todayWith(checkIns, catalog(card("a"))).today("a@x.com", "hh");

        assertThat(t.refresh()).isEqualTo(new ScenarioDtos.RefreshDto("tornado", "Tornado", "Prompt 0", 2));
        assertThat(t.recommended().key()).as("the story is still offered; the FE ranks the refresh above it").isEqualTo("a");
    }

    @Test
    void noRefreshWhenNothingIsDue() {
        PracticeCheckInService checkIns = mock(PracticeCheckInService.class);
        when(checkIns.dueCards("hh")).thenReturn(dueOf(0));
        assertThat(todayWith(checkIns, catalog(card("a"))).today("a@x.com", "hh").refresh()).isNull();
    }

    @Test
    void noRefreshWhilePracticeWaits_andTheCheckInsAreNotEvenAsked() {
        PracticeCheckInService checkIns = mock(PracticeCheckInService.class);
        when(checkIns.dueCards("hh")).thenReturn(dueOf(3));
        ScenarioDtos.SuppressionDto waiting = new ScenarioDtos.SuppressionDto("PLAN_ACTIVATION", "t", "d", null, Map.of());
        CatalogDto suppressed = new CatalogDto(ScenarioDtos.SCHEMA_VERSION, true, waiting, List.of(), List.of(), null);

        assertThat(todayWith(checkIns, suppressed).today("a@x.com", "hh").refresh()).isNull();
        verify(checkIns, never()).dueCards(any());
    }

    @Test
    void aPersonalScopeHasNoRefresh_stateIsTheHouseholds() {
        PracticeCheckInService checkIns = mock(PracticeCheckInService.class);
        ScenarioService scenarios = mock(ScenarioService.class);
        when(scenarios.catalog(anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(catalog(card("a")));
        ScenarioRunRepo runs = mock(ScenarioRunRepo.class);
        when(runs.findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc(anyString())).thenReturn(List.of());
        PracticeTodayService svc = new PracticeTodayService(scenarios, null, runs, mock(GroupRepo.class),
                mock(FamilyPracticeCompletionRepo.class), checkIns, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        assertThat(svc.today("a@x.com", null).refresh()).isNull();
        verify(checkIns, never()).dueCards(any());
    }
}
