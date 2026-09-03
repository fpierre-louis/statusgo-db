package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationLifecycleFrame;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The 72-hour timer stops being silent (2026-09-03, BE-7, audit B2).
 *
 * <p>Every other way an activation ends says so. The timer ended one by ceasing
 * to satisfy {@code expiresAt > now} — no frame, no log row, no push — so a
 * client only found out if something unrelated made it refetch {@code /me}.</p>
 *
 * <p>Three of the assertions here guard rulings whose opposite is the more
 * obvious implementation, and they are the ones not to "simplify":
 * the timer must NOT stamp {@code endedAt}, must NOT push, and must not
 * announce the same row twice.</p>
 *
 * Pure Mockito — no Spring context, no DB. Synchronization is cleared in
 * {@link #setUp()} so the no-transaction branch is what runs; see the comment
 * there.
 */
class PlanActivationExpiryTest {

    private static final String OWNER = "owner@x.com";
    private static final String HOUSEHOLD_ID = "hh-1";

    private PlanActivationRepo activationRepo;
    private WebSocketMessageSender ws;
    private HouseholdEventService events;
    private NotificationService notifications;
    private PlanActivationService service;

    /** The rows the fake repo query filters, so idempotence is exercised, not stubbed. */
    private final List<PlanActivation> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        service = new PlanActivationService(activationRepo, mock(PlanActivationAckRepo.class), userInfoRepo,
                mock(MeetingPlaceRepo.class), mock(EvacuationPlanRepo.class),
                mock(OriginLocationRepo.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencyContactRepo.class), ws = mock(WebSocketMessageSender.class),
                groupRepo, notifications = mock(NotificationService.class),
                mock(HouseholdAccessService.class),
                mock(HouseholdResolver.class), mock(GoBagService.class),
                events = mock(HouseholdEventService.class), mock(GroupService.class));

        // T-89: registerSynchronization THROWS with no active transaction, and a
        // test that calls initSynchronization() never fires its afterCommit. The
        // sweep can reach this either way, so the branch that actually runs
        // something is the one worth covering.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        Group household = new Group();
        household.setGroupId(HOUSEHOLD_ID);
        household.setGroupType("Household");
        household.setMemberEmails(List.of(OWNER, "spouse@x.com"));
        when(groupRepo.findByMemberEmail(OWNER)).thenReturn(List.of(household));
        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        // A REACHABLE household member, and this line is load-bearing. With
        // findByUserEmailIn stubbed empty the push loop had no one to iterate,
        // so `itDoesNotPush` passed whether or not the timer pushed — re-arming
        // it (swapping announce for announceEnd) left the suite green. T-86.
        UserInfo spouse = new UserInfo();
        spouse.setUserEmail("spouse@x.com");
        spouse.setFcmtoken("tok-spouse");
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(spouse));

        // The real query, in Java: expired, unhandled, and not already ended.
        when(activationRepo.findExpiredNotHandled(any(Instant.class), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Instant now = inv.getArgument(0);
                    Pageable page = inv.getArgument(1);
                    return table.stream()
                            .filter(a -> !a.getExpiresAt().isAfter(now))
                            .filter(a -> a.getExpiryHandledAt() == null)
                            .filter(a -> a.getEndedAt() == null)
                            .limit(page.getPageSize())
                            .toList();
                });
        when(activationRepo.save(any(PlanActivation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private PlanActivation expired(String id, long hoursAgo) {
        PlanActivation a = new PlanActivation();
        a.setId(id);
        a.setOwnerEmail(OWNER);
        a.setActivatedAt(Instant.now().minus(hoursAgo + 72, ChronoUnit.HOURS));
        a.setExpiresAt(Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
        table.add(a);
        return a;
    }

    private PlanActivation live(String id) {
        PlanActivation a = new PlanActivation();
        a.setId(id);
        a.setOwnerEmail(OWNER);
        a.setActivatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        a.setExpiresAt(Instant.now().plus(70, ChronoUnit.HOURS));
        table.add(a);
        return a;
    }

    // ── the timeout leaves the server ───────────────────────────────────────

    @Test
    void anExpiredActivationBroadcastsAnEnding() {
        PlanActivation a = expired("act-timed-out", 1);

        assertEquals(1, service.handleExpiredActivations(200));

        ArgumentCaptor<ActivationLifecycleFrame> frame = ArgumentCaptor.forClass(ActivationLifecycleFrame.class);
        verify(ws).sendActivationLifecycle(eq("act-timed-out"), frame.capture());
        assertEquals("ended", frame.getValue().state(),
                "a timeout has to LOOK like an ending or no client converges");
        assertNull(frame.getValue().byEmail(), "no person ended this — a clock did");
        assertNotNull(a.getExpiryHandledAt(), "the sweep records that it handled the row");
    }

    @Test
    void itWritesTheHouseholdEventWithNoActor() {
        expired("act-logged", 3);

        service.handleExpiredActivations(200);

        // Null actor is the event log's own documented signal for "the timer did
        // this rather than a person"; the chat renders that row without a name.
        verify(events).recordActivationEnded(HOUSEHOLD_ID, null, "act-logged");
    }

    // ── the three things it deliberately does NOT do ────────────────────────

    @Test
    void itDoesNotStampEndedAt() {
        // THE RULING: `endedAt` means A PERSON SAID SO. EndActivationControl
        // renders "Your household ended this" off this field alone, and
        // activationEnd.test.js has a case named "EXPIRED is closed with NO
        // endedAt — nobody said it was over". Stamping the timer here would make
        // the app attribute a clock's decision to the household.
        PlanActivation a = expired("act-not-ended", 2);

        service.handleExpiredActivations(200);

        assertNull(a.getEndedAt(), "the timer never claims a person ended it");
        assertNull(a.getEndedByEmail());
    }

    @Test
    void itDoesNotPush() {
        // THE RULING: PLAN_ACTIVATION_RECEIVED is Lane A and BYPASSES QUIET
        // HOURS. A 72-hour timer expiring at 3am would wake every member to
        // report a timer, under copy ("All clear") that credits the household
        // with a decision it did not make.
        expired("act-quiet", 1);

        service.handleExpiredActivations(200);

        // The whole service, not one overload — the timer has no business
        // reaching the notification layer by any route.
        verifyNoInteractions(notifications);
    }

    @Test
    void itDoesNotTouchARunningActivation() {
        PlanActivation running = live("act-running");

        assertEquals(0, service.handleExpiredActivations(200));

        assertNull(running.getExpiryHandledAt());
        verifyNoInteractions(ws);
    }

    // ── idempotence, which is the whole reason the column exists ────────────

    @Test
    void asecondTickAnnouncesNothing() {
        expired("act-once", 1);

        assertEquals(1, service.handleExpiredActivations(200));
        assertEquals(0, service.handleExpiredActivations(200),
                "without the handled mark the hourly job would re-announce this "
                        + "row — and add one more 'the plan ended' to the household's "
                        + "history — every hour for fourteen days");

        verify(ws, times(1)).sendActivationLifecycle(eq("act-once"), any());
        verify(events, times(1)).recordActivationEnded(anyString(), any(), eq("act-once"));
    }

    @Test
    void anActivationAPersonAlreadyEndedIsNotAnnouncedAgainWhenItsTimerRunsOut() {
        // Ending broadcasts at the moment a person taps it. If the expiry pass
        // picked the row up again three days later the household would carry two
        // endings for one event.
        PlanActivation a = expired("act-human-ended", 4);
        a.setEndedAt(Instant.now().minus(5, ChronoUnit.HOURS));
        a.setEndedByEmail(OWNER);

        assertEquals(0, service.handleExpiredActivations(200));

        verifyNoInteractions(ws);
    }

    // ── the batch bound ─────────────────────────────────────────────────────

    @Test
    void itHonoursTheBatchSize() {
        expired("act-a", 5);
        expired("act-b", 4);
        expired("act-c", 3);

        assertEquals(2, service.handleExpiredActivations(2), "a backlog drains across ticks");
        assertEquals(1, service.handleExpiredActivations(2));
    }

    @Test
    void aNonPositiveBatchDoesNothing() {
        expired("act-guard", 1);

        assertEquals(0, service.handleExpiredActivations(0));

        verifyNoInteractions(activationRepo);
    }

    // ── a broken side effect must not cost the mark ─────────────────────────

    @Test
    void aDeadSocketStillLeavesTheRowHandled() {
        // The frame is the nice-to-have; the mark is what stops the hourly job
        // repeating itself forever. An activation that timed out and could not
        // say so is bad. One that says so once an hour until Christmas is worse.
        PlanActivation a = expired("act-ws-down", 1);
        doThrow(new RuntimeException("broker down"))
                .when(ws).sendActivationLifecycle(anyString(), any());

        assertDoesNotThrow(() -> service.handleExpiredActivations(200));

        assertNotNull(a.getExpiryHandledAt());
        verify(events).recordActivationEnded(HOUSEHOLD_ID, null, "act-ws-down");
    }
}
