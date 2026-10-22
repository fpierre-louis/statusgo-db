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
import java.util.List;
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
    private final PracticeCheckInService checkIns;
    private final Clock clock;

    @Autowired
    public PracticeTodayService(ScenarioService scenarios, ScenarioRelevance relevance, ScenarioRunRepo runRepo,
                                GroupRepo groupRepo, FamilyPracticeCompletionRepo familyRepo,
                                PracticeCheckInService checkIns) {
        this(scenarios, relevance, runRepo, groupRepo, familyRepo, checkIns, Clock.systemUTC());
    }

    PracticeTodayService(ScenarioService scenarios, ScenarioRelevance relevance, ScenarioRunRepo runRepo,
                         GroupRepo groupRepo, FamilyPracticeCompletionRepo familyRepo, Clock clock) {
        this(scenarios, relevance, runRepo, groupRepo, familyRepo, null, clock);
    }

    PracticeTodayService(ScenarioService scenarios, ScenarioRelevance relevance, ScenarioRunRepo runRepo,
                         GroupRepo groupRepo, FamilyPracticeCompletionRepo familyRepo,
                         PracticeCheckInService checkIns, Clock clock) {
        this.checkIns = checkIns;
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

        // Household scope: the household's runs. Personal scope (no household): the
        // caller's own runs, read the same way, so "recently done" holds for them too.
        Map<String, ScenarioProgressDto> progress = new HashMap<>();
        List<ScenarioRun> runs;
        if (hid != null) {
            ProgressDto p = scenarios.progress(hid, callerEmail);
            for (ScenarioProgressDto s : p.scenarios()) progress.put(s.scenarioKey(), s);
            runs = runRepo.findByHouseholdIdOrderByStartedAtDesc(hid);
        } else {
            runs = runRepo.findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc(normalize(callerEmail));
            progress.putAll(progressFrom(runs));
        }

        InProgressDto continueRun = catalog.inProgress().isEmpty() ? null : catalog.inProgress().get(0);
        ScenarioCardDto recommended = recommend(catalog, progress, continueRun, now);
        String reentry = reentry(hid, runs, now);
        return new TodayDto(ScenarioDtos.SCHEMA_VERSION, reentry, recommended, continueRun,
                relevance == null ? java.util.List.of() : relevance.localHazards(hid), catalog.suppression(),
                refresh(hid, catalog.suppression() != null));
    }

    /**
     * A due check-in (FR-7). Household scope only (state is the household's), and never while
     * Practice waits: the catalog already said so, and a waiting household gets no refresh.
     */
    private ScenarioDtos.RefreshDto refresh(String hid, boolean waiting) {
        if (checkIns == null || hid == null || waiting) return null;
        PracticeCheckInDtos.DueDto due = checkIns.dueCards(hid);
        if (due.cards().isEmpty()) return null;
        return new ScenarioDtos.RefreshDto(due.hazard(), due.hazardLabel(), due.cards().get(0).prompt(), due.cards().size());
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

    /** Per scenario: completed count, newest completion, a run in progress — from runs newest first. */
    static Map<String, ScenarioProgressDto> progressFrom(List<ScenarioRun> runs) {
        Map<String, ScenarioProgressDto> out = new HashMap<>();
        for (ScenarioRun r : runs) {
            ScenarioProgressDto p = out.getOrDefault(r.getScenarioKey(), new ScenarioProgressDto(r.getScenarioKey(), 0, null, null));
            boolean done = r.getStatus() == ScenarioRun.Status.COMPLETED;
            Instant last = p.lastCompletedAt();
            if (done && r.getCompletedAt() != null && (last == null || r.getCompletedAt().isAfter(last))) last = r.getCompletedAt();
            Long active = p.inProgressRunId() != null ? p.inProgressRunId()
                    : r.getStatus() == ScenarioRun.Status.IN_PROGRESS ? r.getId() : null;
            out.put(r.getScenarioKey(), new ScenarioProgressDto(r.getScenarioKey(), p.completedCount() + (done ? 1 : 0), last, active));
        }
        return out;
    }

    /** When a run was last practised: its last step or completion, not when it started. */
    static Instant lastTouched(ScenarioRun r) {
        return later(later(r.getStartedAt(), r.getUpdatedAt()), r.getCompletedAt());
    }

    String reentry(String hid, List<ScenarioRun> runs, Instant now) {
        Instant last = null;
        for (ScenarioRun r : runs) last = later(last, lastTouched(r));
        if (hid == null) return reentryFor(last, now);
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

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Instant later(Instant a, Instant b) {
        if (b == null) return a;
        return a == null || b.isAfter(a) ? b : a;
    }
}
