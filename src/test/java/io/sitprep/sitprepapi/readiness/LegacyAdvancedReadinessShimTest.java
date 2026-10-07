package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.readiness.LegacyAdvancedReadinessShim.LegacyCompletionDto;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deprecated legacy advanced-readiness routes (EXEC-A1 task 2): only
 * {@code documentVault} writes, and only as {@code documents.first_folder}
 * through the new service and its rules; every other key is a no-op; the
 * response is built from the new table alone.
 */
@SuppressWarnings("removal")
class LegacyAdvancedReadinessShimTest {

    static final String HH = "hh-42";
    static final String ADMIN = "admin@x.com";
    static final String MEMBER = "member@x.com";
    static final String OUTSIDER = "outsider@x.com";
    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    final GroupRepo groupRepo = mock(GroupRepo.class);
    final HouseholdAccessService access = mock(HouseholdAccessService.class);
    final HouseholdReadinessItemStateRepo stateRepo = mock(HouseholdReadinessItemStateRepo.class);
    final List<HouseholdReadinessItemState> rows = new ArrayList<>();
    final AtomicLong ids = new AtomicLong();

    LegacyAdvancedReadinessShim shim;

    @BeforeEach
    void setUp() {
        Group household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setDrillLog(new HashMap<>());
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(household));
        Group circle = new Group();
        circle.setGroupId("circle-1");
        circle.setGroupType("Neighborhood");
        when(groupRepo.findByGroupId("circle-1")).thenReturn(Optional.of(circle));

        when(access.canWriteHousehold(ADMIN, HH)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"))
                .when(access).requireCanAdminHousehold(eq(MEMBER), eq(HH));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "not shared"))
                .when(access).requireCanReadHousehold(eq(OUTSIDER), eq(HH));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"))
                .when(access).requireCanAdminHousehold(eq(OUTSIDER), eq(HH));

        // In-memory household_readiness_item_state.
        when(stateRepo.findByHouseholdId(HH)).thenAnswer(inv -> List.copyOf(rows));
        when(stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(r -> r.getHouseholdId().equals(inv.getArgument(0))
                                && r.getItemKey().equals(inv.getArgument(1))
                                && r.getScope().equals(inv.getArgument(2)))
                        .findFirst());
        when(stateRepo.save(any(HouseholdReadinessItemState.class))).thenAnswer(inv -> {
            HouseholdReadinessItemState r = inv.getArgument(0);
            if (r.getId() == null) { r.setId(ids.incrementAndGet()); rows.add(r); }
            return r;
        });
        doAnswer(inv -> { rows.remove((HouseholdReadinessItemState) inv.getArgument(0)); return null; })
                .when(stateRepo).delete(any(HouseholdReadinessItemState.class));

        EssentialsReadinessService essentials = mock(EssentialsReadinessService.class);
        when(essentials.evaluate(any(), anyString(), anyBoolean())).thenReturn(new EssentialsResult(true, true, true, true));
        ReadinessJourneyService service = new ReadinessJourneyService(
                groupRepo, access, essentials, new ActiveResponseResolver(mock(PlanActivationRepo.class)),
                new ReadinessRecommendationService(), mock(RiskProfileService.class), mock(HomeStockpileService.class),
                mock(EvacuationAdvancedService.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencySupportProfileRepo.class), mock(HouseholdPetRepo.class), mock(DemographicRepo.class),
                stateRepo, Clock.fixed(NOW, ZoneOffset.UTC));
        shim = new LegacyAdvancedReadinessShim(service, stateRepo, groupRepo, access);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }

    @Test
    void documentVaultMarksTheFolderDoneAndReadsItBack() {
        as(ADMIN);
        Map<String, LegacyCompletionDto> body = shim.markComplete(HH, "documentVault").getBody();

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getItemKey()).isEqualTo("documents.first_folder");
            assertThat(r.getScope()).isEqualTo("HOUSEHOLD");
            assertThat(r.getState()).isEqualTo(ItemStateKind.DONE);
            assertThat(r.getReasonCode()).isEqualTo("legacy_document_vault");
        });
        assertThat(body).containsOnlyKeys("documentVault");
        assertThat(body.get("documentVault").completedAt()).isEqualTo(NOW);
        assertThat(body.get("documentVault").completedBy()).isNull();

        body = shim.clear(HH, "documentVault").getBody();
        assertThat(rows).isEmpty();
        assertThat(body).isEmpty();
    }

    @Test
    void documentVaultFollowsTheNewRules_aMemberCannotCompleteTheFolder() {
        as(MEMBER);
        assertStatus(() -> shim.markComplete(HH, "documentVault"), HttpStatus.FORBIDDEN);
        assertThat(rows).isEmpty();

        // An admin's DONE (from the new UI) is what an old build sees; a member can't clear it.
        as(ADMIN);
        shim.markComplete(HH, "documentVault");
        as(MEMBER);
        assertStatus(() -> shim.clear(HH, "documentVault"), HttpStatus.FORBIDDEN);
        assertThat(rows).hasSize(1);
    }

    @Test
    void everyOtherLegacyKeyIsANoOpThatReportsTheTruth() {
        as(ADMIN);
        for (String key : List.of("medicalStockpile", "quarterlyDrill", "outOfTownContact", "petEvacuation",
                "contactTreeTest", "extendedFamilyContacts", "neighborCoordination")) {
            assertThat(shim.markComplete(HH, key).getBody()).as(key).isEmpty();
            assertThat(shim.clear(HH, key).getBody()).as(key).isEmpty();
        }
        verify(stateRepo, never()).save(any());

        // With the folder done (however it got there), every response carries it — and only it.
        shim.markComplete(HH, "documentVault");
        assertThat(shim.markComplete(HH, "medicalStockpile").getBody()).containsOnlyKeys("documentVault");
        as(MEMBER);
        assertThat(shim.clear(HH, "quarterlyDrill").getBody()).containsOnlyKeys("documentVault");
    }

    @Test
    void notRelevantIsNotDone_soTheMapStaysEmpty() {
        as(ADMIN);
        HouseholdReadinessItemState nr = new HouseholdReadinessItemState();
        nr.setId(99L);
        nr.setHouseholdId(HH);
        nr.setItemKey("documents.first_folder");
        nr.setScope("HOUSEHOLD");
        nr.setState(ItemStateKind.NOT_RELEVANT);
        nr.setCreatedBy(ADMIN);
        rows.add(nr);
        assertThat(shim.markComplete(HH, "quarterlyDrill").getBody()).isEmpty();
    }

    @Test
    void outsidersAnonymousBadKeysAndNonHouseholdsAreRefused() {
        as(OUTSIDER);
        assertStatus(() -> shim.markComplete(HH, "documentVault"), HttpStatus.FORBIDDEN);
        assertStatus(() -> shim.markComplete(HH, "medicalStockpile"), HttpStatus.FORBIDDEN);
        assertStatus(() -> shim.clear(HH, "quarterlyDrill"), HttpStatus.FORBIDDEN);
        as(ADMIN);
        assertStatus(() -> shim.markComplete(HH, "bad key!"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> shim.markComplete("circle-1", "medicalStockpile"), HttpStatus.NOT_FOUND);
        assertStatus(() -> shim.markComplete("circle-1", "documentVault"), HttpStatus.NOT_FOUND);
        SecurityContextHolder.clearContext();
        assertStatus(() -> shim.markComplete(HH, "documentVault"), HttpStatus.UNAUTHORIZED);
        assertThat(rows).isEmpty();
    }
}
