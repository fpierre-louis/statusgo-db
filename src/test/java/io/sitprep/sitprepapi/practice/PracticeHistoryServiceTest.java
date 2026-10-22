package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PracticeHistoryServiceTest {

    private static final String HH = "hh-1";
    private static final String ADMIN = "owner@x.com";
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private HouseholdAccessService access;
    private GroupRepo groups;
    private ScenarioRunRepo runs;
    private FamilyPracticeCompletionRepo completions;
    private AdminAuditLogService audit;
    private WebSocketMessageSender ws;
    private PracticeHistoryService service;
    private Group household;

    @BeforeEach
    void setUp() {
        access = mock(HouseholdAccessService.class);
        groups = mock(GroupRepo.class);
        runs = mock(ScenarioRunRepo.class);
        completions = mock(FamilyPracticeCompletionRepo.class);
        audit = mock(AdminAuditLogService.class);
        ws = mock(WebSocketMessageSender.class);
        service = new PracticeHistoryService(access, groups, runs, completions, audit, ws,
                Clock.fixed(NOW, ZoneOffset.UTC));

        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setChallengeLastShownWeek("2026-W40");
        Map<String, DrillCompletion> drills = new HashMap<>();
        drills.put("go-bag", new DrillCompletion(NOW, null, "u1"));
        drills.put("exit-drill", new DrillCompletion(NOW, null, "u1"));
        household.setDrillLog(drills);
        household.setChallengeProgress(new HashMap<>(Map.of("2026-W39", true)));
        when(groups.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(runs.deleteAllByHousehold(HH)).thenReturn(3);
        when(completions.deleteAllByHousehold(HH)).thenReturn(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearsEverythingSharedKeepsThePromptWeekAuditsAndPublishes() {
        PracticeHistoryService.ClearResult r = service.clear(ADMIN, HH, true);

        assertThat(r.householdId()).isEqualTo(HH);
        assertThat(r.clearedAt()).isEqualTo(NOW);
        assertThat(r.removed()).isEqualTo(new PracticeHistoryService.Removed(3, 2, 2, 1));
        assertThat(household.getDrillLog()).isEmpty();
        assertThat(household.getChallengeProgress()).isEmpty();
        assertThat(household.getChallengeLastShownWeek()).isEqualTo("2026-W40");
        verify(audit).record(eq(ADMIN), eq("CLEARED_PRACTICE_HISTORY"), eq("group"), eq(HH), anyString());

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(ws).sendHouseholdPractice(eq(HH), frame.capture());
        assertThat((Map<String, Object>) frame.getValue())
                .containsEntry("type", "practice-history-cleared")
                .containsEntry("householdId", HH)
                .containsEntry("clearedAt", NOW.toString());
    }

    @Test
    void aRegularMemberIsRefusedAndNothingIsDeleted() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireCanAdminHousehold("m@x.com", HH);

        assertThatThrownBy(() -> service.clear("m@x.com", HH, true))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(runs, completions, ws, audit);
        assertThat(household.getDrillLog()).hasSize(2);
    }

    @Test
    void withoutConfirmationItIs400AndNothingIsDeleted() {
        assertThatThrownBy(() -> service.clear(ADMIN, HH, false))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(runs, never()).deleteAllByHousehold(any());
        verify(ws, never()).sendHouseholdPractice(any(), any());
    }
}
