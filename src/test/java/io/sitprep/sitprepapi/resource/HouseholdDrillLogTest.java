package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.MeDto.DrillCompletionDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Per-drill, dated completion (2026-09-01).
 *
 * <p>Before this, the only record was a {@code Map<weekKey, Boolean>} — the app
 * knew a drill was done that week and not which one, so "when did we last do
 * this drill" had no answer and the dashboard's "Drills done · N" counted
 * weeks.</p>
 *
 * <p>The assertion worth not "simplifying" is {@code repeatCompleteMovesTheDate}:
 * the sibling advanced-readiness route uses {@code putIfAbsent} and this one
 * must not, because a drill is a thing you do again and keeping the first date
 * would show a household as overdue on a drill it ran yesterday.</p>
 */
class HouseholdDrillLogTest {

    private static final String HH = "hh-1";
    private static final String MEMBER = "member@x.com";
    private static final String OUTSIDER = "outsider@x.com";

    private GroupRepo groupRepo;
    private HouseholdAccessService access;
    private HouseholdChallengesResource resource;
    private Group household;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        access = mock(HouseholdAccessService.class);
        resource = new HouseholdChallengesResource(groupRepo, access);

        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(groupRepo.save(any(Group.class))).thenAnswer(inv -> inv.getArgument(0));
        signIn(MEMBER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a", java.util.List.of()));
    }

    private Map<String, DrillCompletionDto> complete(String key) {
        ResponseEntity<Map<String, DrillCompletionDto>> res = resource.markDrillComplete(HH, key);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        return res.getBody();
    }

    // ── the record ──────────────────────────────────────────────────────────

    @Test
    void completeRecordsWhenAndWho() {
        Map<String, DrillCompletionDto> log = complete("smoke-alarms");

        assertTrue(log.containsKey("smoke-alarms"));
        assertNotNull(log.get("smoke-alarms").completedAt());
        assertEquals(MEMBER, log.get("smoke-alarms").completedBy());
        assertEquals(1, household.getDrillLog().size());
    }

    @Test
    void repeatCompleteMovesTheDate() throws Exception {
        // THE POINT OF THE WHOLE TABLE. The sibling advanced-readiness route
        // uses putIfAbsent; this one must not. A drill is a thing you do again,
        // and a frozen first date would make the second practice invisible.
        complete("exit-drill");
        Instant first = household.getDrillLog().get("exit-drill").getCompletedAt();
        Thread.sleep(5);
        complete("exit-drill");
        Instant second = household.getDrillLog().get("exit-drill").getCompletedAt();

        assertTrue(second.isAfter(first), "a repeat practice must move the date forward");
    }

    @Test
    void phasesAreSeparateRowsWithSeparateDates() {
        // A household can pack the documents on a different evening from the
        // water, and each part carries its own date.
        complete("go-bag#bag");
        complete("go-bag#papers");

        assertEquals(2, household.getDrillLog().size());
        assertTrue(household.getDrillLog().containsKey("go-bag#bag"));
        assertTrue(household.getDrillLog().containsKey("go-bag#papers"));
    }

    // ── undo ────────────────────────────────────────────────────────────────

    @Test
    void clearRemovesTheRowEntirely() {
        complete("smoke-alarms");

        Map<String, DrillCompletionDto> log = resource.clearDrill(HH, "smoke-alarms").getBody();

        assertFalse(log.containsKey("smoke-alarms"));
        assertTrue(household.getDrillLog().isEmpty(), "the row goes, rather than losing its date");
    }

    @Test
    void clearingSomethingNotThereIs200NotAnError() {
        // The caller's intent — "this should not be marked done" — is already
        // satisfied, so an error would be the API arguing about bookkeeping.
        ResponseEntity<Map<String, DrillCompletionDto>> res = resource.clearDrill(HH, "never-done");

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().isEmpty());
        verify(groupRepo, never()).save(any(Group.class));
    }

    // ── the wire ────────────────────────────────────────────────────────────

    @Test
    void theResponseIsACopy_notTheManagedCollection() {
        complete("smoke-alarms");
        Map<String, DrillCompletionDto> log = resource.clearDrill(HH, "nope").getBody();

        log.clear();   // mutating the response must not touch the entity
        assertEquals(1, household.getDrillLog().size());
    }

    @Test
    void aRowWithNoTimestampIsNotShippedAtAll() {
        // A row that says a drill was done and cannot say when is worse on a
        // "when did we last do this" surface than no row.
        Map<String, DrillCompletion> log = new HashMap<>();
        log.put("broken", new DrillCompletion(null, MEMBER));
        log.put("good", new DrillCompletion(Instant.now(), MEMBER));
        household.setDrillLog(log);

        Map<String, DrillCompletionDto> out = resource.clearDrill(HH, "absent").getBody();

        assertTrue(out.containsKey("good"));
        assertFalse(out.containsKey("broken"));
    }

    // ── auth + validation ───────────────────────────────────────────────────

    @Test
    void anyMemberMayReportADrill_notJustAnAdmin() {
        // Deliberately membership, not admin: the household is collective, and
        // this matches the weekly challenge rather than advanced readiness.
        complete("smoke-alarms");

        verify(access).requireCanReadHousehold(MEMBER, HH);
        verify(access, never()).requireCanAdminHousehold(anyString(), anyString());
    }

    @Test
    void anOutsiderIsRefused() {
        signIn(OUTSIDER);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanReadHousehold(OUTSIDER, HH);

        assertThrows(ResponseStatusException.class, () -> resource.markDrillComplete(HH, "smoke-alarms"));
        assertTrue(household.getDrillLog().isEmpty());
    }

    @Test
    void aMalformedDrillKeyIs400_andIsRejectedBeforeTheAuthLookup() {
        for (String bad : new String[]{
                "-leading-hyphen", "has space", "has/slash", "two#hash#es", "", "#justphase",
                "x".repeat(97),
        }) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> resource.markDrillComplete(HH, bad), "should reject: " + bad);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), "for: " + bad);
        }
        assertTrue(household.getDrillLog().isEmpty());
    }

    @Test
    void realCatalogKeysAreAccepted() {
        for (String ok : new String[]{
                "go-bag", "go-bag#papers", "smoke-alarms", "poweroutage-fridge-first-menu",
                "earthquake-drop-cover-relay", "hurricane-evac-pack-draft#rehearse",
        }) {
            assertDoesNotThrow(() -> resource.markDrillComplete(HH, ok), "should accept: " + ok);
        }
        assertEquals(6, household.getDrillLog().size());
    }

    @Test
    void aKeyThatFitsTheRegexAlsoFitsTheColumn() {
        // 96 is the column width. A key that passed the regex and overflowed
        // the column would fail at flush with a message about nothing.
        String longest = "a".repeat(63) + "#" + "b".repeat(32);
        assertEquals(96, longest.length());
        assertDoesNotThrow(() -> resource.markDrillComplete(HH, longest));
    }
}
