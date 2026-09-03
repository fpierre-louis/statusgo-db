package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.PlanActivationAck;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.AckRequest;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationDetailDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Giving a plan activation an end (2026-09-01).
 *
 * <p>Before this, the only thing that stopped an activation was its 72-hour
 * {@code expiresAt}. The most recent production row is the defect it caused:
 * activated 2026-08-29 17:31, and every household surface read EVACUATING until
 * the timer ran out three days later — with no alert anywhere near it.</p>
 *
 * <p>Three of the assertions below guard rulings whose opposite is the more
 * obvious implementation, so they are the ones worth not "simplifying":
 * ending must NOT reject acks, must NOT 410 the link, and must NOT be
 * reachable by a link holder. Each is argued at {@code PlanActivationService#endActivation}.</p>
 *
 * Pure Mockito — no Spring context, no DB.
 */
class PlanActivationEndTest {

    private static final String ACT_ID = "act-end-1";
    private static final String OWNER = "owner@x.com";
    private static final String CO_MEMBER = "spouse@x.com";
    private static final String STRANGER = "stranger@x.com";

    private PlanActivationRepo activationRepo;
    private PlanActivationAckRepo ackRepo;
    private HouseholdAccessService householdAccess;
    private WebSocketMessageSender ws;
    private PlanActivationService service;

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        ackRepo = mock(PlanActivationAckRepo.class);
        householdAccess = mock(HouseholdAccessService.class);
        UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
        service = new PlanActivationService(activationRepo, ackRepo, userInfoRepo,
                mock(MeetingPlaceRepo.class), mock(EvacuationPlanRepo.class),
                mock(OriginLocationRepo.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencyContactRepo.class), ws = mock(WebSocketMessageSender.class),
                mock(GroupRepo.class), mock(NotificationService.class),
                householdAccess,
                mock(HouseholdResolver.class), mock(GoBagService.class),
                mock(HouseholdEventService.class));
        TransactionSynchronizationManager.initSynchronization();

        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(activationRepo.save(any(PlanActivation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ackRepo.findByActivationIdOrderByAckedAtAsc(anyString())).thenReturn(java.util.List.of());
        when(ackRepo.findByActivationIdAndRecipientEmailIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(ackRepo.save(any(PlanActivationAck.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private PlanActivation live() {
        PlanActivation a = new PlanActivation();
        a.setId(ACT_ID);
        a.setOwnerEmail(OWNER);
        a.setActivatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        a.setExpiresAt(Instant.now().plus(70, ChronoUnit.HOURS));
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        return a;
    }

    // ── who may end it ──────────────────────────────────────────────────────

    @Test
    void ownerCanEndIt() {
        PlanActivation a = live();

        ActivationDetailDto dto = service.endActivation(ACT_ID, OWNER);

        assertNotNull(a.getEndedAt(), "the row records when it ended");
        assertEquals(OWNER, a.getEndedByEmail());
        assertTrue(dto.closed(), "closed covers ended, not only expired");
        assertNotNull(dto.endedAt(), "a surface must be able to tell ended from timed-out");
        assertTrue(dto.viewerCanEnd(), "the server tells the client the capability, not the membership");
    }

    @Test
    void householdCoMemberCanEndIt() {
        // The owner may be the person who is unreachable — that is the case
        // where this button matters most.
        PlanActivation a = live();
        when(householdAccess.canReadPlanDataFor(CO_MEMBER, OWNER)).thenReturn(true);

        service.endActivation(ACT_ID, CO_MEMBER);

        assertNotNull(a.getEndedAt());
        assertEquals(CO_MEMBER, a.getEndedByEmail());
    }

    @Test
    void strangerHoldingTheLinkCannotEndIt() {
        // THE RULING: reading is link-possession, ending is not. A link gets
        // forwarded; "whoever holds it may declare the evacuation over" is the
        // inverse of the failure this whole change closes.
        PlanActivation a = live();
        when(householdAccess.canReadPlanDataFor(STRANGER, OWNER)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.endActivation(ACT_ID, STRANGER));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertNull(a.getEndedAt(), "a refused end leaves the activation running");
    }

    @Test
    void unknownActivationIs404() {
        when(activationRepo.findById("nope")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.endActivation("nope", OWNER));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── idempotence ─────────────────────────────────────────────────────────

    @Test
    void endingTwiceDoesNotMoveTheTimestamp() {
        // Two household members tapping End at once must not produce two
        // different "over at" times for one event.
        PlanActivation a = live();
        when(householdAccess.canReadPlanDataFor(CO_MEMBER, OWNER)).thenReturn(true);

        service.endActivation(ACT_ID, OWNER);
        Instant first = a.getEndedAt();
        service.endActivation(ACT_ID, CO_MEMBER);

        assertEquals(first, a.getEndedAt());
        assertEquals(OWNER, a.getEndedByEmail(), "the first ender keeps the attribution");
    }

    @Test
    void anAlreadyEndedActivationStill403sAStranger() {
        // Permission is checked BEFORE the idempotence branch. An ended
        // activation is not a free "yes" — a stranger probing this endpoint
        // must not learn from the response that the household exists.
        live();
        service.endActivation(ACT_ID, OWNER);
        when(householdAccess.canReadPlanDataFor(STRANGER, OWNER)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.endActivation(ACT_ID, STRANGER));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    // ── the two things ending deliberately does NOT do ──────────────────────

    @Test
    void anEndedActivationStillAcceptsAnAck() {
        // THE RULING: the owner ends it because everyone THEY CAN SEE is safe.
        // The straggler who has not replied is exactly the person whose
        // "I need help" must still land.
        PlanActivation a = live();
        service.endActivation(ACT_ID, OWNER);

        assertDoesNotThrow(() -> service.recordAck(ACT_ID,
                new AckRequest("straggler@x.com", "Straggler", "help", null, null)));
    }

    @Test
    void anEndedActivationStillReadsBackInsteadOf410() {
        // THE RULING: a recipient who opens the link ten minutes late should
        // learn WHAT happened, not merely that something did.
        PlanActivation a = live();
        service.endActivation(ACT_ID, OWNER);

        Optional<ActivationDetailDto> read = service.getActivation(ACT_ID, null);

        assertTrue(read.isPresent(), "ending does not make the link gone");
        assertFalse(read.get().viewerCanEnd(), "a link holder is never offered the End control");
        assertTrue(read.get().closed());
        assertNotNull(read.get().endedAt());
        assertEquals("closed", read.get().activeSituation().status());
        assertNotNull(read.get().activeSituation().endedAt());
    }

    // ── the predicate that had three copies ─────────────────────────────────

    @Test
    void expiryStillClosesIt_andIsDistinguishableFromEnded() {
        PlanActivation a = new PlanActivation();
        a.setId("act-expired");
        a.setOwnerEmail(OWNER);
        a.setActivatedAt(Instant.now().minus(80, ChronoUnit.HOURS));
        a.setExpiresAt(Instant.now().minus(8, ChronoUnit.HOURS));
        when(activationRepo.findById("act-expired")).thenReturn(Optional.of(a));

        ActivationDetailDto dto = service.getActivation("act-expired", OWNER).orElseThrow();

        assertTrue(dto.closed(), "the timer still closes it");
        assertNull(dto.endedAt(), "nobody said it was over — it just stopped being live");
    }

    // ── THE END HAS TO LEAVE THE SERVER ──────────────────────────────────────
    //
    // Before Phase 1, `endActivation` wrote `endedAt` and returned. The ender
    // saw the response and every other household member kept reading
    // EVACUATING until their device happened to refetch, which nothing caused
    // it to do. These two guard the fix.
    //
    // Synchronization is CLEARED first, deliberately. With it active the
    // broadcast registers an afterCommit callback that no test transaction
    // will ever fire; clearing it exercises the else-branch — the one the
    // service comments call a trap for the next caller, and the one nothing
    // else covers.
    @Test
    void endingBroadcastsALifecycleFrame() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        live();
        when(householdAccess.canReadPlanDataFor(OWNER, OWNER)).thenReturn(true);

        service.endActivation(ACT_ID, OWNER);

        org.mockito.ArgumentCaptor<io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationLifecycleFrame> frame =
                org.mockito.ArgumentCaptor.forClass(
                        io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationLifecycleFrame.class);
        verify(ws).sendActivationLifecycle(org.mockito.ArgumentMatchers.eq(ACT_ID), frame.capture());
        assertEquals("ended", frame.getValue().state());
        assertEquals(OWNER, frame.getValue().byEmail());
    }

    // Ending twice must not tell the household twice. The idempotence guard is
    // on the WRITE (`endedAt == null`), and the broadcast lives inside it — so
    // this asserts the two cannot drift apart.
    @Test
    void endingTwiceBroadcastsOnce() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        live();
        when(householdAccess.canReadPlanDataFor(OWNER, OWNER)).thenReturn(true);

        service.endActivation(ACT_ID, OWNER);
        service.endActivation(ACT_ID, OWNER);

        verify(ws, org.mockito.Mockito.times(1))
                .sendActivationLifecycle(org.mockito.ArgumentMatchers.eq(ACT_ID),
                        org.mockito.ArgumentMatchers.any());
    }
}
