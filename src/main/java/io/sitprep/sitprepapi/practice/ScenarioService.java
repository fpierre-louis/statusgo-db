package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.RunCheck;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeContent.Choice;
import io.sitprep.sitprepapi.practice.PracticeContent.Node;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ChoiceDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CommittedDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.DebriefDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.InProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.NextStepDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.NodeDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.PathStepDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.RunDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioCardDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioDetailDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SourceDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.SuppressionDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.AlertHeadsUpDto;
import io.sitprep.sitprepapi.practice.ScenarioRun.Status;
import io.sitprep.sitprepapi.practice.ScenarioRun.TraceStep;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Practice scenario runs: start-or-resume, commit a choice, complete, read.
 *
 * <p><b>Writes only {@code scenario_run}.</b> Nothing here touches a plan,
 * contact, status, check-in, activation or location — {@code PracticeInvariantTest}
 * holds that structurally. A debrief's next step is a semantic action the FE
 * turns into a link to a real editor; the adult decides there.</p>
 *
 * <p>Gates, in order, on every write: who may see the run, whether Practice
 * should wait ({@link PracticeSuppressionService} — start, commit and
 * complete all wait), and whether the pinned content may still be used
 * ({@link PracticeAvailabilityService}).</p>
 *
 * <p>HTTP: 404 unknown / unpublished scenario or a run that isn't yours;
 * 403 a household you're not in; 409 Practice is waiting, or the step was
 * already answered differently, or the run is finished; 410 content withdrawn.</p>
 */
@Service
public class ScenarioService {

    static final String WAITING = "Practice can wait right now";
    static final String WITHDRAWN = "This practice was taken out of service for review";

    private final PracticeAvailabilityService availability;
    private final PracticeSuppressionService suppression;
    private final ScenarioRunRepo runRepo;
    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;
    private final Clock clock;

    @Autowired
    public ScenarioService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                           ScenarioRunRepo runRepo, GroupRepo groupRepo, HouseholdAccessService access) {
        this(availability, suppression, runRepo, groupRepo, access, Clock.systemUTC());
    }

    ScenarioService(PracticeAvailabilityService availability, PracticeSuppressionService suppression,
                    ScenarioRunRepo runRepo, GroupRepo groupRepo, HouseholdAccessService access, Clock clock) {
        this.availability = availability;
        this.suppression = suppression;
        this.runRepo = runRepo;
        this.groupRepo = groupRepo;
        this.access = access;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CatalogDto catalog(String callerEmail, String householdId) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        SuppressionDto waiting = SuppressionDto.of(suppression.check(hid, email).orElse(null));

        List<ScenarioCardDto> cards = new ArrayList<>();
        for (Entry e : availability.startable(PracticeKind.ADULT_SCENARIO)) {
            Version v = e.version();
            cards.add(new ScenarioCardDto(v.key(), v.version(), v.body().title(), v.body().summary(),
                    v.body().estimatedMinutes(), e.preview()));
        }

        List<InProgressDto> inProgress = new ArrayList<>();
        for (ScenarioRun r : scopeRuns(email, hid)) {
            if (r.getStatus() != Status.IN_PROGRESS) continue;
            RunCheck check = availability.checkRun(r.getScenarioKey(), r.getContentVersion(), r.getContentHash());
            if (!check.canContinue()) continue;
            Version v = check.version();
            String nodeTitle = ScenarioEngine.node(v, r.getLastNodeKey()).map(Node::title).orElse(null);
            inProgress.add(new InProgressDto(r.getId(), r.getScenarioKey(), v.body().title(),
                    stepNumber(r), nodeTitle));
        }
        return new CatalogDto(ScenarioDtos.SCHEMA_VERSION, availability.practiceEnabled(), waiting, cards, inProgress,
                waiting == null ? alertHeadsUp(hid) : null);
    }

    @Transactional(readOnly = true)
    public ScenarioDetailDto detail(String scenarioKey, String callerEmail, String householdId) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        StartCheck start = availability.checkStart(scenarioKey);
        if (!start.allowed()) throw startDenied(start);
        Version v = start.entry().version();
        List<SourceDto> sources = v.body().sources().stream().map(s -> new SourceDto(s.label(), s.url())).toList();
        Long runId = activeRun(email, hid, scenarioKey).map(ScenarioRun::getId).orElse(null);
        SuppressionDto waiting = SuppressionDto.of(suppression.check(hid, email).orElse(null));
        return new ScenarioDetailDto(ScenarioDtos.SCHEMA_VERSION, v.key(), v.version(), v.body().title(),
                v.body().summary(), v.body().objective(), v.body().estimatedMinutes(), sources,
                start.entry().preview(), waiting, runId, waiting == null ? alertHeadsUp(hid) : null);
    }

    /** The alert nudge, only where nothing outranks it. */
    private AlertHeadsUpDto alertHeadsUp(String hid) {
        return AlertHeadsUpDto.of(suppression.headsUp(hid).orElse(null));
    }

    @Transactional(readOnly = true)
    public RunDto get(long runId, String callerEmail) {
        String email = normalize(callerEmail);
        ScenarioRun run = runFor(runId, email);
        return toDto(run, email);
    }

    @Transactional(readOnly = true)
    public ProgressDto progress(String householdId, String callerEmail) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        if (hid == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "householdId is required");
        Map<String, ScenarioProgressDto> byKey = new LinkedHashMap<>();
        for (ScenarioRun r : runRepo.findByHouseholdIdOrderByStartedAtDesc(hid)) {
            ScenarioProgressDto p = byKey.getOrDefault(r.getScenarioKey(),
                    new ScenarioProgressDto(r.getScenarioKey(), 0, null, null));
            int count = p.completedCount() + (r.getStatus() == Status.COMPLETED ? 1 : 0);
            Instant last = p.lastCompletedAt();
            if (r.getCompletedAt() != null && (last == null || r.getCompletedAt().isAfter(last))) last = r.getCompletedAt();
            Long active = p.inProgressRunId() != null ? p.inProgressRunId()
                    : r.getStatus() == Status.IN_PROGRESS ? r.getId() : null;
            byKey.put(r.getScenarioKey(), new ScenarioProgressDto(r.getScenarioKey(), count, last, active));
        }
        return new ProgressDto(ScenarioDtos.SCHEMA_VERSION, hid, List.copyOf(byKey.values()));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Start {@code scenarioKey}, or resume the run already in progress for this
     * household (or, with no household, for this caller). Deliberately not one
     * transaction: the partial unique index (V104) settles a race between two
     * devices, and the loser re-reads the winner's run.
     */
    public RunDto start(String scenarioKey, String callerEmail, String householdId) {
        String email = normalize(callerEmail);
        String hid = householdScope(email, householdId);
        requireCalm(hid, email);

        Optional<ScenarioRun> existing = activeRun(email, hid, scenarioKey);
        if (existing.isPresent()) {
            ScenarioRun run = existing.get();
            RunCheck check = availability.checkRun(run.getScenarioKey(), run.getContentVersion(), run.getContentHash());
            if (check.canContinue()) return toDto(run, email);
            // Its content was withdrawn or no longer matches: close it out honestly and start fresh.
            run.setStatus(Status.ABANDONED);
            run.setAbandonedAt(clock.instant());
            run.setUpdatedAt(clock.instant());
            runRepo.save(run);
        }

        StartCheck start = availability.checkStart(scenarioKey);
        if (!start.allowed()) throw startDenied(start);
        Version v = start.entry().version();

        ScenarioRun run = new ScenarioRun();
        run.setHouseholdId(hid);
        run.setUserEmail(email);
        run.setScenarioKey(v.key());
        run.setContentVersion(v.version());
        run.setContentHash(v.contentHash());
        run.setStatus(Status.IN_PROGRESS);
        Instant now = clock.instant();
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        run.setLastNodeKey(ScenarioEngine.startNode(v));
        try {
            return toDto(runRepo.saveAndFlush(run), email);
        } catch (DataIntegrityViolationException raced) {
            return activeRun(email, hid, scenarioKey)
                    .map(r -> toDto(r, email))
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * Commit a choice (R4 beat 2 — the client's Continue, never the first tap).
     * Re-sending the commit that was just applied returns the current state, so
     * a retried request never forks or fails the run.
     */
    @Transactional
    public RunDto commit(long runId, String callerEmail, String nodeKey, String choiceKey) {
        String email = normalize(callerEmail);
        ScenarioRun run = runFor(runId, email);
        if (run.getStatus() != Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This practice is already finished");
        }
        requireCalm(run.getHouseholdId(), email);
        Version v = continuable(run);

        List<TraceStep> trace = run.getDecisionTrace() == null ? new ArrayList<>() : new ArrayList<>(run.getDecisionTrace());
        if (!trace.isEmpty()) {
            TraceStep last = trace.get(trace.size() - 1);
            if (last.nodeKey().equals(nodeKey) && last.choiceKey().equals(choiceKey)) {
                return toDto(run, email); // the same commit again (retry / double tap)
            }
        }

        ScenarioEngine.Commit c;
        try {
            c = ScenarioEngine.commit(v, run.getLastNodeKey(), nodeKey, choiceKey);
        } catch (ScenarioEngine.EngineException e) {
            HttpStatus status = e.refusal() == ScenarioEngine.Refusal.UNKNOWN_CHOICE
                    ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, e.getMessage());
        }
        trace.add(new TraceStep(nodeKey, choiceKey, clock.instant().toString()));
        run.setDecisionTrace(trace);
        run.setLastNodeKey(c.nextKey());
        run.setUpdatedAt(clock.instant());
        return toDto(runRepo.save(run), email);
    }

    /** Close a run that reached an outcome and record its debrief. Idempotent. */
    @Transactional
    public RunDto complete(long runId, String callerEmail) {
        String email = normalize(callerEmail);
        ScenarioRun run = runFor(runId, email);
        if (run.getStatus() == Status.COMPLETED) return toDto(run, email);
        if (run.getStatus() != Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This practice was closed");
        }
        requireCalm(run.getHouseholdId(), email);
        Version v = continuable(run);
        if (ScenarioEngine.outcome(v, run.getLastNodeKey()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This practice isn't finished yet");
        }
        ScenarioEngine.Debrief d = ScenarioEngine.debrief(v, run.getDecisionTrace(), run.getLastNodeKey());
        Instant now = clock.instant();
        run.setStatus(Status.COMPLETED);
        run.setCompletedAt(now);
        run.setUpdatedAt(now);
        run.setOutcomeKey(d.outcome().key());
        run.setDebriefTags(new ArrayList<>(d.tags()));
        run.setSuggestedActionKey(d.nextStep().action() == null ? null : d.nextStep().action().name());
        return toDto(runRepo.save(run), email);
    }

    // ------------------------------------------------------------------
    // Shaping
    // ------------------------------------------------------------------

    RunDto toDto(ScenarioRun run, String email) {
        RunCheck check = availability.checkRun(run.getScenarioKey(), run.getContentVersion(), run.getContentHash());
        Version v = check.version();
        boolean showContent = check.canContinue();
        // Every run says whether Practice waits, finished ones too: the debrief
        // steps aside for a live response like any other phase (spec P10), and
        // Practice again's 409 relies on the reloaded run saying why.
        SuppressionDto waiting = SuppressionDto.of(suppression.check(run.getHouseholdId(), email).orElse(null));

        NodeDto node = null;
        CommittedDto lastCommit = null;
        DebriefDto debrief = null;
        boolean atOutcome = false;
        String title = null;

        if (showContent) {
            title = v.body().title();
            if (run.getStatus() == Status.IN_PROGRESS) {
                atOutcome = ScenarioEngine.outcome(v, run.getLastNodeKey()).isPresent();
                node = ScenarioEngine.node(v, run.getLastNodeKey()).map(ScenarioService::nodeDto).orElse(null);
            }
            lastCommit = lastCommit(v, run.getDecisionTrace());
            if (run.getStatus() == Status.COMPLETED && run.getOutcomeKey() != null) {
                debrief = debriefDto(v, run);
            }
        }
        return new RunDto(ScenarioDtos.SCHEMA_VERSION, run.getId(), run.getScenarioKey(), run.getContentVersion(),
                title, run.getStatus().name(), check.status().name(),
                v != null && v.publishState() != PublishState.PUBLISHED && v.publishState() != PublishState.RETIRED,
                stepNumber(run), node, lastCommit, atOutcome, debrief, waiting,
                run.getStartedAt(), run.getCompletedAt());
    }

    private static NodeDto nodeDto(Node n) {
        List<ChoiceDto> choices = n.choices().stream().map(c -> new ChoiceDto(c.key(), c.label())).toList();
        return new NodeDto(n.key(), n.title(), n.body(), n.prompt(), choices);
    }

    private static CommittedDto lastCommit(Version v, List<TraceStep> trace) {
        if (trace == null || trace.isEmpty()) return null;
        TraceStep last = trace.get(trace.size() - 1);
        Optional<Node> node = ScenarioEngine.node(v, last.nodeKey());
        Optional<Choice> choice = node.flatMap(n -> ScenarioEngine.choice(n, last.choiceKey()));
        if (choice.isEmpty()) return null;
        return new CommittedDto(last.nodeKey(), node.get().title(), last.choiceKey(),
                choice.get().label(), choice.get().consequence(), choice.get().feedback());
    }

    private static DebriefDto debriefDto(Version v, ScenarioRun run) {
        ScenarioEngine.Debrief d = ScenarioEngine.debrief(v, run.getDecisionTrace(), run.getOutcomeKey());
        PracticeActions.StepCopy copy = PracticeActions.describe(d.nextStep().action());
        NextStepDto next = copy == null ? null : new NextStepDto(d.nextStep().action(), d.nextStep().params(),
                copy.prompt(), copy.label(), copy.note());
        List<PathStepDto> path = new ArrayList<>();
        for (TraceStep step : run.getDecisionTrace()) {
            ScenarioEngine.node(v, step.nodeKey()).ifPresent(n ->
                    ScenarioEngine.choice(n, step.choiceKey()).ifPresent(c ->
                            path.add(new PathStepDto(n.prompt(), c.label()))));
        }
        return new DebriefDto(d.outcome().title(), d.outcome().body(), d.strongChoices(),
                d.worthPracticing(), next, path);
    }

    private static int stepNumber(ScenarioRun run) {
        return (run.getDecisionTrace() == null ? 0 : run.getDecisionTrace().size()) + 1;
    }

    // ------------------------------------------------------------------
    // Gates
    // ------------------------------------------------------------------

    /** Validates and authorizes an optional household scope; returns it, or null for a personal scope. */
    private String householdScope(String email, String householdId) {
        if (householdId == null || householdId.isBlank()) return null;
        groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
        access.requireCanReadHousehold(email, householdId);
        return householdId;
    }

    /** A household run is any member's to see and continue; a personal run is only its owner's. */
    private ScenarioRun runFor(long runId, String email) {
        ScenarioRun run = runRepo.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Practice run not found"));
        if (run.getHouseholdId() != null) {
            access.requireCanReadHousehold(email, run.getHouseholdId());
        } else if (!email.equals(run.getUserEmail())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Practice run not found");
        }
        return run;
    }

    private Optional<ScenarioRun> activeRun(String email, String hid, String scenarioKey) {
        return hid != null
                ? runRepo.findFirstByHouseholdIdAndScenarioKeyAndStatus(hid, scenarioKey, Status.IN_PROGRESS)
                : runRepo.findFirstByUserEmailAndScenarioKeyAndStatusAndHouseholdIdIsNull(email, scenarioKey, Status.IN_PROGRESS);
    }

    private List<ScenarioRun> scopeRuns(String email, String hid) {
        return hid != null
                ? runRepo.findByHouseholdIdOrderByStartedAtDesc(hid)
                : runRepo.findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc(email);
    }

    private void requireCalm(String hid, String email) {
        if (suppression.check(hid, email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, WAITING);
        }
    }

    private Version continuable(ScenarioRun run) {
        RunCheck check = availability.checkRun(run.getScenarioKey(), run.getContentVersion(), run.getContentHash());
        if (!check.canContinue()) throw new ResponseStatusException(HttpStatus.GONE, WITHDRAWN);
        return check.version();
    }

    private static ResponseStatusException startDenied(StartCheck start) {
        return switch (start.denial()) {
            case DISABLED, PRACTICE_OFF -> new ResponseStatusException(HttpStatus.GONE, WITHDRAWN);
            case NOT_FOUND, NOT_PUBLISHED -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario not found");
        };
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
