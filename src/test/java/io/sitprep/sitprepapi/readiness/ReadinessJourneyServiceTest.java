package io.sitprep.sitprepapi.readiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EmergencyContactType;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdPet;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto.EvacMetricDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileCategoryDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.StockpileItemDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskAdjustedRequirementDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.AreaDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ItemDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ReadinessJourneyDto;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The readiness journey end to end over mocked domain truth: applicability →
 * completion → freshness → overlays → counts → gates → mutations
 * (CONTRACT.md §3, §6, §7). The item-state repo is an in-memory fake so the
 * one-row-per-scope rule (V98 partial unique indexes, which H2 cannot build)
 * is asserted on the rows the service actually writes.
 */
class ReadinessJourneyServiceTest {

    static final String HH = "hh-1";
    static final String ME = "member@example.com";
    static final String ADMIN = "admin@example.com";
    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    final GroupRepo groupRepo = mock(GroupRepo.class);
    final HouseholdAccessService access = mock(HouseholdAccessService.class);
    final EssentialsReadinessService essentials = mock(EssentialsReadinessService.class);
    final PlanActivationRepo activations = mock(PlanActivationRepo.class);
    final RiskProfileService riskService = mock(RiskProfileService.class);
    final HomeStockpileService stockpile = mock(HomeStockpileService.class);
    final EvacuationAdvancedService evac = mock(EvacuationAdvancedService.class);
    final EmergencyContactGroupRepo contactGroups = mock(EmergencyContactGroupRepo.class);
    final EmergencySupportProfileRepo supportProfiles = mock(EmergencySupportProfileRepo.class);
    final HouseholdPetRepo pets = mock(HouseholdPetRepo.class);
    final DemographicRepo demographics = mock(DemographicRepo.class);
    final HouseholdReadinessItemStateRepo stateRepo = mock(HouseholdReadinessItemStateRepo.class);

    final ReadinessJourneyService service = new ReadinessJourneyService(
            groupRepo, access, essentials, new ActiveResponseResolver(activations),
            new ReadinessRecommendationService(), riskService, stockpile, evac,
            contactGroups, supportProfiles, pets, demographics, stateRepo,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** In-memory stand-in for household_readiness_item_state. */
    final List<HouseholdReadinessItemState> rows = new ArrayList<>();
    final AtomicLong ids = new AtomicLong();

    Group household;
    RiskProfileDto risk;
    Map<String, Boolean> stockpileItems;
    int stockpilePercent;
    Map<String, Boolean> evacMetrics;

    @BeforeEach
    void setUp() {
        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setDrillLog(new HashMap<>());
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(access.canWriteHousehold(ADMIN, HH)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"))
                .when(access).requireCanAdminHousehold(ME, HH);

        when(essentials.isRequesterBase(anyString(), anyString())).thenReturn(false);
        when(essentials.evaluate(any(), anyString(), anyBoolean()))
                .thenReturn(new EssentialsResult(true, true, true, true));
        when(activations.findLiveForHousehold(any(), any())).thenReturn(List.of());

        risk = new RiskProfileDto("household_zip", "CO", "Colorado", List.of(), List.of(), List.of(), NOW, "v");
        when(riskService.resolveFor(any())).thenAnswer(inv -> risk);

        stockpileItems = new HashMap<>();
        stockpilePercent = 0;
        when(stockpile.getForHousehold(HH)).thenAnswer(inv -> kit());
        evacMetrics = new HashMap<>();
        when(evac.getForHousehold(HH)).thenAnswer(inv -> evacDto());

        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of());
        when(supportProfiles.findByHouseholdId(HH)).thenReturn(List.of());
        when(pets.findByHouseholdIdOrderByCreatedAtAsc(HH)).thenReturn(List.of());
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.empty());

        when(stateRepo.findByHouseholdId(HH)).thenAnswer(inv -> List.copyOf(rows));
        when(stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(r -> r.getHouseholdId().equals(inv.getArgument(0))
                                && r.getItemKey().equals(inv.getArgument(1))
                                && r.getScope().equals(inv.getArgument(2)))
                        .findFirst());
        when(stateRepo.findFirstByHouseholdIdAndItemKeyAndScopeAndUserEmail(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(r -> r.getHouseholdId().equals(inv.getArgument(0))
                                && r.getItemKey().equals(inv.getArgument(1))
                                && r.getScope().equals(inv.getArgument(2))
                                && Objects.equals(r.getUserEmail(), inv.getArgument(3)))
                        .findFirst());
        when(stateRepo.save(any(HouseholdReadinessItemState.class))).thenAnswer(inv -> {
            HouseholdReadinessItemState r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(ids.incrementAndGet());
                rows.add(r);
            }
            return r;
        });
        doAnswer(inv -> { rows.remove((HouseholdReadinessItemState) inv.getArgument(0)); return null; })
                .when(stateRepo).delete(any(HouseholdReadinessItemState.class));
    }

    HomeStockpileDto kit() {
        List<StockpileItemDto> items = new ArrayList<>();
        stockpileItems.forEach((k, v) -> items.add(new StockpileItemDto(k, k, 1, null, 0, null, v, null, null, null)));
        return new HomeStockpileDto("stay_home_14_day", "ADVANCED", "t", "s", 14, 2, stockpilePercent, false,
                List.of(new StockpileCategoryDto("power_heat", "Power", "b", true, false, null, null, items)),
                NOW, "v", false, null);
    }

    EvacuationAdvancedDto evacDto() {
        List<EvacMetricDto> metrics = new ArrayList<>();
        evacMetrics.forEach((k, v) -> metrics.add(new EvacMetricDto(k, k, k, v, null, "/evacuation-wizard")));
        return new EvacuationAdvancedDto("evacuation_advanced", "ADVANCED", "t", "s", 0, true, metrics, NOW);
    }

    HouseholdReadinessItemState row(String key, String scope, String email, ItemStateKind state, Instant updatedAt) {
        HouseholdReadinessItemState r = new HouseholdReadinessItemState();
        r.setId(ids.incrementAndGet());
        r.setHouseholdId(HH);
        r.setItemKey(key);
        r.setScope(scope);
        r.setUserEmail(email);
        r.setState(state);
        r.setCreatedBy(email == null ? ADMIN : email);
        r.setCreatedAt(updatedAt);
        r.setUpdatedAt(updatedAt);
        rows.add(r);
        return r;
    }

    static EmergencyContactGroup contacts(EmergencyContact... cs) {
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setName("Family");
        g.setContacts(new ArrayList<>(List.of(cs)));
        return g;
    }

    static EmergencyContact contact(String role, EmergencyContactType type) {
        EmergencyContact c = new EmergencyContact();
        c.setRole(role);
        c.setContactType(type);
        return c;
    }

    static Optional<ItemDto> find(ReadinessJourneyDto j, String key) {
        return j.areas().stream().flatMap(a -> a.items().stream()).filter(i -> i.key().equals(key)).findFirst();
    }

    static ItemDto item(ReadinessJourneyDto j, String key) {
        return find(j, key).orElseThrow(() -> new AssertionError("missing " + key));
    }

    static AreaDto area(ReadinessJourneyDto j, ReadinessArea a) {
        return j.areas().stream().filter(x -> x.key() == a).findFirst().orElseThrow();
    }

    ReadinessJourneyDto journey() { return service.getJourney(HH, ME); }

    // ------------------------------------------------------------------ shape

    @Test
    void dtoCarriesVersionsAndAllAreasInOrder() {
        ReadinessJourneyDto j = journey();
        assertThat(j.schemaVersion()).isEqualTo(1);
        assertThat(j.catalogVersion()).isEqualTo("readiness-catalog-2026.10.07");
        assertThat(j.recommendationVersion()).isEqualTo("readiness-rec-2026.10.07");
        assertThat(j.householdId()).isEqualTo(HH);
        assertThat(j.generatedAt()).isEqualTo(NOW);
        assertThat(j.mode()).isEqualTo(JourneyMode.CALM);
        assertThat(j.areas()).extracting(AreaDto::key).containsExactly(ReadinessArea.values());
        assertThat(area(j, ReadinessArea.DOCUMENTS).title()).isEqualTo("Documents");
        assertThat(area(j, ReadinessArea.OUTAGE).title()).isEqualTo("Outage Ready");
        assertThat(j.tools()).hasSize(4);
    }

    @Test
    void jsonNeverContainsAScore() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = mapper.writeValueAsString(journey());
        assertThat(json).doesNotContainIgnoringCase("score");
        assertThat(json).doesNotContain("percent");
        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"canMarkDone\"");
    }

    @Test
    void riskProfileIsResolvedOncePerRequest() {
        journey();
        verify(riskService, times(1)).resolveFor(any());
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("DONE", null, null), ME);
        verify(riskService, times(2)).resolveFor(any());
    }

    @Test
    void nonHouseholdGroupIs404AndAccessIsChecked() {
        Group circle = new Group();
        circle.setGroupId("g-2");
        circle.setGroupType("Neighborhood");
        when(groupRepo.findByGroupId("g-2")).thenReturn(Optional.of(circle));
        assertThatThrownBy(() -> service.getJourney("g-2", ME))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).requireCanReadHousehold("x@example.com", HH);
        assertThatThrownBy(() -> service.getJourney(HH, "x@example.com"))
                .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ applicability

    @Test
    void petPlanAppearsOnlyWithPets() {
        assertThat(find(journey(), "evacuation.pet_plan")).isEmpty();

        Demographic d = new Demographic();
        d.setCats(1);
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.of(d));
        assertThat(find(journey(), "evacuation.pet_plan")).isPresent();

        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.empty());
        when(pets.findByHouseholdIdOrderByCreatedAtAsc(HH)).thenReturn(List.of(new HouseholdPet()));
        assertThat(find(journey(), "evacuation.pet_plan")).isPresent();
    }

    @Test
    void medicalBackupAppearsOnlyWithPowerOrColdMedicineNeeds() {
        assertThat(find(journey(), "outage.medical_backup")).isEmpty();
        EmergencySupportProfile p = new EmergencySupportProfile();
        p.setRefrigeratedMedication(true);
        when(supportProfiles.findByHouseholdId(HH)).thenReturn(List.of(p));
        ReadinessJourneyDto j = journey();
        assertThat(find(j, "outage.medical_backup")).isPresent();
        assertThat(j.nextStep().itemKey()).isEqualTo("outage.medical_backup");
        assertThat(j.nextStep().reason().code()).isEqualTo("HOUSEHOLD_RELEVANCE");
    }

    // ------------------------------------------------------------------ completion

    @Test
    void contactsCompleteFromHouseholdContacts() {
        EmergencyContact petVet = contact("Other", EmergencyContactType.OTHER);
        petVet.setMedicalInfo("Dr. Lee, Vet clinic");
        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of(contacts(
                contact("Cousin", EmergencyContactType.OUT_OF_AREA),
                contact("neighbor", EmergencyContactType.LOCAL),
                contact("Friend", EmergencyContactType.LOCAL),
                petVet)));
        Demographic d = new Demographic();
        d.setDogs(1);
        when(demographics.findFirstByHouseholdIdOrderByIdDesc(HH)).thenReturn(Optional.of(d));

        ReadinessJourneyDto j = journey();
        assertThat(item(j, "people.out_of_area_contact").completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(item(j, "people.neighbor").completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(item(j, "people.extended_family").completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(item(j, "evacuation.pet_plan").completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(area(j, ReadinessArea.PEOPLE).done()).isEqualTo(3);
    }

    @Test
    void outOfAreaRoleStringAlsoCounts() {
        when(contactGroups.findByHouseholdId(HH)).thenReturn(List.of(contacts(contact("out-of-area", null))));
        assertThat(item(journey(), "people.out_of_area_contact").completion()).isEqualTo(CompletionState.COMPLETE);
    }

    @Test
    void ownerContactsAreAFallbackOnlyForTheRequestersBase() {
        when(contactGroups.findByOwnerEmailIgnoreCase(ME)).thenReturn(List.of(contacts(contact("Neighbor", null))));
        assertThat(item(journey(), "people.neighbor").completion()).isEqualTo(CompletionState.INCOMPLETE);
        when(essentials.isRequesterBase(HH, ME)).thenReturn(true);
        assertThat(item(journey(), "people.neighbor").completion()).isEqualTo(CompletionState.COMPLETE);
    }

    @Test
    void stockpileNeedsEveryMappedItem() {
        stockpileItems.put("stockpile-flashlights", true);
        stockpileItems.put("stockpile-batteries", false);
        assertThat(item(journey(), "supplies.light_batteries").completion()).isEqualTo(CompletionState.INCOMPLETE);
        stockpileItems.put("stockpile-batteries", true);
        assertThat(item(journey(), "supplies.light_batteries").completion()).isEqualTo(CompletionState.COMPLETE);

        assertThat(item(journey(), "supplies.home_kit").completion()).isEqualTo(CompletionState.INCOMPLETE);
        stockpilePercent = 100;
        assertThat(item(journey(), "supplies.home_kit").completion()).isEqualTo(CompletionState.COMPLETE);
    }

    @Test
    void evacuationMetricDrillLogAndPlanConfirmation() {
        evacMetrics.put("alternate_route", true);
        evacMetrics.put("offline_maps", false);
        household.getDrillLog().put("contact-tree#call", new DrillCompletion(NOW.minus(Duration.ofDays(3)), ME));
        household.getDrillLog().put("contact-tree", new DrillCompletion(NOW.minus(Duration.ofDays(9)), ME));
        household.setPlanLastConfirmedAt(NOW.minus(Duration.ofDays(1)));

        ReadinessJourneyDto j = journey();
        assertThat(item(j, "evacuation.alternate_route").completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(item(j, "evacuation.offline_map").completion()).isEqualTo(CompletionState.INCOMPLETE);
        ItemDto tree = item(j, "practice.contact_tree");
        assertThat(tree.completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(tree.completedAt()).isEqualTo(NOW.minus(Duration.ofDays(3)));
        assertThat(item(j, "practice.plan_review").completedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(item(j, "practice.blackout_drill").completion()).isEqualTo(CompletionState.INCOMPLETE);
    }

    @Test
    void manualDoneRowCompletesAndCarriesUpdatedAt() {
        row("documents.first_folder", "HOUSEHOLD", null, ItemStateKind.DONE, NOW.minus(Duration.ofDays(5)));
        ItemDto folder = item(journey(), "documents.first_folder");
        assertThat(folder.completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(folder.completedAt()).isEqualTo(NOW.minus(Duration.ofDays(5)));
        assertThat(folder.householdState()).isEqualTo(ItemStateKind.DONE);
        assertThat(folder.capabilities().canUndo()).isTrue();
    }

    @Test
    void foodSafetyAlsoCompletesFromTheFridgeDrill() {
        household.getDrillLog().put("poweroutage-fridge-first-menu", new DrillCompletion(NOW, ME));
        assertThat(item(journey(), "outage.food_safety").completion()).isEqualTo(CompletionState.COMPLETE);
    }

    @Test
    void manualDoneIsIgnoredForNonManualItems() {
        row("outage.co_detector", "HOUSEHOLD", null, ItemStateKind.DONE, NOW);
        ItemDto co = item(journey(), "outage.co_detector");
        assertThat(co.completion()).isEqualTo(CompletionState.INCOMPLETE);
        assertThat(co.householdState()).isNull();
        assertThat(co.capabilities().canMarkDone()).isFalse();
    }

    // ------------------------------------------------------------------ freshness

    @Test
    void freshnessBands() {
        row("documents.printed_plan", "HOUSEHOLD", null, ItemStateKind.DONE, NOW.minus(Duration.ofDays(200)));
        row("outage.flashlight_bed", "HOUSEHOLD", null, ItemStateKind.DONE, NOW.minus(Duration.ofDays(160)));
        row("outage.charge_plan", "HOUSEHOLD", null, ItemStateKind.DONE, NOW.minus(Duration.ofDays(10)));
        row("outage.generator_safety", "HOUSEHOLD", null, ItemStateKind.DONE, NOW.minus(Duration.ofDays(900)));
        row("documents.paper_numbers", "HOUSEHOLD", null, ItemStateKind.DONE, null);

        ReadinessJourneyDto j = journey();
        ItemDto printed = item(j, "documents.printed_plan");
        assertThat(printed.freshness()).isEqualTo(Freshness.REVIEW_DUE);
        assertThat(printed.completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(printed.reviewDueAt()).isEqualTo(NOW.minus(Duration.ofDays(20)));
        assertThat(item(j, "outage.flashlight_bed").freshness()).isEqualTo(Freshness.REVIEW_SOON);
        assertThat(item(j, "outage.charge_plan").freshness()).isEqualTo(Freshness.CURRENT);
        assertThat(item(j, "outage.generator_safety").freshness()).isNull();       // one-time step
        assertThat(item(j, "documents.paper_numbers").freshness()).isEqualTo(Freshness.UNKNOWN);
        assertThat(item(j, "documents.first_folder").freshness()).isNull();        // incomplete
        // stale stays done
        assertThat(area(j, ReadinessArea.DOCUMENTS).done()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ overlays + counts

    @Test
    void notRelevantIsShownButExcludedFromCountsAndCandidates() {
        row("people.out_of_area_contact", "HOUSEHOLD", null, ItemStateKind.NOT_RELEVANT, NOW);
        ReadinessJourneyDto j = journey();
        ItemDto out = item(j, "people.out_of_area_contact");
        assertThat(out.householdState()).isEqualTo(ItemStateKind.NOT_RELEVANT);
        assertThat(area(j, ReadinessArea.PEOPLE).total()).isEqualTo(2);
        assertThat(j.nextStep().itemKey()).isNotEqualTo("people.out_of_area_contact");
    }

    @Test
    void activeSnoozesHideFromCandidatesAndExpiredOnesAreIgnored() {
        HouseholdReadinessItemState skip = row("people.out_of_area_contact", "USER", ME, ItemStateKind.SKIPPED, NOW);
        skip.setSuppressedUntil(NOW.plus(Duration.ofDays(30)));
        ReadinessJourneyDto j = journey();
        ItemDto out = item(j, "people.out_of_area_contact");
        assertThat(out.userState().state()).isEqualTo(ItemStateKind.SKIPPED);
        assertThat(out.userState().until()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(area(j, ReadinessArea.PEOPLE).total()).isEqualTo(3);    // still counted
        assertThat(j.nextStep().itemKey()).isNotEqualTo("people.out_of_area_contact");

        skip.setSuppressedUntil(NOW.minus(Duration.ofMinutes(1)));
        j = journey();
        assertThat(item(j, "people.out_of_area_contact").userState()).isNull();
        assertThat(j.nextStep().itemKey()).isEqualTo("people.out_of_area_contact");

        rows.clear();
        HouseholdReadinessItemState remind = row("people.out_of_area_contact", "USER", ME, ItemStateKind.REMIND_LATER, NOW);
        remind.setRemindAt(NOW.plus(Duration.ofDays(7)));
        assertThat(item(journey(), "people.out_of_area_contact").userState().state()).isEqualTo(ItemStateKind.REMIND_LATER);
        remind.setRemindAt(NOW.minus(Duration.ofDays(1)));
        assertThat(item(journey(), "people.out_of_area_contact").userState()).isNull();
    }

    @Test
    void anotherMembersSnoozeDoesNotAffectMe() {
        HouseholdReadinessItemState skip = row("people.out_of_area_contact", "USER", "other@example.com",
                ItemStateKind.SKIPPED, NOW);
        skip.setSuppressedUntil(NOW.plus(Duration.ofDays(30)));
        ReadinessJourneyDto j = journey();
        assertThat(item(j, "people.out_of_area_contact").userState()).isNull();
        assertThat(j.nextStep().itemKey()).isEqualTo("people.out_of_area_contact");
    }

    @Test
    void doneCountSumsAreasAndAllCaughtUpWhenNothingLeft() {
        ReadinessJourneyDto j = journey();
        assertThat(j.doneCount()).isZero();
        assertThat(j.allCaughtUp()).isFalse();

        // mark every applicable item not relevant → no candidates.
        for (var item : ReadinessCatalog.staticItems()) {
            row(item.key(), "HOUSEHOLD", null, ItemStateKind.NOT_RELEVANT, NOW);
        }
        j = journey();
        assertThat(j.nextStep()).isNull();
        assertThat(j.allCaughtUp()).isTrue();
        assertThat(j.doneCount()).isZero();
    }

    // ------------------------------------------------------------------ local risks

    @Test
    void localRiskAreaShowsSetupHintWithoutALocation() {
        risk = new RiskProfileDto("unknown", null, "your area", List.of(),
                List.of(new RiskAdjustedRequirementDto("set_home_location", null, "l", "d", 0, "c", "/household",
                        "location_prompt")), List.of(), NOW, "v");
        AreaDto local = area(journey(), ReadinessArea.LOCAL_RISKS);
        assertThat(local.items()).isEmpty();
        assertThat(local.setupHint().title()).isEqualTo("Set your home location");
        assertThat(local.setupHint().action().type()).isEqualTo(ReadinessAction.OPEN_HOUSEHOLD_LOCATION);
        assertThat(local.emptyCopy()).isNull();
    }

    @Test
    void localRiskAreaEmptyCopyWhenNothingMapped() {
        AreaDto local = area(journey(), ReadinessArea.LOCAL_RISKS);
        assertThat(local.setupHint()).isNull();
        assertThat(local.emptyCopy()).isEqualTo("No extra local steps for your area right now.");
    }

    @Test
    void localRiskItemsCompleteFromTheirMappedSource() {
        risk = new RiskProfileDto("household_zip", "CA", "California",
                List.of(new RiskDto("earthquake", "Earthquake", "very_high", "r", "s")),
                List.of(new RiskAdjustedRequirementDto("earthquake_utility_wrench", "earthquake", "Utility shutoff wrench",
                        "d", 1, "c", "/go-bag", "risk_added")), List.of(), NOW, "v");
        stockpileItems.put("stockpile-utility-shutoff-wrench", true);
        ItemDto wrench = item(journey(), "local_risk.earthquake_utility_wrench");
        assertThat(wrench.completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(wrench.completionSource()).isEqualTo(CompletionSource.STOCKPILE);
    }

    // ------------------------------------------------------------------ gates

    @Test
    void essentialsFirstNextStepIsHomesNextEssential() {
        when(essentials.evaluate(any(), anyString(), anyBoolean()))
                .thenReturn(new EssentialsResult(true, true, false, false));
        ReadinessJourneyDto j = journey();
        assertThat(j.mode()).isEqualTo(JourneyMode.ESSENTIALS_FIRST);
        assertThat(j.essentials().complete()).isFalse();
        assertThat(j.essentials().done()).isEqualTo(2);
        assertThat(j.essentials().nextKey()).isEqualTo("evacuation");
        assertThat(j.nextStep().itemKey()).isEqualTo("essentials.evacuation");
        assertThat(j.allCaughtUp()).isFalse();
        assertThat(j.areas()).hasSize(7);
    }

    @Test
    void liveActivationGatesToActiveResponse() {
        PlanActivation a = new PlanActivation();
        a.setId("act-1");
        when(activations.findLiveForHousehold(eq(household), any())).thenReturn(List.of(a));
        assertActiveResponse(journey(), "PLAN_ACTIVATION");
    }

    @Test
    void openCheckInGatesToActiveResponse() {
        household.setAlert("Active");
        assertActiveResponse(journey(), "CHECK_IN");
    }

    @Test
    void officialAlertGatesToActiveResponseAndUrgentStepsNeverBecomeItems() {
        risk = new RiskProfileDto("household_zip", "FL", "Florida",
                List.of(new RiskDto("hurricane", "Hurricane", "very_high", "r", "s")),
                List.of(new RiskAdjustedRequirementDto("active_alert_hurricane", "hurricane", "Act now", "d", 0,
                                "Safety steps", "/hazards", "active_alert_upgraded"),
                        new RiskAdjustedRequirementDto("noaa_radio", "hurricane", "Radio", "d", 3, "c", "/go-bag",
                                "risk_added")),
                List.of(new ActiveAlertDto("a", "NWS", "Extreme", "hurricane", "Hurricane Warning", "x", "y", null, null)),
                NOW, "v");
        ReadinessJourneyDto j = journey();
        assertActiveResponse(j, "OFFICIAL_ALERT");
        assertThat(j.activeResponse().title()).isEqualTo("Hurricane Warning");
        assertThat(area(j, ReadinessArea.LOCAL_RISKS).items()).extracting(ItemDto::key)
                .containsExactly("local_risk.noaa_radio");
    }

    private static void assertActiveResponse(ReadinessJourneyDto j, String kind) {
        assertThat(j.mode()).isEqualTo(JourneyMode.ACTIVE_RESPONSE);
        assertThat(j.activeResponse().kind()).isEqualTo(kind);
        assertThat(j.nextStep()).isNull();
        assertThat(j.allCaughtUp()).isFalse();
        assertThat(j.tools()).extracting(ReadinessJourneyDtos.ToolDto::key)
                .doesNotContain("tools.home_kit", "tools.alert_setup");
        assertThat(j.areas()).isNotEmpty();
        assertThat(j.areas().stream().flatMap(a -> a.items().stream()))
                .allSatisfy(i -> {
                    assertThat(i.capabilities().canSkip()).isFalse();
                    assertThat(i.capabilities().canRemind()).isFalse();
                });
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    void capabilitiesDependOnTheRequestersRole() {
        ItemDto asMember = item(journey(), "documents.first_folder");
        assertThat(asMember.capabilities().canMarkDone()).isTrue();
        assertThat(asMember.capabilities().canSkip()).isTrue();
        assertThat(asMember.capabilities().canRemind()).isTrue();
        assertThat(asMember.capabilities().canMarkNotRelevant()).isFalse();
        assertThat(asMember.capabilities().canUndo()).isFalse();
        assertThat(asMember.capabilities().canRestore()).isFalse();

        ItemDto asAdmin = item(service.getJourney(HH, ADMIN), "documents.first_folder");
        assertThat(asAdmin.capabilities().canMarkNotRelevant()).isTrue();

        ItemDto stockpileItem = item(journey(), "outage.co_detector");
        assertThat(stockpileItem.capabilities().canMarkDone()).isFalse();
        assertThat(stockpileItem.capabilities().canSkip()).isTrue();
    }

    // ------------------------------------------------------------------ mutations

    @Test
    void memberCanMarkAManualStepDone() {
        ReadinessJourneyDto j = service.setState(HH, "documents.first_folder",
                new SetItemStateRequest("DONE", null, null), ME);
        assertThat(item(j, "documents.first_folder").completion()).isEqualTo(CompletionState.COMPLETE);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getScope()).isEqualTo("HOUSEHOLD");
            assertThat(r.getUserEmail()).isNull();
            assertThat(r.getCreatedBy()).isEqualTo(ME);
            assertThat(r.getUpdatedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void doneOnANonManualStepIs409() {
        assertStatus(() -> service.setState(HH, "outage.co_detector", new SetItemStateRequest("DONE", null, null), ME),
                HttpStatus.CONFLICT);
        assertThat(rows).isEmpty();
    }

    @Test
    void memberCannotMarkNotRelevantButAdminCan() {
        assertStatus(() -> service.setState(HH, "outage.generator_safety",
                new SetItemStateRequest("NOT_RELEVANT", null, "no_generator"), ME), HttpStatus.FORBIDDEN);
        assertThat(rows).isEmpty();

        ReadinessJourneyDto j = service.setState(HH, "outage.generator_safety",
                new SetItemStateRequest("NOT_RELEVANT", null, "no_generator"), ADMIN);
        assertThat(item(j, "outage.generator_safety").householdState()).isEqualTo(ItemStateKind.NOT_RELEVANT);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getReasonCode()).isEqualTo("no_generator"));
    }

    @Test
    void unknownKeyIs404AndBadStateIs400() {
        assertStatus(() -> service.setState(HH, "documents.nope", new SetItemStateRequest("DONE", null, null), ME),
                HttpStatus.NOT_FOUND);
        assertStatus(() -> service.setState(HH, "local_risk.noaa_radio", new SetItemStateRequest("DONE", null, null), ME),
                HttpStatus.NOT_FOUND);   // not in this household's current local-risk set
        assertStatus(() -> service.setState(HH, "documents.first_folder", new SetItemStateRequest("FINISHED", null, null), ME),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.setState(HH, "documents.first_folder", new SetItemStateRequest(null, null, null), ME),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.clearState(HH, "documents.first_folder", "bogus", ME), HttpStatus.BAD_REQUEST);
    }

    @Test
    void doneReplacesNotRelevantInOneHouseholdRow() {
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("NOT_RELEVANT", null, null), ADMIN);

        // A member can't undo the admin's "not relevant" by marking it done.
        assertThat(item(journey(), "documents.first_folder").capabilities().canMarkDone()).isFalse();
        assertStatus(() -> service.setState(HH, "documents.first_folder",
                new SetItemStateRequest("DONE", null, null), ME), HttpStatus.FORBIDDEN);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getState()).isEqualTo(ItemStateKind.NOT_RELEVANT));

        assertThat(item(service.getJourney(HH, ADMIN), "documents.first_folder").capabilities().canMarkDone()).isTrue();
        ReadinessJourneyDto j = service.setState(HH, "documents.first_folder",
                new SetItemStateRequest("DONE", null, null), ADMIN);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getState()).isEqualTo(ItemStateKind.DONE));
        assertThat(item(j, "documents.first_folder").householdState()).isEqualTo(ItemStateKind.DONE);

        service.setState(HH, "documents.first_folder", new SetItemStateRequest("NOT_RELEVANT", null, null), ADMIN);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getState()).isEqualTo(ItemStateKind.NOT_RELEVANT));
    }

    @Test
    void skippedReplacesRemindLaterInOneUserRow() {
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("REMIND_LATER", null, null), ME);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getScope()).isEqualTo("USER");
            assertThat(r.getUserEmail()).isEqualTo(ME);
            assertThat(r.getRemindAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        });

        ReadinessJourneyDto j = service.setState(HH, "documents.first_folder",
                new SetItemStateRequest("SKIPPED", null, null), ME);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getState()).isEqualTo(ItemStateKind.SKIPPED);
            assertThat(r.getSuppressedUntil()).isEqualTo(NOW.plus(Duration.ofDays(30)));
            assertThat(r.getRemindAt()).isNull();
        });
        assertThat(item(j, "documents.first_folder").userState().state()).isEqualTo(ItemStateKind.SKIPPED);

        // a different member gets their own row
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("SKIPPED", null, null), ADMIN);
        assertThat(rows).hasSize(2);
    }

    @Test
    void remindLaterAcceptsOnlyOneSevenOrThirtyDays() {
        for (int days : List.of(1, 7, 30)) {
            service.setState(HH, "documents.first_folder", new SetItemStateRequest("REMIND_LATER", days, null), ME);
            assertThat(rows).singleElement()
                    .satisfies(r -> assertThat(r.getRemindAt()).isEqualTo(NOW.plus(Duration.ofDays(days))));
        }
        assertStatus(() -> service.setState(HH, "documents.first_folder",
                new SetItemStateRequest("REMIND_LATER", 2, null), ME), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.setState(HH, "documents.first_folder",
                new SetItemStateRequest("DONE", null, "x".repeat(65)), ME), HttpStatus.BAD_REQUEST);
    }

    @Test
    void clearIsIdempotentAndScopedToTheMatchingState() {
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("DONE", null, null), ME);
        service.clearState(HH, "documents.first_folder", "NOT_RELEVANT", ADMIN);   // wrong state → no-op
        assertThat(rows).hasSize(1);

        ReadinessJourneyDto j = service.clearState(HH, "documents.first_folder", "DONE", ME);
        assertThat(rows).isEmpty();
        assertThat(item(j, "documents.first_folder").completion()).isEqualTo(CompletionState.INCOMPLETE);
        service.clearState(HH, "documents.first_folder", "DONE", ME);              // again → fine
        assertThat(rows).isEmpty();

        assertStatus(() -> service.clearState(HH, "documents.first_folder", "NOT_RELEVANT", ME), HttpStatus.FORBIDDEN);
    }

    @Test
    void clearingASnoozeOnlyTouchesMyRow() {
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("SKIPPED", null, null), ME);
        service.setState(HH, "documents.first_folder", new SetItemStateRequest("SKIPPED", null, null), ADMIN);
        service.clearState(HH, "documents.first_folder", "SKIPPED", ME);
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.getUserEmail()).isEqualTo(ADMIN));
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode()).isEqualTo(status);
    }
}
