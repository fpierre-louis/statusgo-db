package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartCheck;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.TogetherSessionService.SessionDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.web.Idempotent;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-8 sessions against real repositories: lifecycle, access, suppression, completion, sweep, clear. */
@DataJpaTest
@ActiveProfiles("test")
class TogetherSessionServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-22T12:00:00Z");
    private static final String HH = "hh-tog";
    private static final String OTHER = "hh-other";
    private static final String ME = "member@x.com";
    private static final String KEY = "together-get-out-and-meet-up";

    @Autowired GroupRepo groups;
    @Autowired PracticeTogetherSessionRepo sessions;
    @Autowired FamilyPracticeCompletionRepo completions;
    @Autowired ScenarioRunRepo runs;
    @Autowired PracticeCheckInAnswerRepo answers;
    @Autowired PracticeSkillStateRepo states;
    @Autowired TestEntityManager em;

    static final class MutableClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private PracticeAvailabilityService availability;
    private PracticeSuppressionService suppression;
    private HouseholdAccessService access;
    private TogetherSessionService service;
    private Version pilot;

    @BeforeEach
    void setUp() {
        PracticeCatalog catalog = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);
        pilot = catalog.versions(KEY).get(0);
        availability = mock(PracticeAvailabilityService.class);
        when(availability.checkStart(KEY)).thenReturn(new StartCheck(new Entry(pilot, true), null));
        Version kit = catalog.versions("kit-builder").get(0);
        when(availability.checkStart("kit-builder")).thenReturn(new StartCheck(new Entry(kit, false), null));
        when(availability.checkStart("nope")).thenReturn(new StartCheck(null, PracticeAvailabilityService.StartDenial.NOT_FOUND));
        suppression = mock(PracticeSuppressionService.class);
        when(suppression.check(anyString(), anyString())).thenReturn(Optional.empty());
        access = mock(HouseholdAccessService.class);
        for (String id : new String[] {HH, OTHER}) {
            Group g = new Group();
            g.setGroupId(id);
            g.setGroupType("Household");
            g.setGroupName(id);
            groups.saveAndFlush(g);
        }
        service = new TogetherSessionService(availability, suppression, sessions, groups, access, clock);
    }

    private static int status(Throwable t) {
        return ((ResponseStatusException) t).getStatusCode().value();
    }

    // -- create / turn ---------------------------------------------------------

    @Test
    void createStartsAtTurnZeroWith24HourExpiryAndStoresNothingElse() {
        SessionDto s = service.create(ME, HH, KEY, "TAKE_A_TURN");
        assertThat(s.turnIndex()).isZero();
        assertThat(s.activityKey()).isEqualTo(KEY);
        assertThat(s.contentVersion()).isEqualTo(1);
        assertThat(s.style()).isEqualTo("TAKE_A_TURN");
        assertThat(s.expiresAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
        // The entity has no actor, child, age or role-holder column.
        assertThat(Arrays.stream(PracticeTogetherSession.class.getDeclaredFields()).map(f -> f.getName()))
                .containsExactlyInAnyOrder("id", "householdId", "activityKey", "contentVersion", "style",
                        "turnIndex", "startedAt", "updatedAt", "expiresAt");
    }

    @Test
    void aNewSessionReplacesTheOpenOneForTheSameActivity() {
        SessionDto first = service.create(ME, HH, KEY, "DO_IT_TOGETHER");
        service.turn(ME, first.sessionId(), 2);
        SessionDto second = service.create(ME, HH, KEY, "TEEN_LEAD");
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(second.turnIndex()).isZero();
        assertThat(sessions.findAll()).singleElement().satisfies(s -> assertThat(s.getStyle()).isEqualTo("TEEN_LEAD"));
    }

    @Test
    void anotherHouseholdsSessionIsLeftAlone() {
        service.create(ME, HH, KEY, "TAKE_A_TURN");
        service.create(ME, OTHER, KEY, "TAKE_A_TURN");
        assertThat(sessions.findAll()).hasSize(2);
    }

    @Test
    void turnMovesWithinZeroToTurnsInclusiveAndRejectsOutOfBounds() {
        SessionDto s = service.create(ME, HH, KEY, "TAKE_A_TURN");
        int turns = pilot.body().activity().turns().size();
        assertThat(service.turn(ME, s.sessionId(), 3).turnIndex()).isEqualTo(3);
        assertThat(service.turn(ME, s.sessionId(), turns).turnIndex()).isEqualTo(turns);
        assertThat(service.turn(ME, s.sessionId(), 0).turnIndex()).isZero();
        assertThatThrownBy(() -> service.turn(ME, s.sessionId(), turns + 1)).satisfies(t -> assertThat(status(t)).isEqualTo(400));
        assertThatThrownBy(() -> service.turn(ME, s.sessionId(), -1)).satisfies(t -> assertThat(status(t)).isEqualTo(400));
        assertThatThrownBy(() -> service.turn(ME, s.sessionId(), null)).satisfies(t -> assertThat(status(t)).isEqualTo(400));
    }

    @Test
    void anExpiredSessionIsGone() {
        SessionDto s = service.create(ME, HH, KEY, "TAKE_A_TURN");
        clock.now = T0.plus(Duration.ofHours(24));
        assertThatThrownBy(() -> service.turn(ME, s.sessionId(), 1)).satisfies(t -> assertThat(status(t)).isEqualTo(410));
    }

    @Test
    void anUnknownSessionIs404() {
        assertThatThrownBy(() -> service.turn(ME, 99999L, 1)).satisfies(t -> assertThat(status(t)).isEqualTo(404));
    }

    @Test
    void aNonMemberIsRefusedOnCreateAndTurn() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no")).when(access).requireCanReadHousehold(eq("intruder@x.com"), anyString());
        assertThatThrownBy(() -> service.create("intruder@x.com", HH, KEY, "TAKE_A_TURN"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(403));
        SessionDto s = service.create(ME, HH, KEY, "TAKE_A_TURN");
        assertThatThrownBy(() -> service.turn("intruder@x.com", s.sessionId(), 1))
                .satisfies(t -> assertThat(status(t)).isEqualTo(403));
        assertThat(sessions.findById(s.sessionId()).orElseThrow().getTurnIndex()).isZero();
    }

    @Test
    void unknownHouseholdIs404() {
        assertThatThrownBy(() -> service.create(ME, "no-such", KEY, "TAKE_A_TURN"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(404));
    }

    @Test
    void suppressionIs409OnCreateAndTurn() {
        SessionDto s = service.create(ME, HH, KEY, "TAKE_A_TURN");
        PracticeSuppressionService.Suppression waiting = new PracticeSuppressionService.Suppression(
                PracticeSuppressionService.Reason.CHECK_IN, "t", "d", null, java.util.Map.of());
        when(suppression.check(anyString(), anyString())).thenReturn(Optional.of(waiting));
        assertThatThrownBy(() -> service.create(ME, HH, KEY, "TAKE_A_TURN")).satisfies(t -> assertThat(status(t)).isEqualTo(409));
        assertThatThrownBy(() -> service.turn(ME, s.sessionId(), 1)).satisfies(t -> assertThat(status(t)).isEqualTo(409));
    }

    @Test
    void onlyAStartableTogetherActivityCanStartASession() {
        assertThatThrownBy(() -> service.create(ME, HH, "kit-builder", "TAKE_A_TURN"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(404));
        assertThatThrownBy(() -> service.create(ME, HH, "nope", "TAKE_A_TURN"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(404));
        when(availability.checkStart(KEY)).thenReturn(new StartCheck(null, PracticeAvailabilityService.StartDenial.DISABLED));
        assertThatThrownBy(() -> service.create(ME, HH, KEY, "TAKE_A_TURN"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(410));
        when(availability.checkStart(KEY)).thenReturn(new StartCheck(null, PracticeAvailabilityService.StartDenial.NOT_PUBLISHED));
        assertThatThrownBy(() -> service.create(ME, HH, KEY, "TAKE_A_TURN"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(404));
        assertThat(sessions.findAll()).isEmpty();
    }

    @Test
    void styleMustBeOneOfTheThree() {
        assertThatThrownBy(() -> service.create(ME, HH, KEY, "SOLO")).satisfies(t -> assertThat(status(t)).isEqualTo(400));
        assertThatThrownBy(() -> service.create(ME, HH, KEY, null)).satisfies(t -> assertThat(status(t)).isEqualTo(400));
    }

    // -- detail / completion ---------------------------------------------------

    private FamilyPracticeService family() {
        PracticeSuppressionService calm = suppression;
        return new FamilyPracticeService(availability, calm, completions, groups, access, sessions, clock);
    }

    @Test
    void detailShowsTheNewestUnexpiredSessionAndCompletionDeletesIt() {
        FamilyPracticeService fam = family();
        assertThat(fam.detail(KEY, ME, HH).activeSession()).isNull();
        SessionDto s = service.create(ME, HH, KEY, "TEEN_LEAD");
        service.turn(ME, s.sessionId(), 3);
        FamilyPracticeDtos.ActiveSessionDto active = fam.detail(KEY, ME, HH).activeSession();
        assertThat(active.sessionId()).isEqualTo(s.sessionId());
        assertThat(active.style()).isEqualTo("TEEN_LEAD");
        assertThat(active.turnIndex()).isEqualTo(3);
        assertThat(active.expiresAt()).isEqualTo(s.expiresAt());
        // another household does not see it
        assertThat(fam.detail(KEY, ME, OTHER).activeSession()).isNull();

        service.create(ME, OTHER, KEY, "TAKE_A_TURN");
        fam.complete(KEY, ME, HH, pilot.version(), pilot.contentHash());
        em.clear();
        assertThat(sessions.findAll()).singleElement().satisfies(x -> assertThat(x.getHouseholdId()).isEqualTo(OTHER));
        assertThat(fam.detail(KEY, ME, HH).activeSession()).isNull();
    }

    @Test
    void anExpiredSessionIsNotShownOnDetail() {
        service.create(ME, HH, KEY, "TAKE_A_TURN");
        clock.now = T0.plus(Duration.ofHours(25));
        assertThat(family().detail(KEY, ME, HH).activeSession()).isNull();
    }

    // -- sweep / clear ---------------------------------------------------------

    private PracticeTogetherSession raw(String hh, Instant expires) {
        PracticeTogetherSession s = new PracticeTogetherSession();
        s.setHouseholdId(hh);
        s.setActivityKey(KEY);
        s.setContentVersion(1);
        s.setStyle("TAKE_A_TURN");
        s.setStartedAt(expires.minus(Duration.ofHours(24)));
        s.setUpdatedAt(expires.minus(Duration.ofHours(24)));
        s.setExpiresAt(expires);
        return sessions.saveAndFlush(s);
    }

    @Test
    void theSweepDeletesExpiredSessionsOnlyAndKeepsLiveOnes() {
        PracticeTogetherSession expired = raw(HH, T0.minusSeconds(1));
        PracticeTogetherSession boundary = raw(OTHER, T0);
        PracticeTogetherSession live = raw(HH, T0.plusSeconds(60));
        PracticeRetentionSweepService sweep = new PracticeRetentionSweepService(runs,
                completions, answers, sessions, clock);
        assertThat(sweep.sweepAll()).isEqualTo(2);
        em.clear();
        assertThat(sessions.findById(expired.getId())).isEmpty();
        assertThat(sessions.findById(boundary.getId())).isEmpty();
        assertThat(sessions.findById(live.getId())).isPresent();
    }

    @Test
    void clearHistoryDeletesThatHouseholdsSessionsOnly() {
        service.create(ME, HH, KEY, "TAKE_A_TURN");
        service.create(ME, OTHER, KEY, "TAKE_A_TURN");
        PracticeHistoryService history = new PracticeHistoryService(access, groups, runs, completions, answers, states,
                sessions, mock(AdminAuditLogService.class), mock(WebSocketMessageSender.class), clock);
        PracticeHistoryService.ClearResult r = history.clear("owner@x.com", HH, true);
        assertThat(r.removed().togetherSessions()).isEqualTo(1);
        em.clear();
        assertThat(sessions.findAll()).singleElement().satisfies(x -> assertThat(x.getHouseholdId()).isEqualTo(OTHER));
    }

    // -- resource contract -----------------------------------------------------

    @Test
    void theResourceHasTheContractPathsAndIsIdempotent() {
        Method create = Arrays.stream(TogetherSessionResource.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("create")).findFirst().orElseThrow();
        Method turn = Arrays.stream(TogetherSessionResource.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("turn")).findFirst().orElseThrow();
        assertThat(create.getAnnotation(PostMapping.class).value()).containsExactly("/api/practice/together/sessions");
        assertThat(turn.getAnnotation(PostMapping.class).value()).containsExactly("/api/practice/together/sessions/{id}/turn");
        assertThat(create.isAnnotationPresent(Idempotent.class)).isTrue();
        assertThat(turn.isAnnotationPresent(Idempotent.class)).isTrue();
        assertThat(Arrays.stream(TogetherSessionResource.CreateRequest.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("householdId", "activityKey", "style");
        assertThat(Arrays.stream(TogetherSessionResource.TurnRequest.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("turnIndex");
    }
}
