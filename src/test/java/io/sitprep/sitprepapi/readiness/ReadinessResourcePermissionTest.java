package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.SetItemStateRequest;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.service.EvacuationAdvancedService;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.RiskProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The readiness item-state endpoints refuse a member's write on an
 * admin-only step at the resource, and refuse it BEFORE anything is written
 * (EXEC-A1 task 4). Same shape as HouseholdMembershipGateTest: no Spring
 * context, a stubbed SecurityContext, the real resource over the real service,
 * with household access and the repositories mocked.
 */
class ReadinessResourcePermissionTest {

    static final String HH = "hh-42";
    static final String MEMBER = "member@x.com";
    static final String ADMIN = "admin@x.com";
    static final String OUTSIDER = "outsider@x.com";

    final GroupRepo groupRepo = mock(GroupRepo.class);
    final HouseholdAccessService access = mock(HouseholdAccessService.class);
    final EssentialsReadinessService essentials = mock(EssentialsReadinessService.class);
    final HouseholdReadinessItemStateRepo stateRepo = mock(HouseholdReadinessItemStateRepo.class);
    final RiskProfileService riskService = mock(RiskProfileService.class);

    ReadinessResource resource;

    @BeforeEach
    void setUp() {
        Group household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setDrillLog(new HashMap<>());
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(essentials.evaluate(any(), anyString(), anyBoolean()))
                .thenReturn(new EssentialsResult(true, true, true, true));
        when(stateRepo.findByHouseholdId(HH)).thenReturn(List.of());
        when(stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Real access rules: members read, only admins administer, outsiders neither.
        when(access.canWriteHousehold(ADMIN, HH)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"))
                .when(access).requireCanAdminHousehold(eq(MEMBER), eq(HH));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "This household's plan is not shared with you"))
                .when(access).requireCanReadHousehold(eq(OUTSIDER), eq(HH));

        ReadinessJourneyService service = new ReadinessJourneyService(
                groupRepo, access, essentials, new ActiveResponseResolver(mock(PlanActivationRepo.class)),
                new ReadinessRecommendationService(), riskService, mock(HomeStockpileService.class),
                mock(EvacuationAdvancedService.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencySupportProfileRepo.class), mock(HouseholdPetRepo.class),
                mock(DemographicRepo.class), stateRepo,
                Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC));
        resource = new ReadinessResource(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    private static SetItemStateRequest done() {
        return new SetItemStateRequest("DONE", null, null);
    }

    @Test
    void memberCannotMarkAnAdminOnlyStepDone_andNothingIsWritten() {
        authenticateAs(MEMBER);
        assertStatus(() -> resource.setState(HH, "documents.first_folder", done()), HttpStatus.FORBIDDEN);
        assertStatus(() -> resource.setState(HH, "outage.warm_cool_place", done()), HttpStatus.FORBIDDEN);
        verify(stateRepo, never()).save(any());
    }

    @Test
    void memberCannotUndoAnAdminOnlyStep_andNothingIsDeleted() {
        authenticateAs(MEMBER);
        assertStatus(() -> resource.clearState(HH, "documents.first_folder", "DONE"), HttpStatus.FORBIDDEN);
        verify(stateRepo, never()).delete(any());
    }

    @Test
    void memberCanMarkAMemberSafeStepDone() {
        authenticateAs(MEMBER);
        assertThat(resource.setState(HH, "outage.flashlight_bed", done()).getStatusCode().value()).isEqualTo(200);
        verify(stateRepo, times(1)).save(any());
    }

    @Test
    void adminCanMarkAnAdminOnlyStepDone() {
        authenticateAs(ADMIN);
        assertThat(resource.setState(HH, "documents.first_folder", done()).getStatusCode().value()).isEqualTo(200);
        verify(stateRepo, times(1)).save(any());
    }

    @Test
    void memberCannotMarkNotRelevantOrCompleteARealDataStep() {
        authenticateAs(MEMBER);
        assertStatus(() -> resource.setState(HH, "outage.flashlight_bed",
                new SetItemStateRequest("NOT_RELEVANT", null, null)), HttpStatus.FORBIDDEN);
        assertStatus(() -> resource.setState(HH, "outage.co_detector", done()), HttpStatus.CONFLICT);
        verify(stateRepo, never()).save(any());
    }

    @Test
    void outsiderAndAnonymousAreRefusedBeforeAnyWork() {
        authenticateAs(OUTSIDER);
        assertStatus(() -> resource.setState(HH, "outage.flashlight_bed", done()), HttpStatus.FORBIDDEN);
        SecurityContextHolder.clearContext();
        assertStatus(() -> resource.setState(HH, "outage.flashlight_bed", done()), HttpStatus.UNAUTHORIZED);
        verify(stateRepo, never()).save(any());
        verify(riskService, never()).resolveFor(any());
    }
}
