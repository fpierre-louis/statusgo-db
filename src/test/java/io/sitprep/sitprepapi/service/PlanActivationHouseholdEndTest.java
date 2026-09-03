package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.HouseholdActivationsEndedDto;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "All clear" — ending EVERY live activation in a household (2026-09-03, BE-2,
 * audit B7).
 *
 * <p>An activation is keyed on the OWNER's email, so a household where two
 * people launched has two rows. {@code MeService.resolveActiveActivationIdForHome}
 * resolves Home's state by taking {@code max(activatedAt)} across every member,
 * so ending one row lets it fall back to the other — Home stays EVACUATING and
 * the person who declared it over watches it come back. That is the whole
 * reason this method is keyed on the household rather than the row.</p>
 *
 * Pure Mockito — no Spring context, no DB.
 */
class PlanActivationHouseholdEndTest {

    private static final String HOUSEHOLD_ID = "hh-1";
    private static final String OWNER = "owner@x.com";
    private static final String SPOUSE = "spouse@x.com";
    private static final String TEEN = "teen@x.com";

    private PlanActivationRepo activationRepo;
    private GroupRepo groupRepo;
    private WebSocketMessageSender ws;
    private HouseholdEventService events;
    private NotificationService notifications;
    private PlanActivationService service;

    private final List<PlanActivation> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        groupRepo = mock(GroupRepo.class);
        UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
        service = new PlanActivationService(activationRepo, mock(PlanActivationAckRepo.class), userInfoRepo,
                mock(MeetingPlaceRepo.class), mock(EvacuationPlanRepo.class),
                mock(OriginLocationRepo.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencyContactRepo.class), ws = mock(WebSocketMessageSender.class),
                groupRepo, notifications = mock(NotificationService.class),
                mock(HouseholdAccessService.class),
                mock(HouseholdResolver.class), mock(GoBagService.class),
                events = mock(HouseholdEventService.class));

        // The else-branch. See PlanActivationExpiryTest for the argument (T-89).
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        Group household = new Group();
        household.setGroupId(HOUSEHOLD_ID);
        household.setGroupType("Household");
        household.setOwnerEmail(OWNER);
        // The owner appears in BOTH lists, which is how the real rows look and
        // is the input that makes a naive implementation end the same row twice.
        household.setMemberEmails(List.of(OWNER, SPOUSE, TEEN));
        when(groupRepo.findByGroupId(HOUSEHOLD_ID)).thenReturn(Optional.of(household));
        when(groupRepo.findByGroupId("nope")).thenReturn(Optional.empty());
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(household));

        // Reachable members, so a push CAN happen and `times()` means something.
        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(
                List.of(user(OWNER), user(SPOUSE), user(TEEN)));

        when(activationRepo.findActiveByOwnerEmail(anyString(), any(Instant.class)))
                .thenAnswer(inv -> {
                    String owner = inv.getArgument(0);
                    Instant now = inv.getArgument(1);
                    return table.stream()
                            .filter(a -> a.getOwnerEmail().equalsIgnoreCase(owner))
                            .filter(a -> a.getExpiresAt().isAfter(now))
                            .filter(a -> a.getEndedAt() == null)
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

    private static UserInfo user(String email) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("tok-" + email);
        return u;
    }

    private PlanActivation live(String id, String owner, long hoursAgo) {
        PlanActivation a = new PlanActivation();
        a.setId(id);
        a.setOwnerEmail(owner);
        a.setActivatedAt(Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
        a.setExpiresAt(Instant.now().plus(72 - hoursAgo, ChronoUnit.HOURS));
        table.add(a);
        return a;
    }

    // ── THE DEFECT ──────────────────────────────────────────────────────────

    @Test
    void endsEveryLiveActivationInTheHousehold_notJustTheCallersOwn() {
        // Two people launched. Ending only the newest leaves the older one live,
        // and Home's resolver falls straight back onto it.
        PlanActivation mine = live("act-mine", OWNER, 1);
        PlanActivation theirs = live("act-theirs", SPOUSE, 3);

        HouseholdActivationsEndedDto result = service.endHouseholdActivations(HOUSEHOLD_ID, OWNER);

        assertEquals(2, result.endedCount());
        assertNotNull(mine.getEndedAt(), "the caller's own");
        assertNotNull(theirs.getEndedAt(), "AND the co-member's — this is the finding");
        assertTrue(result.activationIds().containsAll(List.of("act-mine", "act-theirs")));
    }

    @Test
    void everyRowCarriesTheSameEndedAt() {
        // All clear is ONE statement about the household. Two rows closed by one
        // tap that disagree by milliseconds would put two times in the timeline
        // for one event.
        PlanActivation mine = live("act-mine", OWNER, 1);
        PlanActivation theirs = live("act-theirs", SPOUSE, 3);

        HouseholdActivationsEndedDto result = service.endHouseholdActivations(HOUSEHOLD_ID, SPOUSE);

        assertEquals(mine.getEndedAt(), theirs.getEndedAt());
        assertEquals(mine.getEndedAt(), result.endedAt());
        assertEquals(SPOUSE, mine.getEndedByEmail());
        assertEquals(SPOUSE, theirs.getEndedByEmail(), "the ender is whoever tapped, on every row");
    }

    @Test
    void theOwnerAppearingInBothListsDoesNotEndTheSameRowTwice() {
        // The fixture's household lists OWNER as both ownerEmail and a member,
        // which is how the real rows look. The candidate emails are collected
        // into a lowercased Set for exactly this — drop the Set and the row is
        // queried twice, ended twice, and announced twice.
        live("act-only", OWNER, 2);

        HouseholdActivationsEndedDto result = service.endHouseholdActivations(HOUSEHOLD_ID, OWNER);

        assertEquals(1, result.endedCount());
        assertEquals(List.of("act-only"), result.activationIds());
        verify(ws, times(1)).sendActivationLifecycle(eq("act-only"), any());
    }

    // ── what leaves the server ──────────────────────────────────────────────

    @Test
    void everyRowBroadcastsItsOwnFrame() {
        // Per-activation, because the topic is /topic/activations/{id}/plan — a
        // recipient watching a shared link holds an id and nothing else.
        live("act-mine", OWNER, 1);
        live("act-theirs", SPOUSE, 3);

        service.endHouseholdActivations(HOUSEHOLD_ID, OWNER);

        verify(ws).sendActivationLifecycle(eq("act-mine"), any());
        verify(ws).sendActivationLifecycle(eq("act-theirs"), any());
        verify(events).recordActivationEnded(HOUSEHOLD_ID, OWNER, "act-mine");
        verify(events).recordActivationEnded(HOUSEHOLD_ID, OWNER, "act-theirs");
    }

    @Test
    void theHouseholdIsPushedOnce_notOncePerActivation() {
        // THE RULING: the household is being told ONE thing. Three members, the
        // ender skipped, so a correct end is 2 deliveries however many rows it
        // closed — four would be the app narrating its own schema.
        live("act-mine", OWNER, 1);
        live("act-theirs", SPOUSE, 3);

        service.endHouseholdActivations(HOUSEHOLD_ID, OWNER);

        verify(notifications, times(2)).deliverPresenceAware(
                anyString(), anyString(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(),
                any(PushPolicyService.Category.class));
    }

    // ── the quiet cases ─────────────────────────────────────────────────────

    @Test
    void aHouseholdWithNothingLiveIsAFreeNoOp() {
        // Double-tapping under stress must not cost the household a second
        // "All clear" push about nothing.
        HouseholdActivationsEndedDto result = service.endHouseholdActivations(HOUSEHOLD_ID, OWNER);

        assertEquals(0, result.endedCount());
        assertTrue(result.activationIds().isEmpty());
        assertNull(result.endedAt(), "nothing ended, so there is no time to report");
        verifyNoInteractions(ws);
        verifyNoInteractions(notifications);
        verifyNoInteractions(events);
    }

    @Test
    void anAlreadyEndedRowIsNotEndedAgain() {
        PlanActivation already = live("act-done", SPOUSE, 4);
        Instant first = Instant.now().minus(10, ChronoUnit.MINUTES);
        already.setEndedAt(first);
        already.setEndedByEmail(SPOUSE);
        live("act-live", OWNER, 1);

        HouseholdActivationsEndedDto result = service.endHouseholdActivations(HOUSEHOLD_ID, OWNER);

        assertEquals(1, result.endedCount());
        assertEquals(first, already.getEndedAt(), "the first ender keeps the attribution");
        assertEquals(SPOUSE, already.getEndedByEmail());
    }

    @Test
    void anExpiredRowIsNotEndedByAllClear() {
        // It is already over by the timer, and the expiry sweep owns that
        // ending. Stamping endedAt here would credit a person with a clock's
        // decision — the same claim BE-7 refuses to make.
        PlanActivation expired = new PlanActivation();
        expired.setId("act-expired");
        expired.setOwnerEmail(TEEN);
        expired.setActivatedAt(Instant.now().minus(80, ChronoUnit.HOURS));
        expired.setExpiresAt(Instant.now().minus(8, ChronoUnit.HOURS));
        table.add(expired);

        assertEquals(0, service.endHouseholdActivations(HOUSEHOLD_ID, OWNER).endedCount());
        assertNull(expired.getEndedAt());
    }

    // ── the household has to exist, and be one ──────────────────────────────

    @Test
    void unknownHouseholdIs404() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.endHouseholdActivations("nope", OWNER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void aNonHouseholdGroupIs404() {
        // The route is keyed on a household id. A neighbourhood circle's id
        // must not end its members' personal evacuations.
        Group circle = new Group();
        circle.setGroupId("grp-neighbourhood");
        circle.setGroupType("Neighborhood");
        circle.setOwnerEmail(OWNER);
        circle.setMemberEmails(List.of(OWNER, SPOUSE));
        when(groupRepo.findByGroupId("grp-neighbourhood")).thenReturn(Optional.of(circle));
        live("act-mine", OWNER, 1);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.endHouseholdActivations("grp-neighbourhood", OWNER));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ── a broken side effect must not cost the write ────────────────────────

    @Test
    void aDeadSocketStillLeavesTheHouseholdStoodDown() {
        PlanActivation mine = live("act-mine", OWNER, 1);
        doThrow(new RuntimeException("broker down"))
                .when(ws).sendActivationLifecycle(anyString(), any());

        assertDoesNotThrow(() -> service.endHouseholdActivations(HOUSEHOLD_ID, OWNER));

        assertNotNull(mine.getEndedAt(), "an all clear that could not be announced is still an all clear");
    }
}
