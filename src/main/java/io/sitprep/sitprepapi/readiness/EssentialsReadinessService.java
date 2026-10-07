package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The four essentials, evaluated exactly as Home sees them.
 *
 * <p><b>FE twin:</b> {@code SitPrep FE/src/me/dashboard/essentialsCatalog.js}
 * ({@code normalizeFromMePlans} + {@code CHECKS}), fed by
 * {@code /api/me/{uid}/plans} ({@code MeService.assemblePlans}) and
 * {@code /me.household.demographic}. Change the rule in all three places
 * together — FE Home moving onto this value is Epic 2 (CONTRACT.md §3a).</p>
 *
 * <p>Five checks, AND not OR:</p>
 * <ul>
 *   <li>{@code evacuation}: meeting places &gt; 0 AND evacuation plans (routes) &gt; 0</li>
 *   <li>{@code contacts}: contact GROUPS &gt; 0 (an empty group passes, as on Home)</li>
 *   <li>{@code mealPlan}: a {@code MealPlanData} row exists (menus not required, as on Home)</li>
 *   <li>{@code demographics}: head count (people + pets) &gt; 0; no household → fails</li>
 * </ul>
 *
 * <p>Lists are household-first; the requester's own owner-keyed rows are a
 * fallback ONLY when {@code householdId} is the requester's base household
 * and the household has none — the {@code householdFirst} rule
 * {@code MeService.assemblePlans} uses. The owner fallbacks keep Home's exact
 * repo calls (meeting places / evacuation use the case-sensitive
 * {@code findByOwnerEmail} with a lower-cased email).</p>
 */
@Service
public class EssentialsReadinessService {

    /** Home's order (FE {@code ESSENTIAL_KEYS}); {@code nextKey} = first not done. */
    public static final List<String> ESSENTIAL_KEYS = List.of("demographics", "mealPlan", "evacuation", "contacts");

    /** One essential's client copy for the ESSENTIALS_FIRST next step. */
    public record EssentialStep(String key, String title, String description, TimeBand time) {}

    /**
     * Short imperative titles for the FE ESSENTIALS rows; minutes from the FE
     * catalog ({@code mins}) → nearest band. Descriptions are the FE {@code desc}.
     */
    public static final List<EssentialStep> STEPS = List.of(
            new EssentialStep("demographics", "Add your household",
                    "Tell us who you're planning for. People and pets to tailor portions and lists.",
                    TimeBand.nearest(2)),
            new EssentialStep("mealPlan", "Make a meal plan",
                    "Build a 3+ day meal plan. Get auto-lists and portioning help.",
                    TimeBand.nearest(3)),
            new EssentialStep("evacuation", "Set an evacuation route and meeting place",
                    "Meetups, shelters, routes, and your go bag — one plan for leaving fast.",
                    TimeBand.nearest(5)),
            new EssentialStep("contacts", "Add emergency contacts",
                    "Add key family, medical, and neighbor contacts. Share with your group in one tap.",
                    TimeBand.nearest(2)));

    public static EssentialStep step(String key) {
        return STEPS.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
    }

    /** Result: which essentials are done, in Home's order. */
    public record EssentialsResult(boolean demographics, boolean mealPlan, boolean evacuation, boolean contacts) {
        public int done() {
            return (demographics ? 1 : 0) + (mealPlan ? 1 : 0) + (evacuation ? 1 : 0) + (contacts ? 1 : 0);
        }

        public int total() { return ESSENTIAL_KEYS.size(); }

        public boolean complete() { return done() == total(); }

        public boolean isDone(String key) {
            return switch (key) {
                case "demographics" -> demographics;
                case "mealPlan" -> mealPlan;
                case "evacuation" -> evacuation;
                case "contacts" -> contacts;
                default -> false;
            };
        }

        /** Home's {@code nextStep}: the first essential not done, or null. */
        public String nextKey() {
            return ESSENTIAL_KEYS.stream().filter(k -> !isDone(k)).findFirst().orElse(null);
        }
    }

    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final EmergencyContactGroupRepo contactGroupRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final DemographicRepo demographicRepo;
    private final UserInfoRepo userInfoRepo;

    public EssentialsReadinessService(MeetingPlaceRepo meetingPlaceRepo,
                                      EvacuationPlanRepo evacuationPlanRepo,
                                      EmergencyContactGroupRepo contactGroupRepo,
                                      MealPlanDataRepo mealPlanDataRepo,
                                      DemographicRepo demographicRepo,
                                      UserInfoRepo userInfoRepo) {
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.contactGroupRepo = contactGroupRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.demographicRepo = demographicRepo;
        this.userInfoRepo = userInfoRepo;
    }

    /** True when {@code householdId} is the requester's base household. */
    @Transactional(readOnly = true)
    public boolean isRequesterBase(String householdId, String requesterEmail) {
        if (householdId == null || requesterEmail == null || requesterEmail.isBlank()) return false;
        String base = userInfoRepo.findByUserEmailIgnoreCase(requesterEmail.trim())
                .map(UserInfo::getBaseHouseholdId).orElse(null);
        return householdId.equals(base);
    }

    /**
     * Evaluate the essentials for {@code household} (null → nothing passes
     * that needs a household; Home's {@code household} is null without a
     * Household group, so demographics fails).
     */
    @Transactional(readOnly = true)
    public EssentialsResult evaluate(Group household, String requesterEmail) {
        String hid = household == null ? null : household.getGroupId();
        return evaluate(household, requesterEmail, isRequesterBase(hid, requesterEmail));
    }

    /**
     * Same, with the base-household check already made (the journey service
     * also needs it for its contact read, so it resolves it once).
     */
    @Transactional(readOnly = true)
    public EssentialsResult evaluate(Group household, String requesterEmail, boolean requesterBase) {
        String hid = household == null ? null : household.getGroupId();
        String email = requesterEmail == null ? "" : requesterEmail.trim().toLowerCase(Locale.ROOT);
        boolean ownerFallback = hid != null && !email.isEmpty() && requesterBase;
        boolean hasHousehold = hid != null && !hid.isBlank();

        int meetingPlaces = householdFirst(
                hasHousehold ? meetingPlaceRepo.findByHouseholdId(hid) : List.of(),
                ownerFallback, () -> meetingPlaceRepo.findByOwnerEmail(email)).size();
        int routes = householdFirst(
                hasHousehold ? evacuationPlanRepo.findByHouseholdId(hid) : List.of(),
                ownerFallback, () -> evacuationPlanRepo.findByOwnerEmail(email)).size();
        int contactGroups = householdFirst(
                hasHousehold ? contactGroupRepo.findByHouseholdId(hid) : List.of(),
                ownerFallback, () -> contactGroupRepo.findByOwnerEmailIgnoreCase(email)).size();

        // Home: plans.mealPlan has a non-null id ⇒ menuCount ≥ 1. A persisted
        // row always has an id, so "a row exists" is the whole rule.
        boolean meal = (hasHousehold && mealPlanDataRepo.findFirstByHouseholdId(hid).isPresent())
                || (ownerFallback && mealPlanDataRepo.findFirstByOwnerEmailIgnoreCase(email).isPresent());

        Demographic demo = !hasHousehold ? null
                : demographicRepo.findFirstByHouseholdIdOrderByIdDesc(hid)
                    .or(() -> ownerFallback
                            ? demographicRepo.findFirstByOwnerEmailIgnoreCaseOrderByIdDesc(email)
                                    .filter(d -> belongsHere(d, hid))
                            : Optional.empty())
                    .orElse(null);

        return new EssentialsResult(
                headCount(demo) > 0,
                meal,
                meetingPlaces > 0 && routes > 0,
                contactGroups > 0);
    }

    /**
     * The owner-email fallback is for LEGACY rows that predate household
     * keys (no household id). A row the user wrote for ANOTHER household is
     * that household's head count, not this one's: reading it here made Home
     * say "4 household members" about a household whose own page, food plan,
     * go-bag and stockpile all said there was no plan yet (2026-10-07).
     */
    public static boolean belongsHere(Demographic d, String householdId) {
        if (d == null) return false;
        String own = d.getHouseholdId();
        return own == null || own.isBlank() || own.equals(householdId);
    }

    /** FE {@code headCountOf}: people + pets. */
    static int headCount(Demographic d) {
        if (d == null) return 0;
        return d.getAdults() + d.getTeens() + d.getKids() + d.getInfants()
                + d.getDogs() + d.getCats() + d.getPets();
    }

    private static <T> List<T> householdFirst(List<T> byHousehold, boolean fallbackAllowed, Supplier<List<T>> byOwner) {
        if (byHousehold != null && !byHousehold.isEmpty()) return byHousehold;
        if (!fallbackAllowed) return List.of();
        List<T> owned = byOwner.get();
        return owned == null ? List.of() : owned;
    }
}
