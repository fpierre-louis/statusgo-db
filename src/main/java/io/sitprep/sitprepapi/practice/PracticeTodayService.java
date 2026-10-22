package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.InProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioCardDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.TodayDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Today on the Practice home (Practice redesign FR-5; handoff TODAY_FLOW),
 * composed from what the server already knows. No recommendation engine yet
 * (BE-05/06): the one recommendation is a story — the first scenario in the
 * risk-ordered catalog that is not in progress and was not finished in the
 * last {@link #RECENTLY_DONE} — which TODAY_FLOW allows ("a story").
 *
 * <p>The re-entry line is from the household's latest practice of any kind (a
 * scenario run, a drill mark, a family activity): none → FIRST, {@link #GAP}
 * or more ago → GAP, else RECENT. Never a count of anything missed.</p>
 */
@Service
public class PracticeTodayService {

    static final Duration RECENTLY_DONE = Duration.ofDays(30);
    static final Duration GAP = Duration.ofDays(14);

    private final ScenarioService scenarios;
    private final ScenarioRelevance relevance;
    private final ScenarioRunRepo runRepo;
    private final GroupRepo groupRepo;
    private final FamilyPracticeCompletionRepo familyRepo;
    private final Clock clock;

    @Autowired
    public PracticeTodayService(ScenarioService scenarios, ScenarioRelevance relevance, ScenarioRunRepo runRepo,
                                GroupRepo groupRepo, FamilyPracticeCompletionRepo familyRepo) {
        this(scenarios, relevance, runRepo, groupRepo, familyRepo, Clock.systemUTC());
    }

    PracticeTodayService(ScenarioService scenarios, ScenarioRelevance relevance, ScenarioRunRepo runRepo,
                         GroupRepo groupRepo, FamilyPracticeCompletionRepo familyRepo, Clock clock) {
        this.scenarios = scenarios;
        this.relevance = relevance;
        this.runRepo = runRepo;
        this.groupRepo = groupRepo;
        this.familyRepo = familyRepo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TodayDto today(String callerEmail, String householdId) {
        // The catalog authorizes the household scope (404 / 403) before anything else is read.
        CatalogDto catalog = scenarios.catalog(callerEmail, householdId);
        String hid = householdId == null || householdId.isBlank() ? null : householdId;
        Instant now = clock.instant();

        Map<String, ScenarioProgressDto> progress = new HashMap<>();
        if (hid != null) {
            ProgressDto p = scenarios.progress(hid, callerEmail);
            for (ScenarioProgressDto s : p.scenarios()) progress.put(s.scenarioKey(), s);
        }

        InProgressDto continueRun = catalog.inProgress().isEmpty() ? null : catalog.inProgress().get(0);
        ScenarioCardDto recommended = recommend(catalog, progress, continueRun, now);
        String reentry = reentry(hid, now);
        return new TodayDto(ScenarioDtos.SCHEMA_VERSION, reentry, recommended, continueRun,
                relevance == null ? java.util.List.of() : relevance.localHazards(hid), catalog.suppression());
    }

    /** The first card in catalog (risk) order that isn't being continued and wasn't just done. */
    static ScenarioCardDto recommend(CatalogDto catalog, Map<String, ScenarioProgressDto> progress,
                                     InProgressDto continueRun, Instant now) {
        for (ScenarioCardDto card : catalog.scenarios()) {
            if (continueRun != null && card.key().equals(continueRun.scenarioKey())) continue;
            ScenarioProgressDto p = progress.get(card.key());
            if (p != null && p.inProgressRunId() != null) continue;
            if (p != null && p.lastCompletedAt() != null && p.lastCompletedAt().isAfter(now.minus(RECENTLY_DONE))) continue;
            return card;
        }
        return null;
    }

    String reentry(String hid, Instant now) {
        if (hid == null) return "FIRST";
        Instant last = null;
        for (ScenarioRun r : runRepo.findByHouseholdIdOrderByStartedAtDesc(hid)) {
            last = later(last, r.getCompletedAt() != null ? r.getCompletedAt() : r.getStartedAt());
        }
        Group g = groupRepo.findByGroupId(hid).orElse(null);
        if (g != null && g.getDrillLog() != null) {
            for (var c : g.getDrillLog().values()) if (c != null) last = later(last, c.getCompletedAt());
        }
        for (FamilyPracticeCompletion c : familyRepo.findByHouseholdIdOrderByCompletedAtDesc(hid)) {
            last = later(last, c.getCompletedAt());
            break; // newest first
        }
        return reentryFor(last, now);
    }

    static String reentryFor(Instant lastPractice, Instant now) {
        if (lastPractice == null) return "FIRST";
        return Duration.between(lastPractice, now).compareTo(GAP) >= 0 ? "GAP" : "RECENT";
    }

    private static Instant later(Instant a, Instant b) {
        if (b == null) return a;
        return a == null || b.isAfter(a) ? b : a;
    }
}
