package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.PracticeSuppressionService.Reason;
import io.sitprep.sitprepapi.practice.PracticeSuppressionService.Suppression;
import io.sitprep.sitprepapi.practice.ScenarioDtos.RunDto;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Start / resume / commit / complete over an in-memory run table, with the
 * real catalog + availability rules. The fixture scenario is
 * start → (text | call) → second → (plan → reconnected | improvise → partial).
 */
class ScenarioServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");
    private static final String HH = "hh-1";
    private static final String ME = "adult@example.com";
    private static final String OUTSIDER = "stranger@example.com";
    private static final String KEY = "reconnect";

    private final List<ScenarioRun> table = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private PracticeContentControlRepo controls;
    private PracticeSuppressionService suppression;
    private HouseholdAccessService access;
    private ScenarioService service;
    private Version live;

    @BeforeEach
    void setUp() {
        live = version(approve(scenario(KEY, 1), PublishState.PUBLISHED));
        PracticeCatalog catalog = PracticeCatalog.of(List.of(live));
        controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PracticeAvailabilityService availability = new PracticeAvailabilityService(catalog, controls, true, false, clock);

        suppression = mock(PracticeSuppressionService.class);
        when(suppression.check(any(), any())).thenReturn(Optional.empty());

        GroupRepo groups = mock(GroupRepo.class);
        Group household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        when(groups.findByGroupId(HH)).thenReturn(Optional.of(household));
        access = mock(HouseholdAccessService.class);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(access).requireCanReadHousehold(eq(OUTSIDER), anyString());

        service = new ScenarioService(availability, suppression, inMemoryRepo(), groups, access, clock);
    }

    private ScenarioRunRepo inMemoryRepo() {
        ScenarioRunRepo repo = mock(ScenarioRunRepo.class);
        org.mockito.stubbing.Answer<ScenarioRun> save = inv -> {
            ScenarioRun r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(ids.getAndIncrement());
                table.add(r);
            }
            return r;
        };
        when(repo.save(any())).thenAnswer(save);
        when(repo.saveAndFlush(any())).thenAnswer(save);
        when(repo.findById(any())).thenAnswer(inv -> table.stream()
                .filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findFirstByHouseholdIdAndScenarioKeyAndStatus(any(), any(), any())).thenAnswer(inv -> table.stream()
                .filter(r -> inv.getArgument(0).equals(r.getHouseholdId())
                        && inv.getArgument(1).equals(r.getScenarioKey()) && r.getStatus() == inv.getArgument(2))
                .findFirst());
        when(repo.findFirstByUserEmailAndScenarioKeyAndStatusAndHouseholdIdIsNull(any(), any(), any()))
                .thenAnswer(inv -> table.stream()
                        .filter(r -> r.getHouseholdId() == null && inv.getArgument(0).equals(r.getUserEmail())
                                && inv.getArgument(1).equals(r.getScenarioKey()) && r.getStatus() == inv.getArgument(2))
                        .findFirst());
        when(repo.findByHouseholdIdOrderByStartedAtDesc(any())).thenAnswer(inv -> table.stream()
                .filter(r -> inv.getArgument(0).equals(r.getHouseholdId())).toList());
        when(repo.findByUserEmailAndHouseholdIdIsNullOrderByStartedAtDesc(any())).thenAnswer(inv -> table.stream()
                .filter(r -> r.getHouseholdId() == null && inv.getArgument(0).equals(r.getUserEmail())).toList());
        return repo;
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    private void disable() {
        PracticeContentControl c = new PracticeContentControl();
        c.setContentKey(KEY);
        c.setDisabledAt(NOW);
        when(controls.findById(KEY)).thenReturn(Optional.of(c));
        when(controls.findAll()).thenReturn(List.of(c));
    }

    // -- start / resume -------------------------------------------------

    @Test
    void startPinsTheVersionAndOpensAtTheStartNode() {
        RunDto run = service.start(KEY, ME, HH);
        assertThat(run.status()).isEqualTo("IN_PROGRESS");
        assertThat(run.stepNumber()).isEqualTo(1);
        assertThat(run.node().key()).isEqualTo("start");
        assertThat(run.node().choices()).extracting(ScenarioDtos.ChoiceDto::key).containsExactly("text", "call");
        assertThat(run.lastCommit()).isNull();
        ScenarioRun row = table.get(0);
        assertThat(row.getContentHash()).isEqualTo(live.contentHash());
        assertThat(row.getContentVersion()).isEqualTo(1);
        assertThat(row.getHouseholdId()).isEqualTo(HH);
    }

    @Test
    void startingAgainResumesTheSameRun() {
        long first = service.start(KEY, ME, HH).runId();
        service.commit(first, ME, "start", "text");
        RunDto again = service.start(KEY, ME, HH);
        assertThat(again.runId()).isEqualTo(first);
        assertThat(again.node().key()).isEqualTo("second");
        assertThat(table).hasSize(1);
    }

    @Test
    void refreshResumesWhereTheRunStopped() {
        long id = service.start(KEY, ME, HH).runId();
        service.commit(id, ME, "start", "call");
        RunDto reloaded = service.get(id, ME);
        assertThat(reloaded.node().key()).isEqualTo("second");
        assertThat(reloaded.stepNumber()).isEqualTo(2);
        assertThat(reloaded.lastCommit().choiceKey()).isEqualTo("call");
    }

    // -- commit (R4: Continue commits) ----------------------------------

    @Test
    void commitReturnsTheReflectionAndTheNextNode() {
        long id = service.start(KEY, ME, HH).runId();
        RunDto after = service.commit(id, ME, "start", "text");
        assertThat(after.lastCommit().feedback()).isEqualTo("Texts often get through when calls do not.");
        assertThat(after.lastCommit().choiceLabel()).isEqualTo("Send a short text");
        assertThat(after.node().key()).isEqualTo("second");
        assertThat(table.get(0).getDecisionTrace()).hasSize(1);
    }

    @Test
    void theSameCommitTwiceIsHarmless() {
        long id = service.start(KEY, ME, HH).runId();
        service.commit(id, ME, "start", "text");
        RunDto replay = service.commit(id, ME, "start", "text");
        assertThat(replay.node().key()).isEqualTo("second");
        assertThat(table.get(0).getDecisionTrace()).hasSize(1);
    }

    @Test
    void aDifferentAnswerToAnAnsweredStepIs409AndUnknownChoiceIs400() {
        long id = service.start(KEY, ME, HH).runId();
        service.commit(id, ME, "start", "text");
        assertThat(status(() -> service.commit(id, ME, "start", "call"))).isEqualTo(409);
        assertThat(status(() -> service.commit(id, ME, "second", "teleport"))).isEqualTo(400);
        assertThat(table.get(0).getDecisionTrace()).hasSize(1);
    }

    @Test
    void reflectionsAreNeverSentAheadOfTheCommit() {
        RunDto run = service.start(KEY, ME, HH);
        // NodeDto carries no feedback field at all; ChoiceDto is {key, label}.
        assertThat(ScenarioDtos.ChoiceDto.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("key", "label");
        assertThat(run.lastCommit()).isNull();
    }

    // -- complete -------------------------------------------------------

    @Test
    void completeRecordsTheDebriefOnceAndOnlyAtAnOutcome() {
        long id = service.start(KEY, ME, HH).runId();
        assertThat(status(() -> service.complete(id, ME))).isEqualTo(409); // not at an outcome yet
        service.commit(id, ME, "start", "call");
        RunDto atEnd = service.commit(id, ME, "second", "plan");
        assertThat(atEnd.atOutcome()).isTrue();
        assertThat(atEnd.node()).isNull();

        RunDto done = service.complete(id, ME);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.debrief().outcomeTitle()).isEqualTo("Everyone reconnected");
        assertThat(done.debrief().strongChoices()).isEmpty();
        assertThat(done.debrief().worthPracticing()).containsExactly("Worth checking your out-of-area contact is current.");
        assertThat(done.debrief().nextStep().action()).isEqualTo(ReadinessAction.OPEN_EMERGENCY_CONTACTS);
        assertThat(done.debrief().nextStep().note()).isEqualTo("Opens your real contacts. Nothing changes unless you save it.");
        assertThat(done.debrief().path()).hasSize(2);

        ScenarioRun row = table.get(0);
        assertThat(row.getOutcomeKey()).isEqualTo("reconnected");
        assertThat(row.getDebriefTags()).containsExactly("needs_contact_review");
        assertThat(row.getSuggestedActionKey()).isEqualTo("OPEN_EMERGENCY_CONTACTS");
        assertThat(row.getCompletedAt()).isEqualTo(NOW);

        assertThat(service.complete(id, ME).status()).isEqualTo("COMPLETED"); // idempotent
        assertThat(status(() -> service.commit(id, ME, "second", "plan"))).isEqualTo(409);
    }

    @Test
    void practiceAgainStartsAFreshRunAfterCompletion() {
        long first = service.start(KEY, ME, HH).runId();
        service.commit(first, ME, "start", "text");
        service.commit(first, ME, "second", "plan");
        service.complete(first, ME);
        assertThat(service.start(KEY, ME, HH).runId()).isNotEqualTo(first);
    }

    // -- suppression ----------------------------------------------------

    @Test
    void activeResponseBlocksStartCommitAndComplete() {
        long id = service.start(KEY, ME, HH).runId();
        service.commit(id, ME, "start", "text");
        service.commit(id, ME, "second", "plan");
        when(suppression.check(any(), any())).thenReturn(Optional.of(new Suppression(Reason.CHECK_IN,
                "Your household is checking in", PracticeSuppressionService.WAIT, ReadinessAction.OPEN_CHECK_IN, Map.of())));

        assertThat(status(() -> service.start(KEY, ME, HH))).isEqualTo(409);
        assertThat(status(() -> service.complete(id, ME))).isEqualTo(409);
        RunDto paused = service.get(id, ME);
        assertThat(paused.suppression().reason()).isEqualTo("CHECK_IN");
        assertThat(paused.suppression().action()).isEqualTo(ReadinessAction.OPEN_CHECK_IN);
        assertThat(service.catalog(ME, HH).suppression().title()).isEqualTo("Your household is checking in");
    }

    // -- kill switch / content drift -----------------------------------

    @Test
    void withdrawnContentStopsTheRunAndShowsNothing() {
        long id = service.start(KEY, ME, HH).runId();
        disable();
        RunDto run = service.get(id, ME);
        assertThat(run.contentStatus()).isEqualTo("DISABLED");
        assertThat(run.node()).isNull();
        assertThat(run.title()).isNull();
        assertThat(status(() -> service.commit(id, ME, "start", "text"))).isEqualTo(410);
        // Starting again closes the stranded run and refuses a new one.
        assertThat(status(() -> service.start(KEY, ME, HH))).isEqualTo(410);
        assertThat(table.get(0).getStatus()).isEqualTo(ScenarioRun.Status.ABANDONED);
        assertThat(service.catalog(ME, HH).scenarios()).isEmpty();
    }

    @Test
    void aRunWhosePinnedHashMovedShowsNothing() {
        long id = service.start(KEY, ME, HH).runId();
        table.get(0).setContentHash("sha256:not-what-shipped");
        assertThat(service.get(id, ME).contentStatus()).isEqualTo("MISSING");
        assertThat(service.get(id, ME).node()).isNull();
    }

    @Test
    void unknownScenarioIs404() {
        assertThat(status(() -> service.start("nope", ME, HH))).isEqualTo(404);
        assertThat(status(() -> service.detail("nope", ME, HH))).isEqualTo(404);
    }

    // -- access ---------------------------------------------------------

    @Test
    void outsidersGet403OnHouseholdRuns() {
        long id = service.start(KEY, ME, HH).runId();
        assertThat(status(() -> service.get(id, OUTSIDER))).isEqualTo(403);
        assertThat(status(() -> service.commit(id, OUTSIDER, "start", "text"))).isEqualTo(403);
        assertThat(status(() -> service.start(KEY, OUTSIDER, HH))).isEqualTo(403);
        assertThat(status(() -> service.progress(HH, OUTSIDER))).isEqualTo(403);
        assertThat(table.get(0).getDecisionTrace()).isEmpty();
    }

    @Test
    void aPersonalRunIsOnlyItsOwners() {
        long id = service.start(KEY, ME, null).runId();
        assertThat(table.get(0).getHouseholdId()).isNull();
        assertThat(status(() -> service.get(id, OUTSIDER))).isEqualTo(404);
        assertThat(service.get(id, ME).runId()).isEqualTo(id);
    }

    @Test
    void unknownHouseholdIs404() {
        assertThat(status(() -> service.start(KEY, ME, "hh-ghost"))).isEqualTo(404);
    }

    // -- catalog + progress --------------------------------------------

    @Test
    void catalogListsStartableScenariosAndTheRunToContinue() {
        long id = service.start(KEY, ME, HH).runId();
        service.commit(id, ME, "start", "text");
        var catalog = service.catalog(ME, HH);
        assertThat(catalog.scenarios()).singleElement().satisfies(s -> {
            assertThat(s.key()).isEqualTo(KEY);
            assertThat(s.preview()).isFalse();
        });
        assertThat(catalog.inProgress()).singleElement().satisfies(p -> {
            assertThat(p.runId()).isEqualTo(id);
            assertThat(p.stepNumber()).isEqualTo(2);
        });
        assertThat(catalog.suppression()).isNull();
    }

    @Test
    void progressIsDerivedFromRuns() {
        long first = service.start(KEY, ME, HH).runId();
        service.commit(first, ME, "start", "text");
        service.commit(first, ME, "second", "plan");
        service.complete(first, ME);
        long second = service.start(KEY, ME, HH).runId();
        var p = service.progress(HH, ME).scenarios();
        assertThat(p).singleElement().satisfies(s -> {
            assertThat(s.completedCount()).isEqualTo(1);
            assertThat(s.lastCompletedAt()).isEqualTo(NOW);
            assertThat(s.inProgressRunId()).isEqualTo(second);
        });
    }
}
