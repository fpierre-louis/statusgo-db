package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EmergencyContactType;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.EvacuationAdvancedDto;
import io.sitprep.sitprepapi.dto.HomeStockpileDtos.HomeStockpileDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver.ActiveResponse;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.readiness.ReadinessCatalog.CatalogItem;
import io.sitprep.sitprepapi.readiness.ReadinessCatalog.ContactRule;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ActionDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ActiveResponseDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.AreaDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.BandDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.CapabilitiesDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.EssentialsDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ItemDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.NextStepDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ProvenanceDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ReadinessJourneyDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.SetItemStateRequest;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.SetupHintDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ToolDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.UserStateDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import io.sitprep.sitprepapi.service.EvacuationAdvancedService;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.RiskProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The readiness journey for one household + one requester
 * (CONTRACT.md §3, §6).
 *
 * <p>Gathers authoritative domain truth ONCE per request — essentials,
 * household contacts, stockpile, evacuation metrics, drill log, plan
 * confirmation, support profiles, pets, the risk profile (at most one
 * {@link RiskProfileService#resolveFor} call), active response and the item
 * state rows — then derives applicability → completion → freshness →
 * overlays → areas, and asks {@link ReadinessRecommendationService} for one
 * next step.</p>
 *
 * <p>Mutations enforce the one-row-per-scope rule (V98 partial unique
 * indexes) by find-then-update inside the request transaction, and return the
 * full rebuilt journey so the client replaces its state in one round trip.</p>
 */
@Service
public class ReadinessJourneyService {

    static final Duration SKIP_FOR = Duration.ofDays(30);
    static final Set<Integer> REMIND_DAYS = Set.of(1, 7, 30);
    static final int DEFAULT_REMIND_DAYS = 7;
    static final Duration REVIEW_SOON_WINDOW = Duration.ofDays(30);
    static final String NOT_MANUAL_MESSAGE = "This step completes from your plan";

    private static final Set<String> EXTENDED_FAMILY_ROLES = Set.of("family", "partner/spouse", "friend");

    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;
    private final EssentialsReadinessService essentialsService;
    private final ActiveResponseResolver activeResponseResolver;
    private final ReadinessRecommendationService recommendationService;
    private final RiskProfileService riskProfileService;
    private final HomeStockpileService homeStockpileService;
    private final EvacuationAdvancedService evacuationAdvancedService;
    private final EmergencyContactGroupRepo contactGroupRepo;
    private final EmergencySupportProfileRepo supportProfileRepo;
    private final HouseholdPetRepo petRepo;
    private final DemographicRepo demographicRepo;
    private final HouseholdReadinessItemStateRepo stateRepo;
    private final Clock clock;

    @Autowired
    public ReadinessJourneyService(GroupRepo groupRepo,
                                   HouseholdAccessService access,
                                   EssentialsReadinessService essentialsService,
                                   ActiveResponseResolver activeResponseResolver,
                                   ReadinessRecommendationService recommendationService,
                                   RiskProfileService riskProfileService,
                                   HomeStockpileService homeStockpileService,
                                   EvacuationAdvancedService evacuationAdvancedService,
                                   EmergencyContactGroupRepo contactGroupRepo,
                                   EmergencySupportProfileRepo supportProfileRepo,
                                   HouseholdPetRepo petRepo,
                                   DemographicRepo demographicRepo,
                                   HouseholdReadinessItemStateRepo stateRepo) {
        this(groupRepo, access, essentialsService, activeResponseResolver, recommendationService,
                riskProfileService, homeStockpileService, evacuationAdvancedService, contactGroupRepo,
                supportProfileRepo, petRepo, demographicRepo, stateRepo, Clock.systemUTC());
    }

    /** Test seam: a fixed clock makes freshness and snooze windows deterministic. */
    ReadinessJourneyService(GroupRepo groupRepo,
                            HouseholdAccessService access,
                            EssentialsReadinessService essentialsService,
                            ActiveResponseResolver activeResponseResolver,
                            ReadinessRecommendationService recommendationService,
                            RiskProfileService riskProfileService,
                            HomeStockpileService homeStockpileService,
                            EvacuationAdvancedService evacuationAdvancedService,
                            EmergencyContactGroupRepo contactGroupRepo,
                            EmergencySupportProfileRepo supportProfileRepo,
                            HouseholdPetRepo petRepo,
                            DemographicRepo demographicRepo,
                            HouseholdReadinessItemStateRepo stateRepo,
                            Clock clock) {
        this.groupRepo = groupRepo;
        this.access = access;
        this.essentialsService = essentialsService;
        this.activeResponseResolver = activeResponseResolver;
        this.recommendationService = recommendationService;
        this.riskProfileService = riskProfileService;
        this.homeStockpileService = homeStockpileService;
        this.evacuationAdvancedService = evacuationAdvancedService;
        this.contactGroupRepo = contactGroupRepo;
        this.supportProfileRepo = supportProfileRepo;
        this.petRepo = petRepo;
        this.demographicRepo = demographicRepo;
        this.stateRepo = stateRepo;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ReadinessJourneyDto getJourney(String householdId, String requesterEmail) {
        String email = normalizeEmail(requesterEmail);
        Group household = householdOr404(householdId);
        access.requireCanReadHousehold(email, householdId);
        RiskProfileDto risk = riskProfileService.resolveFor(household);
        return build(household, email, risk, clock.instant());
    }

    /**
     * The journey exactly as {@code requesterEmail} would see it at {@code now},
     * for {@link ReadinessReminderService}. Empty when the household is gone,
     * isn't a household, or the user is no longer a member of it: a reminder
     * about a household you left is never sent. No exception path — the sweep
     * decides from the result.
     */
    @Transactional(readOnly = true)
    public Optional<ReadinessJourneyDto> journeyForReminder(String householdId, String requesterEmail, Instant now) {
        String email = normalizeEmail(requesterEmail);
        if (householdId == null || householdId.isBlank() || email.isEmpty()) return Optional.empty();
        Optional<Group> household = groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()));
        if (household.isEmpty() || !access.canReadHousehold(email, householdId)) return Optional.empty();
        RiskProfileDto risk = riskProfileService.resolveFor(household.get());
        return Optional.of(build(household.get(), email, risk, now));
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    @Transactional
    public ReadinessJourneyDto setState(String householdId, String itemKey,
                                        SetItemStateRequest body, String requesterEmail) {
        String email = normalizeEmail(requesterEmail);
        ItemStateKind kind = parseState(body == null ? null : body.state());
        Integer remindIn = body == null ? null : body.remindInDays();
        int remindDays = DEFAULT_REMIND_DAYS;
        if (kind == ItemStateKind.REMIND_LATER && remindIn != null) {
            if (!REMIND_DAYS.contains(remindIn)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remindInDays must be 1, 7, or 30");
            }
            remindDays = remindIn;
        }
        String reasonCode = body == null ? null : trimToNull(body.reasonCode());
        if (reasonCode != null && reasonCode.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reasonCode must be 64 characters or fewer");
        }

        Group household = householdOr404(householdId);
        access.requireCanReadHousehold(email, householdId);
        RiskProfileDto risk = riskProfileService.resolveFor(household);
        CatalogItem item = itemOr404(itemKey, risk);
        Instant now = clock.instant();

        switch (kind) {
            case DONE -> {
                if (item.source() != CompletionSource.MANUAL) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, NOT_MANUAL_MESSAGE);
                }
                // Admin-only steps (shared household records and decisions)
                // need admin; member-safe ones don't (catalog memberCanComplete).
                if (!item.memberCanComplete()) access.requireCanAdminHousehold(email, householdId);
                // DONE replaces the household row. When that row is an admin's
                // NOT_RELEVANT, replacing it is undoing an admin decision, so it
                // takes admin — the same rule as clearing NOT_RELEVANT directly.
                boolean overNotRelevant = stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(
                                householdId, item.key(), HouseholdReadinessItemState.SCOPE_HOUSEHOLD)
                        .map(r -> r.getState() == ItemStateKind.NOT_RELEVANT).orElse(false);
                if (overNotRelevant) access.requireCanAdminHousehold(email, householdId);
                upsertHouseholdRow(householdId, item.key(), ItemStateKind.DONE, reasonCode, email, now);
            }
            case NOT_RELEVANT -> {
                access.requireCanAdminHousehold(email, householdId);
                upsertHouseholdRow(householdId, item.key(), ItemStateKind.NOT_RELEVANT, reasonCode, email, now);
            }
            case SKIPPED -> upsertUserRow(householdId, item.key(), email, ItemStateKind.SKIPPED,
                    now.plus(SKIP_FOR), null, reasonCode, now);
            case REMIND_LATER -> upsertUserRow(householdId, item.key(), email, ItemStateKind.REMIND_LATER,
                    null, now.plus(Duration.ofDays(remindDays)), reasonCode, now);
        }
        return build(household, email, risk, now);
    }

    /** Idempotent: clearing a state that isn't there returns the unchanged journey. */
    @Transactional
    public ReadinessJourneyDto clearState(String householdId, String itemKey, String state, String requesterEmail) {
        String email = normalizeEmail(requesterEmail);
        ItemStateKind kind = parseState(state);
        Group household = householdOr404(householdId);
        access.requireCanReadHousehold(email, householdId);
        RiskProfileDto risk = riskProfileService.resolveFor(household);
        CatalogItem item = itemOr404(itemKey, risk);

        if (kind == ItemStateKind.NOT_RELEVANT) access.requireCanAdminHousehold(email, householdId);
        // Undoing DONE follows the same rule as setting it.
        if (kind == ItemStateKind.DONE && !item.memberCanComplete()) {
            access.requireCanAdminHousehold(email, householdId);
        }

        Optional<HouseholdReadinessItemState> row = kind.householdScoped()
                ? stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(
                        householdId, item.key(), HouseholdReadinessItemState.SCOPE_HOUSEHOLD)
                : stateRepo.findFirstByHouseholdIdAndItemKeyAndScopeAndUserEmail(
                        householdId, item.key(), HouseholdReadinessItemState.SCOPE_USER, email);
        row.filter(r -> r.getState() == kind).ifPresent(stateRepo::delete);
        return build(household, email, risk, clock.instant());
    }

    private void upsertHouseholdRow(String householdId, String key, ItemStateKind state,
                                    String reasonCode, String email, Instant now) {
        HouseholdReadinessItemState row = stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(
                        householdId, key, HouseholdReadinessItemState.SCOPE_HOUSEHOLD)
                .orElseGet(() -> newRow(householdId, key, HouseholdReadinessItemState.SCOPE_HOUSEHOLD, null, email, now));
        row.setState(state);
        row.setReasonCode(reasonCode);
        // Re-marking DONE is the review action: the timestamp must move even
        // when no other column changed (freshness reads updatedAt).
        row.setUpdatedAt(now);
        stateRepo.save(row);
    }

    private void upsertUserRow(String householdId, String key, String email, ItemStateKind state,
                               Instant suppressedUntil, Instant remindAt, String reasonCode, Instant now) {
        HouseholdReadinessItemState row = stateRepo.findFirstByHouseholdIdAndItemKeyAndScopeAndUserEmail(
                        householdId, key, HouseholdReadinessItemState.SCOPE_USER, email)
                .orElseGet(() -> newRow(householdId, key, HouseholdReadinessItemState.SCOPE_USER, email, email, now));
        row.setState(state);
        row.setSuppressedUntil(suppressedUntil);
        row.setRemindAt(remindAt);
        // A new snooze (or a skip) is a new reminder decision: never "already reminded".
        row.setRemindedAt(null);
        row.setReasonCode(reasonCode);
        row.setUpdatedAt(now);
        stateRepo.save(row);
    }

    private static HouseholdReadinessItemState newRow(String householdId, String key, String scope,
                                                      String userEmail, String createdBy, Instant now) {
        HouseholdReadinessItemState row = new HouseholdReadinessItemState();
        row.setHouseholdId(householdId);
        row.setItemKey(key);
        row.setScope(scope);
        row.setUserEmail(userEmail);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(now);
        return row;
    }

    // ------------------------------------------------------------------
    // Build
    // ------------------------------------------------------------------

    /** Everything the derivation reads, gathered once. */
    record Truth(
            Group household,
            String requesterEmail,
            boolean isAdmin,
            EssentialsResult essentials,
            List<EmergencyContactGroup> contactGroups,
            Map<String, Boolean> stockpileSatisfied,
            int stockpilePercent,
            Map<String, Boolean> evacMetrics,
            Map<String, DrillCompletion> drillLog,
            Instant planLastConfirmedAt,
            boolean medicalNeeds,
            boolean hasPets,
            RiskProfileDto risk,
            ActiveResponse active,
            Map<String, HouseholdReadinessItemState> householdRows,
            Map<String, HouseholdReadinessItemState> userRows
    ) {}

    ReadinessJourneyDto build(Group household, String email, RiskProfileDto risk, Instant now) {
        return derive(gather(household, email, risk, now), now);
    }

    private Truth gather(Group household, String email, RiskProfileDto risk, Instant now) {
        String hid = household.getGroupId();
        boolean requesterBase = essentialsService.isRequesterBase(hid, email);
        EssentialsResult essentials = essentialsService.evaluate(household, email, requesterBase);

        // Same householdFirst rule as /me/plans (MeService.assemblePlans).
        List<EmergencyContactGroup> contacts = nonNull(contactGroupRepo.findByHouseholdId(hid));
        if (contacts.isEmpty() && requesterBase && !email.isEmpty()) {
            contacts = nonNull(contactGroupRepo.findByOwnerEmailIgnoreCase(email));
        }

        Map<String, Boolean> stockpile = new HashMap<>();
        int stockpilePercent = 0;
        HomeStockpileDto kit = homeStockpileService.getForHousehold(hid);
        if (kit != null) {
            stockpilePercent = kit.percentComplete();
            if (kit.categories() != null) {
                kit.categories().forEach(c -> {
                    if (c != null && c.items() != null) {
                        c.items().forEach(i -> { if (i != null) stockpile.put(i.itemKey(), i.satisfied()); });
                    }
                });
            }
        }

        Map<String, Boolean> evac = new HashMap<>();
        EvacuationAdvancedDto evacDto = evacuationAdvancedService.getForHousehold(hid);
        if (evacDto != null && evacDto.metrics() != null) {
            evacDto.metrics().forEach(m -> { if (m != null) evac.put(m.key(), m.satisfied()); });
        }

        boolean medical = nonNull(supportProfileRepo.findByHouseholdId(hid)).stream()
                .anyMatch(p -> p != null && (p.isPowerDependentEquipment() || p.isRefrigeratedMedication()));
        Demographic demo = demographicRepo.findFirstByHouseholdIdOrderByIdDesc(hid).orElse(null);
        boolean pets = (demo != null && demo.getDogs() + demo.getCats() + demo.getPets() > 0)
                || !nonNull(petRepo.findByHouseholdIdOrderByCreatedAtAsc(hid)).isEmpty();

        ActiveResponse active = activeResponseResolver.resolve(household, risk, now);

        Map<String, HouseholdReadinessItemState> householdRows = new HashMap<>();
        Map<String, HouseholdReadinessItemState> userRows = new HashMap<>();
        for (HouseholdReadinessItemState row : nonNull(stateRepo.findByHouseholdId(hid))) {
            if (row == null || row.getItemKey() == null) continue;
            if (HouseholdReadinessItemState.SCOPE_HOUSEHOLD.equals(row.getScope())) {
                householdRows.put(row.getItemKey(), row);
            } else if (email.equals(row.getUserEmail())) {
                userRows.put(row.getItemKey(), row);
            }
        }

        return new Truth(household, email, access.canWriteHousehold(email, hid), essentials, contacts,
                stockpile, stockpilePercent, evac,
                household.getDrillLog() == null ? Map.of() : household.getDrillLog(),
                household.getPlanLastConfirmedAt(), medical, pets, risk, active, householdRows, userRows);
    }

    ReadinessJourneyDto derive(Truth t, Instant now) {
        JourneyMode mode = t.active() != null ? JourneyMode.ACTIVE_RESPONSE
                : !t.essentials().complete() ? JourneyMode.ESSENTIALS_FIRST
                : JourneyMode.CALM;

        List<CatalogItem> catalog = new ArrayList<>(ReadinessCatalog.staticItems());
        catalog.addAll(ReadinessCatalog.localRiskItems(t.risk()));

        List<DerivedItem> derived = new ArrayList<>();
        for (CatalogItem item : catalog) {
            if (!applicable(item, t)) continue;
            derived.add(deriveItem(item, t, now));
        }

        List<AreaDto> areas = new ArrayList<>();
        int doneCount = 0;
        for (ReadinessArea area : ReadinessArea.values()) {
            List<DerivedItem> inArea = derived.stream().filter(d -> d.item().area() == area).toList();
            int done = (int) inArea.stream().filter(DerivedItem::countsDone).count();
            int total = (int) inArea.stream().filter(DerivedItem::counted).count();
            doneCount += done;
            SetupHintDto hint = null;
            String emptyCopy = null;
            if (area == ReadinessArea.LOCAL_RISKS) {
                if (!ReadinessCatalog.locationKnown(t.risk())) {
                    hint = new SetupHintDto("Set your home location",
                            "Local risk steps appear once SitPrep knows your area.",
                            ActionDto.of(ReadinessAction.OPEN_HOUSEHOLD_LOCATION, Map.of()));
                } else if (inArea.isEmpty()) {
                    emptyCopy = "No extra local steps for your area right now.";
                }
            }
            areas.add(new AreaDto(area, area.title(), area.description(), done, total, hint, emptyCopy,
                    inArea.stream().map(d -> toItemDto(d, t, mode)).toList()));
        }

        NextStepDto next = recommendationService.recommend(mode, t.essentials(), derived,
                t.risk() == null || t.risk().risks() == null ? List.of() : t.risk().risks());

        EssentialsResult e = t.essentials();
        ActiveResponse a = t.active();
        return new ReadinessJourneyDto(
                ReadinessJourneyDtos.SCHEMA_VERSION,
                ReadinessCatalog.CATALOG_VERSION,
                ReadinessRecommendationService.RECOMMENDATION_VERSION,
                t.household().getGroupId(),
                now,
                mode,
                new EssentialsDto(e.complete(), e.done(), e.total(), e.nextKey()),
                a == null ? null : new ActiveResponseDto(a.kind().name(), a.title(), a.detail(),
                        ActionDto.of(a.action(), a.params())),
                doneCount,
                mode == JourneyMode.CALM && next == null,
                next,
                areas,
                ReadinessCatalog.tools(mode).stream()
                        .map(tool -> new ToolDto(tool.key(), tool.title(), tool.description(),
                                ActionDto.of(tool.action(), Map.of())))
                        .toList());
    }

    private static boolean applicable(CatalogItem item, Truth t) {
        return switch (item.applicability()) {
            case ALWAYS -> true;
            case PETS -> t.hasPets();
            case MEDICAL -> t.medicalNeeds();
        };
    }

    private static DerivedItem deriveItem(CatalogItem item, Truth t, Instant now) {
        HouseholdReadinessItemState hRow = t.householdRows().get(item.key());
        ItemStateKind householdState = null;
        if (hRow != null) {
            if (hRow.getState() == ItemStateKind.NOT_RELEVANT) householdState = ItemStateKind.NOT_RELEVANT;
            // A DONE row counts only for MANUAL items; rows for other sources are ignored.
            else if (hRow.getState() == ItemStateKind.DONE && item.source() == CompletionSource.MANUAL) {
                householdState = ItemStateKind.DONE;
            }
        }

        boolean complete;
        Instant completedAt = null;
        switch (item.source()) {
            case MANUAL -> {
                complete = householdState == ItemStateKind.DONE;
                if (complete) completedAt = hRow.getUpdatedAt();
                Instant drillAt = item.drillId() == null ? null : newestDrill(t.drillLog(), item.drillId());
                if (drillAt != null) {
                    complete = true;
                    if (completedAt == null || drillAt.isAfter(completedAt)) completedAt = drillAt;
                }
            }
            case CONTACTS -> complete = contactsSatisfy(item.contactRule(), t.contactGroups());
            case STOCKPILE -> complete = item.stockpileFull()
                    ? t.stockpilePercent() >= 100
                    : !item.stockpileKeys().isEmpty()
                        && item.stockpileKeys().stream().allMatch(k -> Boolean.TRUE.equals(t.stockpileSatisfied().get(k)));
            case EVACUATION_METRIC -> complete = Boolean.TRUE.equals(t.evacMetrics().get(item.evacMetric()));
            case DRILL_LOG -> {
                completedAt = newestDrill(t.drillLog(), item.drillId());
                complete = completedAt != null;
            }
            case PLAN_CONFIRMATION -> {
                completedAt = t.planLastConfirmedAt();
                complete = completedAt != null;
            }
            default -> complete = false;
        }

        Freshness freshness = null;
        Instant reviewDueAt = null;
        if (complete && item.reviewAfterDays() != null) {
            if (completedAt == null) {
                freshness = Freshness.UNKNOWN;
            } else {
                reviewDueAt = completedAt.plus(Duration.ofDays(item.reviewAfterDays()));
                if (!now.isBefore(reviewDueAt)) freshness = Freshness.REVIEW_DUE;
                else if (!now.isBefore(reviewDueAt.minus(REVIEW_SOON_WINDOW))) freshness = Freshness.REVIEW_SOON;
                else freshness = Freshness.CURRENT;
            }
        }

        ItemStateKind userState = null;
        Instant until = null;
        HouseholdReadinessItemState uRow = t.userRows().get(item.key());
        if (uRow != null) {
            if (uRow.getState() == ItemStateKind.SKIPPED && uRow.getSuppressedUntil() != null
                    && uRow.getSuppressedUntil().isAfter(now)) {
                userState = ItemStateKind.SKIPPED;
                until = uRow.getSuppressedUntil();
            } else if (uRow.getState() == ItemStateKind.REMIND_LATER && uRow.getRemindAt() != null
                    && uRow.getRemindAt().isAfter(now)) {
                userState = ItemStateKind.REMIND_LATER;
                until = uRow.getRemindAt();
            }
        }

        return new DerivedItem(item, complete ? CompletionState.COMPLETE : CompletionState.INCOMPLETE,
                complete ? completedAt : null, freshness, reviewDueAt, householdState, userState, until);
    }

    /** Newest completion among {@code id} and {@code id#phase} keys, or null. */
    static Instant newestDrill(Map<String, DrillCompletion> log, String id) {
        if (log == null || id == null) return null;
        Instant newest = null;
        for (Map.Entry<String, DrillCompletion> e : log.entrySet()) {
            String key = e.getKey();
            DrillCompletion c = e.getValue();
            if (key == null || c == null || c.getCompletedAt() == null) continue;
            if (!key.equals(id) && !key.startsWith(id + "#")) continue;
            if (newest == null || c.getCompletedAt().isAfter(newest)) newest = c.getCompletedAt();
        }
        return newest;
    }

    static boolean contactsSatisfy(ContactRule rule, List<EmergencyContactGroup> groups) {
        if (rule == null || groups == null) return false;
        for (EmergencyContactGroup g : groups) {
            if (g == null || g.getContacts() == null) continue;
            for (EmergencyContact c : g.getContacts()) {
                if (c != null && matches(rule, c, g.getName())) return true;
            }
        }
        return false;
    }

    private static boolean matches(ContactRule rule, EmergencyContact c, String groupName) {
        String role = c.getRole() == null ? "" : c.getRole().trim();
        return switch (rule) {
            case OUT_OF_AREA -> c.getContactType() == EmergencyContactType.OUT_OF_AREA
                    || role.equalsIgnoreCase("Out-of-Area");
            case NEIGHBOR -> role.equalsIgnoreCase("Neighbor");
            case EXTENDED_FAMILY -> EXTENDED_FAMILY_ROLES.contains(role.toLowerCase(Locale.ROOT));
            case PET -> "pet".equalsIgnoreCase(c.getSubjectType())
                    || c.getContactType() == EmergencyContactType.PET_CAREGIVER
                    || mentionsVet(role, c.getMedicalInfo(), groupName, c.getSubjectType(), c.getSubjectName());
        };
    }

    private static boolean mentionsVet(String... fields) {
        for (String f : fields) {
            if (f == null) continue;
            String l = f.toLowerCase(Locale.ROOT);
            if (l.contains("vet") || l.contains("veterinary")) return true;
        }
        return false;
    }

    private static ItemDto toItemDto(DerivedItem d, Truth t, JourneyMode mode) {
        CatalogItem item = d.item();
        return new ItemDto(
                item.key(), item.area(), item.scope(), item.title(), item.description(), item.guidance(),
                d.completion(), item.source(), d.completedAt(), d.freshness(), d.reviewDueAt(),
                d.householdState(),
                d.userState() == null ? null : new UserStateDto(d.userState(), d.userUntil()),
                BandDto.of(item.time()), BandDto.of(item.cost()), BandDto.of(item.effort()),
                item.provenance().stream().map(ProvenanceDto::of).toList(),
                ActionDto.of(item.action(), item.actionParams()),
                capabilities(d, t.isAdmin(), mode));
    }

    /** What the requester may do to this item right now. */
    static CapabilitiesDto capabilities(DerivedItem d, boolean isAdmin, JourneyMode mode) {
        CatalogItem item = d.item();
        boolean manual = item.source() == CompletionSource.MANUAL;
        boolean notRelevant = d.notRelevant();
        boolean incompleteOrDue = d.completion() == CompletionState.INCOMPLETE
                || d.freshness() == Freshness.REVIEW_DUE || d.freshness() == Freshness.REVIEW_SOON;
        boolean open = !notRelevant && (d.completion() == CompletionState.INCOMPLETE || d.freshness() == Freshness.REVIEW_DUE);
        boolean calmish = mode != JourneyMode.ACTIVE_RESPONSE;
        // A member may complete (and undo) only member-safe steps, and never
        // over an admin's "not relevant"; admins may do both.
        boolean memberMay = item.memberCanComplete() && !notRelevant;
        return new CapabilitiesDto(
                manual && incompleteOrDue && (isAdmin || memberMay),
                d.householdState() == ItemStateKind.DONE && (isAdmin || item.memberCanComplete()),
                calmish && open && d.userState() != ItemStateKind.SKIPPED,
                calmish && open && d.userState() != ItemStateKind.REMIND_LATER,
                isAdmin && !notRelevant && d.completion() != CompletionState.COMPLETE,
                (notRelevant && isAdmin) || d.userState() != null);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Group householdOr404(String householdId) {
        if (householdId == null || householdId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        }
        return groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
    }

    /** A static catalog key or a key in the household's CURRENT local-risk set; else 404. */
    static CatalogItem itemOr404(String itemKey, RiskProfileDto risk) {
        if (itemKey == null || !ReadinessCatalog.KEY_FORMAT.matcher(itemKey).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown readiness step");
        }
        Optional<CatalogItem> item = ReadinessCatalog.staticItem(itemKey);
        if (item.isEmpty() && itemKey.startsWith(ReadinessCatalog.LOCAL_RISK_PREFIX)) {
            item = ReadinessCatalog.localRiskItems(risk).stream().filter(i -> i.key().equals(itemKey)).findFirst();
        }
        return item.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown readiness step"));
    }

    static ItemStateKind parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "state is required");
        }
        try {
            return ItemStateKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "state must be DONE, NOT_RELEVANT, SKIPPED, or REMIND_LATER");
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
