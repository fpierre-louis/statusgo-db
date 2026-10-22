package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.AnswerDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.CardDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.DueDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.SkillStateDto;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Practice redesign FR-7: due selection, answering and scheduling, confidence,
 * suppression and the server skill state, against real repositories and the
 * shipped pilot content (served as the local preview would).
 */
@DataJpaTest
@ActiveProfiles("test")
class PracticeCheckInServiceTest {

    private static final PracticeCatalog SHIPPED = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");
    private static final String HH = "hh-ci";
    private static final String USER = "member@x.com";
    private static final String KNOW = "tornado-know-where-to-go";
    private static final String PROTECT = "tornado-protect-yourselves";
    private static final String AFTER = "tornado-after-the-warning";
    private static final String QUAKE = "earthquake-protect-in-place";

    @Autowired GroupRepo groups;
    @Autowired ScenarioRunRepo runs;
    @Autowired PracticeSkillStateRepo stateRepo;
    @Autowired PracticeCheckInAnswerRepo answerRepo;

    private final MutableClock clock = new MutableClock(T0);
    private PracticeAvailabilityService availability;
    private PracticeSuppressionService suppression;
    private HouseholdAccessService access;
    private PracticeCheckInService service;
    private Group household;

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        availability = mock(PracticeAvailabilityService.class);
        for (PracticeKind kind : List.of(PracticeKind.SKILL, PracticeKind.CHECKIN)) {
            when(availability.startable(kind)).thenReturn(SHIPPED.keys(kind).stream()
                    .map(k -> new Entry(SHIPPED.versions(k).get(0), true)).toList());
        }
        for (String k : SHIPPED.all().stream().map(PracticeContent.Version::key).distinct().toList()) {
            when(availability.checkStart(k)).thenReturn(new StartCheck(new Entry(SHIPPED.versions(k).get(0), true), null));
        }
        suppression = mock(PracticeSuppressionService.class);
        when(suppression.check(anyString(), anyString())).thenReturn(Optional.empty());
        access = mock(HouseholdAccessService.class);

        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setGroupName("CI");
        groups.saveAndFlush(household);
        build();
    }

    private void build() {
        PracticeSkillStateService skillState = new PracticeSkillStateService(availability, stateRepo, runs, groups, access, clock);
        service = new PracticeCheckInService(availability, suppression, skillState, stateRepo, answerRepo, access, clock);
    }

    // -- fixtures ---------------------------------------------------------------

    private void completedRun(String scenarioKey, Instant at) {
        ScenarioRun r = new ScenarioRun();
        r.setHouseholdId(HH);
        r.setUserEmail(USER);
        r.setScenarioKey(scenarioKey);
        r.setContentVersion(1);
        r.setContentHash("sha256:x");
        r.setStatus(ScenarioRun.Status.COMPLETED);
        r.setStartedAt(at);
        r.setUpdatedAt(at);
        r.setCompletedAt(at);
        runs.saveAndFlush(r);
    }

    private void drillMark(String drill, Instant at) {
        household.getDrillLog().put(drill, new DrillCompletion(at, null, "u1"));
        groups.saveAndFlush(household);
    }

    private PracticeSkillState state(String skill, int interval, Instant dueAt) {
        PracticeSkillState s = new PracticeSkillState();
        s.setHouseholdId(HH);
        s.setSkillKey(skill);
        s.setLastSeenAt(dueAt.minus(Duration.ofDays(interval)));
        s.setIntervalDays(interval);
        s.setPriorIntervalDays(interval);
        s.setNextDueAt(dueAt);
        s.setLastResult("CORRECT");
        s.setUpdatedAt(T0);
        return stateRepo.saveAndFlush(s);
    }

    private static String ckey(String skill) { return "checkin-" + skill; }

    private String correctChoice(String skill) {
        return SHIPPED.versions(ckey(skill)).get(0).body().checkin().choices().stream()
                .filter(c -> Boolean.TRUE.equals(c.correct())).findFirst().orElseThrow().key();
    }

    private String wrongChoice(String skill) {
        return SHIPPED.versions(ckey(skill)).get(0).body().checkin().choices().stream()
                .filter(c -> !Boolean.TRUE.equals(c.correct())).findFirst().orElseThrow().key();
    }

    private DueDto due() { return service.due(USER, HH); }

    // -- due selection ----------------------------------------------------------

    @Test
    void noExposureMeansNothingIsDue() {
        DueDto d = due();
        assertThat(d.cards()).isEmpty();
        assertThat(d.hazard()).isNull();
        assertThat(d.suppression()).isNull();
    }

    @Test
    void firstDueIsTwoDaysAfterExposureAndWritesNothing() {
        completedRun("tornado-warning-shelter-decision", T0);

        clock.now = T0.plus(Duration.ofDays(1));
        assertThat(due().cards()).as("one day after: not yet").isEmpty();

        clock.now = T0.plus(Duration.ofDays(2));
        DueDto d = due();
        assertThat(d.hazard()).isEqualTo("tornado");
        assertThat(d.hazardLabel()).isEqualTo("Tornado");
        assertThat(d.cards()).extracting(CardDto::skillKey).containsExactlyInAnyOrder(KNOW, PROTECT, AFTER);
        assertThat(stateRepo.count()).as("reading a first-due card writes no state").isZero();
        assertThat(answerRepo.count()).isZero();
    }

    @Test
    void aDrillMarkIsExposureToo() {
        drillMark("earthquake-drop-cover-relay", T0);
        clock.now = T0.plus(Duration.ofDays(3));
        assertThat(due().cards()).extracting(CardDto::skillKey).containsExactly(QUAKE);
    }

    @Test
    void oneHazardOnly_theHazardOfTheMostOverdueSkill() {
        completedRun("tornado-warning-shelter-decision", T0);
        completedRun("earthquake-first-five-minutes", T0.minus(Duration.ofDays(3)));
        clock.now = T0.plus(Duration.ofDays(2));

        DueDto d = due();
        assertThat(d.hazard()).as("earthquake exposure is older, so it is the most overdue").isEqualTo("earthquake");
        assertThat(d.cards()).extracting(CardDto::skillKey).containsExactly(QUAKE);
    }

    @Test
    void mostOverdueFirst_andNeverMoreThanThree() {
        state(AFTER, 9, T0.plus(Duration.ofDays(1)));
        state(KNOW, 9, T0.minus(Duration.ofDays(5)));
        state(PROTECT, 9, T0.minus(Duration.ofDays(2)));
        clock.now = T0.plus(Duration.ofDays(2));

        List<CardDto> cards = due().cards();
        assertThat(cards).extracting(CardDto::skillKey).containsExactly(KNOW, PROTECT, AFTER);
        assertThat(cards.size()).isLessThanOrEqualTo(PracticeCheckInService.MAX_CARDS);
    }

    @Test
    void aStateRowOverridesTheFirstDueRule() {
        completedRun("tornado-warning-shelter-decision", T0);
        for (String s : List.of(KNOW, PROTECT, AFTER)) state(s, 30, T0.plus(Duration.ofDays(40)));
        clock.now = T0.plus(Duration.ofDays(10));
        assertThat(due().cards()).isEmpty();
    }

    @Test
    void suppressionMeansNoCardsAndTheReason() {
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofDays(3));
        when(suppression.check(HH, USER)).thenReturn(Optional.of(new PracticeSuppressionService.Suppression(
                PracticeSuppressionService.Reason.PLAN_ACTIVATION, "Your plan is active", PracticeSuppressionService.WAIT,
                ReadinessAction.OPEN_ACTIVE_ALERTS, Map.of())));

        DueDto d = due();
        assertThat(d.cards()).isEmpty();
        assertThat(d.suppression()).isNotNull();
        assertThat(d.suppression().reason()).isEqualTo("PLAN_ACTIVATION");
    }

    @Test
    void productionServesNothingWhileTheContentIsDraft() {
        when(availability.startable(PracticeKind.CHECKIN)).thenReturn(List.of());
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofDays(30));
        assertThat(due().cards()).isEmpty();
    }

    @Test
    void aCheckInWhoseSkillIsNotServedIsNotServed() {
        when(availability.startable(PracticeKind.SKILL)).thenReturn(List.of());
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofDays(30));
        assertThat(due().cards()).isEmpty();
    }

    @Test
    void theHouseholdIsRequiredAndAccessIsChecked() {
        assertThatThrownBy(() -> service.due(USER, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.answer(USER, ckey(KNOW), " ", "interior")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.skillStates(USER, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.due(USER, "no-such-household")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireCanReadHousehold(USER, HH);
        assertThatThrownBy(() -> service.due(USER, HH)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -- answering --------------------------------------------------------------

    @Test
    void theCorrectFlagNeverLeavesTheServer() throws Exception {
        for (Class<?> dto : List.of(CardDto.class, DueDto.class, AnswerDto.class, ScenarioDtos.ChoiceDto.class)) {
            assertThat(Arrays.stream(dto.getRecordComponents()).map(RecordComponent::getName))
                    .as(dto.getSimpleName()).noneMatch(n -> n.toLowerCase().contains("correct"));
        }
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofDays(2));
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        String wire = json.writeValueAsString(due());
        assertThat(wire).doesNotContain("correct").doesNotContain("fullerExplanation").doesNotContain("remember");
        assertThat(json.writeValueAsString(service.answer(USER, ckey(KNOW), HH, wrongChoice(KNOW)))).doesNotContain("correct");
    }

    @Test
    void aWrongAnswerGetsTheFullerExplanation_aRightOneDoesNot() {
        PracticeContent.CheckIn c = SHIPPED.versions(ckey(KNOW)).get(0).body().checkin();

        AnswerDto wrong = service.answer(USER, ckey(KNOW), HH, wrongChoice(KNOW));
        assertThat(wrong.explanation()).isEqualTo(c.fullerExplanation());
        assertThat(wrong.remember()).isEqualTo(c.remember());

        AnswerDto right = service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        assertThat(right.explanation()).isNull();
        assertThat(right.remember()).isEqualTo(c.remember());
        assertThat(right.answerId()).isNotEqualTo(wrong.answerId());
    }

    @Test
    void anAnswerStoresNoActorAndSchedulesTheSkill() {
        AnswerDto a = service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));

        PracticeCheckInAnswer row = answerRepo.findById(a.answerId()).orElseThrow();
        assertThat(row.getHouseholdId()).isEqualTo(HH);
        assertThat(row.getSkillKey()).isEqualTo(KNOW);
        assertThat(row.getCheckinKey()).isEqualTo(ckey(KNOW));
        assertThat(row.getContentVersion()).isEqualTo(1);
        assertThat(row.isCorrect()).isTrue();
        assertThat(row.getConfidence()).isNull();
        assertThat(Arrays.stream(PracticeCheckInAnswer.class.getDeclaredFields()).map(f -> f.getName().toLowerCase()))
                .noneMatch(n -> n.contains("email") || n.contains("user") || n.contains("actor"));

        PracticeSkillState st = stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow();
        assertThat(st.getIntervalDays()).as("first correct answer climbs 2 -> 9").isEqualTo(9);
        assertThat(st.getPriorIntervalDays()).isEqualTo(2);
        assertThat(st.getLastSeenAt()).isEqualTo(T0);
        assertThat(st.getNextDueAt()).isEqualTo(T0.plus(Duration.ofDays(9)));
        assertThat(st.getLastResult()).isEqualTo("CORRECT");
    }

    @Test
    void anIncorrectAnswerComesBackInTwoDays() {
        state(KNOW, 30, T0);
        service.answer(USER, ckey(KNOW), HH, wrongChoice(KNOW));
        PracticeSkillState st = stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow();
        assertThat(st.getIntervalDays()).isEqualTo(2);
        assertThat(st.getNextDueAt()).isEqualTo(T0.plus(Duration.ofDays(2)));
        assertThat(st.getLastResult()).isEqualTo("INCORRECT");
    }

    @Test
    void anAnswerDrawsTheSkillOutOfTheDueSet() {
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofDays(2));
        assertThat(due().cards()).extracting(CardDto::skillKey).contains(KNOW);
        service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        assertThat(due().cards()).extracting(CardDto::skillKey).doesNotContain(KNOW).contains(PROTECT, AFTER);
    }

    @Test
    void aWriteWaitsWhilePracticeWaits_andWritesNothing() {
        when(suppression.check(HH, USER)).thenReturn(Optional.of(new PracticeSuppressionService.Suppression(
                PracticeSuppressionService.Reason.CHECK_IN, "t", "d", null, Map.of())));
        assertThatThrownBy(() -> service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(answerRepo.count()).isZero();
        assertThat(stateRepo.count()).isZero();
    }

    @Test
    void unknownCheckInOrChoiceIsRefused() {
        assertThatThrownBy(() -> service.answer(USER, ckey(KNOW), HH, "no-such-choice"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.answer(USER, ckey(KNOW), HH, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        // A skill key is not a check-in key.
        assertThatThrownBy(() -> service.answer(USER, KNOW, HH, "interior"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        when(availability.checkStart("checkin-gone")).thenReturn(
                new StartCheck(null, PracticeAvailabilityService.StartDenial.NOT_PUBLISHED));
        assertThatThrownBy(() -> service.answer(USER, "checkin-gone", HH, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        when(availability.checkStart("checkin-off")).thenReturn(
                new StartCheck(null, PracticeAvailabilityService.StartDenial.DISABLED));
        assertThatThrownBy(() -> service.answer(USER, "checkin-off", HH, "x"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE));
        assertThat(answerRepo.count()).isZero();
    }

    // -- confidence -------------------------------------------------------------

    @Test
    void confidenceReschedulesFromTheAnswersOwnStartingInterval() {
        state(KNOW, 30, T0);
        AnswerDto a = service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        assertThat(stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow().getIntervalDays()).isEqualTo(90);

        service.confidence(USER, a.answerId(), "PARTLY");
        PracticeSkillState st = stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow();
        assertThat(st.getIntervalDays()).as("Partly repeats the interval it was on").isEqualTo(30);
        assertThat(st.getNextDueAt()).isEqualTo(T0.plus(Duration.ofDays(30)));
        assertThat(st.getLastConfidence()).isEqualTo("PARTLY");
        assertThat(answerRepo.findById(a.answerId()).orElseThrow().getConfidence()).isEqualTo("PARTLY");

        service.confidence(USER, a.answerId(), "NOT_YET");
        assertThat(stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow().getIntervalDays()).isEqualTo(15);

        service.confidence(USER, a.answerId(), "HAD_IT");
        assertThat(stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow().getIntervalDays()).isEqualTo(90);
    }

    @Test
    void confidenceOnAnIncorrectAnswerStaysTwoDays() {
        state(KNOW, 30, T0);
        AnswerDto a = service.answer(USER, ckey(KNOW), HH, wrongChoice(KNOW));
        service.confidence(USER, a.answerId(), "HAD_IT");
        assertThat(stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow().getIntervalDays()).isEqualTo(2);
    }

    @Test
    void confidenceOnAnAnswerThatIsNoLongerTheNewestIsANoOp() {
        state(KNOW, 30, T0);
        AnswerDto first = service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        clock.now = T0.plus(Duration.ofDays(1));
        service.answer(USER, ckey(KNOW), HH, wrongChoice(KNOW));
        PracticeSkillState before = stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow();
        assertThat(before.getIntervalDays()).isEqualTo(2);

        service.confidence(USER, first.answerId(), "HAD_IT");

        PracticeSkillState after = stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow();
        assertThat(after.getIntervalDays()).isEqualTo(2);
        assertThat(after.getNextDueAt()).isEqualTo(before.getNextDueAt());
        assertThat(after.getLastConfidence()).isNull();
        assertThat(answerRepo.findById(first.answerId()).orElseThrow().getConfidence()).isNull();
    }

    @Test
    void confidenceIsValidatedAndAuthorized() {
        AnswerDto a = service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        assertThatThrownBy(() -> service.confidence(USER, a.answerId(), "GREAT"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.confidence(USER, 9_999_999L, "PARTLY"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireCanReadHousehold(USER, HH);
        assertThatThrownBy(() -> service.confidence(USER, a.answerId(), "PARTLY"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // -- skill state ------------------------------------------------------------

    private Map<String, SkillStateDto> states() {
        Map<String, SkillStateDto> out = new java.util.HashMap<>();
        for (SkillStateDto s : service.skillStates(USER, HH).states()) out.put(s.skillKey(), s);
        return out;
    }

    @Test
    void everythingIsNewUntilSomethingWasDone() {
        assertThat(states()).hasSize(4);
        assertThat(states().values()).allSatisfy(s -> {
            assertThat(s.status()).isEqualTo("NEW");
            assertThat(s.lastPracticedAt()).isNull();
        });
    }

    @Test
    void aPhysicalSkillIsPracticedByItsDrillOnly() {
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofHours(1));

        assertThat(states().get(KNOW).status()).as("REQUIRED rehearsal: the story alone is not practice").isEqualTo("NEW");
        assertThat(states().get(AFTER).status()).as("no physical rehearsal: the story counts").isEqualTo("PRACTICED");
        assertThat(states().get(AFTER).lastPracticedAt()).isEqualTo(T0);

        service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        assertThat(states().get(KNOW).status()).as("neither does a recall answer").isEqualTo("NEW");

        drillMark("tornado-safe-room-speed-round", T0.plus(Duration.ofMinutes(30)));
        assertThat(states().get(KNOW).status()).isEqualTo("PRACTICED");
        assertThat(states().get(KNOW).lastPracticedAt()).isEqualTo(T0.plus(Duration.ofMinutes(30)));
    }

    @Test
    void aDueCheckInOnAPhysicalSkillReadsRefreshNotNew() {
        completedRun("tornado-warning-shelter-decision", T0);
        clock.now = T0.plus(Duration.ofDays(2));
        assertThat(states().get(KNOW).status()).isEqualTo("REFRESH");
    }

    @Test
    void anEarlyAnswerIsRecordedButDoesNotClimbTheLadder() {
        service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        clock.now = T0.plus(Duration.ofHours(1));
        service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        PracticeSkillState st = stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow();
        assertThat(st.getIntervalDays()).isEqualTo(9);
        assertThat(st.getNextDueAt()).isEqualTo(T0.plus(Duration.ofDays(9)));
        assertThat(answerRepo.count()).isEqualTo(2);
    }

    @Test
    void anAnswerOnceDueClimbsAgain() {
        service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        clock.now = T0.plus(Duration.ofDays(9));
        service.answer(USER, ckey(KNOW), HH, correctChoice(KNOW));
        assertThat(stateRepo.findByHouseholdIdAndSkillKey(HH, KNOW).orElseThrow().getIntervalDays()).isEqualTo(30);
    }

    @Test
    void anAnswerRacingTheFirstInsertIs409NotAServerError() {
        PracticeSkillStateRepo racing = mock(PracticeSkillStateRepo.class);
        when(racing.findByHouseholdIdAndSkillKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(racing.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));
        PracticeSkillStateService skillState = new PracticeSkillStateService(availability, stateRepo, runs, groups, access, clock);
        PracticeCheckInService svc = new PracticeCheckInService(availability, suppression, skillState, racing, answerRepo, access, clock);
        assertThatThrownBy(() -> svc.answer(USER, ckey(KNOW), HH, correctChoice(KNOW)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void aDrillPhaseMarkCountsForItsDrill() {
        drillMark("tornado-safe-room-speed-round#night", T0);
        assertThat(states().get(KNOW).lastPracticedAt()).isEqualTo(T0);
        assertThat(states().get(PROTECT).lastPracticedAt()).isEqualTo(T0);
    }

    @Test
    void refreshWhenTheNextCheckInIsDue() {
        completedRun("tornado-warning-shelter-decision", T0);

        clock.now = T0.plus(Duration.ofDays(1));
        assertThat(states().get(AFTER).status()).isEqualTo("PRACTICED");
        clock.now = T0.plus(Duration.ofDays(2));
        assertThat(states().get(AFTER).status()).isEqualTo("REFRESH");

        service.answer(USER, ckey(AFTER), HH, correctChoice(AFTER));
        assertThat(states().get(AFTER).status()).as("answered: next due in 9 days").isEqualTo("PRACTICED");
        clock.now = T0.plus(Duration.ofDays(2 + 9));
        assertThat(states().get(AFTER).status()).isEqualTo("REFRESH");
    }

    @Test
    void noRefreshIsPromisedWhenThereIsNoCheckInToServe() {
        completedRun("tornado-warning-shelter-decision", T0);
        when(availability.startable(PracticeKind.CHECKIN)).thenReturn(List.of());
        clock.now = T0.plus(Duration.ofDays(30));
        assertThat(states().get(AFTER).status()).isEqualTo("PRACTICED");
    }

    @Test
    void theListIsEmptyWhenNoSkillIsServed() {
        when(availability.startable(PracticeKind.SKILL)).thenReturn(new ArrayList<>());
        assertThat(service.skillStates(USER, HH).states()).isEmpty();
    }
}
