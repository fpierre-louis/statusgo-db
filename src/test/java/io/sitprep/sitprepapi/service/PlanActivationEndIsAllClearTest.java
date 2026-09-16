package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationDetailDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Ending ONE activation is an All clear for the household (2026-09-15).
 *
 * <p>The orphaning BE-2 closed survived on exactly one surface.
 * {@code /deployedplan}'s End button called the per-row route, so in a
 * household where two people had launched, ending the row on screen let
 * {@code MeService.resolveActiveActivationIdForHome} fall straight back onto
 * the sibling — Home stayed EVACUATING and the person who had just declared it
 * over watched it come back.</p>
 *
 * <p>The client could not fix it: the household route needs a household id,
 * {@code /deployedplan} has none, and the VIEWER'S base household is the wrong
 * answer for anyone who belongs to two. So the activation names its own
 * household (V79) and the server resolves it from the row it was handed.</p>
 */
class PlanActivationEndIsAllClearTest {

    private static final String HOUSEHOLD_ID = "hh-1";
    private static final String OWNER = "owner@x.com";
    private static final String SPOUSE = "spouse@x.com";

    private PlanActivationRepo activationRepo;
    private UserInfoRepo userInfoRepo;
    private GroupService groupService;
    private PlanActivationService service;

    private final List<PlanActivation> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        HouseholdAccessService householdAccess = mock(HouseholdAccessService.class);

        service = new PlanActivationService(activationRepo, mock(PlanActivationAckRepo.class), userInfoRepo,
                mock(MeetingPlaceRepo.class), mock(EvacuationPlanRepo.class),
                mock(OriginLocationRepo.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencyContactRepo.class), mock(WebSocketMessageSender.class),
                groupRepo, mock(NotificationService.class),
                householdAccess,
                mock(HouseholdResolver.class), mock(GoBagService.class),
                mock(HouseholdEventService.class),
                groupService = mock(GroupService.class), mock(ActivationDirectiveResolver.class));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        Group household = new Group();
        household.setGroupId(HOUSEHOLD_ID);
        household.setGroupType("Household");
        household.setOwnerEmail(OWNER);
        household.setMemberEmails(List.of(OWNER, SPOUSE));
        when(groupRepo.findByGroupId(HOUSEHOLD_ID)).thenReturn(Optional.of(household));
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(household));

        // Any household member may end — the server-computed `viewerCanEnd`.
        when(householdAccess.canReadPlanDataFor(anyString(), anyString())).thenReturn(true);

        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of());

        when(activationRepo.findById(anyString())).thenAnswer(inv ->
                table.stream().filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
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
        when(activationRepo.findLiveByHouseholdId(anyString(), any(Instant.class)))
                .thenAnswer(inv -> {
                    String hh = inv.getArgument(0);
                    Instant now = inv.getArgument(1);
                    return table.stream()
                            .filter(a -> hh.equals(a.getHouseholdId()))
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

    private PlanActivation row(String id, String owner, long hoursAgo, String householdId) {
        PlanActivation a = new PlanActivation();
        a.setId(id);
        a.setOwnerEmail(owner);
        a.setHouseholdId(householdId);
        a.setActivatedAt(Instant.now().minus(hoursAgo, ChronoUnit.HOURS));
        a.setExpiresAt(Instant.now().plus(72 - hoursAgo, ChronoUnit.HOURS));
        table.add(a);
        return a;
    }

    // ── THE DEFECT ──────────────────────────────────────────────────────────

    @Test
    void endingOneRowEndsTheSiblingTheHomeResolverWouldFallBackOnto() {
        PlanActivation onScreen = row("act-mine", OWNER, 1, HOUSEHOLD_ID);
        PlanActivation sibling  = row("act-theirs", SPOUSE, 3, HOUSEHOLD_ID);

        ActivationDetailDto detail = service.endActivation("act-mine", OWNER);

        assertNotNull(onScreen.getEndedAt(), "the row the button was on");
        assertNotNull(sibling.getEndedAt(),
                "AND the sibling — without this Home falls back onto it and stays EVACUATING");
        assertNotNull(detail.endedAt(),
                "the response must carry endedAt, or the page re-renders as still running");
    }

    @Test
    void itAlsoStandsTheCheckInDown() {
        row("act-mine", OWNER, 1, HOUSEHOLD_ID);

        service.endActivation("act-mine", OWNER);

        // All clear is ONE statement about the household. The confirm copy on
        // /deployedplan now says so.
        verify(groupService).setAlert(eq(HOUSEHOLD_ID), eq(false), eq(OWNER));
    }

    // ── THE GUARD THAT MATTERS MOST ─────────────────────────────────────────

    @Test
    void aStaleForwardedLinkCannotStandDownThisWeeksEvacuation() {
        // Last week's activation, already ended. A link outlives the emergency
        // it describes, and it gets forwarded, screenshotted and pasted into
        // group chats.
        PlanActivation old = row("act-old", OWNER, 200, HOUSEHOLD_ID);
        old.setEndedAt(Instant.now().minus(100, ChronoUnit.HOURS));
        old.setExpiresAt(Instant.now().minus(128, ChronoUnit.HOURS));
        // Today's, live.
        PlanActivation today = row("act-today", SPOUSE, 1, HOUSEHOLD_ID);

        service.endActivation("act-old", OWNER);

        assertNull(today.getEndedAt(),
                "ending a CLOSED activation must not touch a live one — a forwarded link "
                + "must not be able to call an all clear on an emergency that is still running");
        verify(groupService, never()).setAlert(anyString(), anyBoolean(), anyString());
    }

    @Test
    void idempotent_endingAnAlreadyEndedRowDoesNotMoveItsEndedAt() {
        PlanActivation a = row("act-done", OWNER, 5, HOUSEHOLD_ID);
        Instant originally = Instant.now().minus(2, ChronoUnit.HOURS);
        a.setEndedAt(originally);

        service.endActivation("act-done", OWNER);

        assertEquals(originally, a.getEndedAt(),
                "two members tapping End must not produce two 'over at' times for one event");
    }

    // ── PRE-V79 ROWS ────────────────────────────────────────────────────────

    @Test
    void aRowWithNoHouseholdIdFallsBackToTheLaunchersBaseHousehold() {
        PlanActivation legacy  = row("act-legacy", OWNER, 1, null);
        PlanActivation sibling = row("act-sibling", SPOUSE, 3, HOUSEHOLD_ID);

        UserInfo u = new UserInfo();
        u.setUserEmail(OWNER);
        u.setBaseHouseholdId(HOUSEHOLD_ID);
        when(userInfoRepo.findByUserEmailIgnoreCase(OWNER)).thenReturn(Optional.of(u));

        service.endActivation("act-legacy", OWNER);

        assertNotNull(legacy.getEndedAt());
        assertNotNull(sibling.getEndedAt(),
                "a pre-V79 row still resolves a household, via the launcher's base");
    }

    @Test
    void noResolvableHousehold_stillEndsTheOneRowRatherThanRefusing() {
        // A launcher who had no household when they activated and still has
        // none. Closing the row it was handed is strictly better than a 500,
        // and is what shipped before householdId existed.
        PlanActivation orphan = row("act-orphan", "nobody@x.com", 1, null);

        service.endActivation("act-orphan", "nobody@x.com");

        assertNotNull(orphan.getEndedAt());
        verify(groupService, never()).setAlert(anyString(), anyBoolean(), anyString());
    }
}
