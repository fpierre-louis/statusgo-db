package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentItemDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentOptionDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentQuestionDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentSummaryDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.GuideActionDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.NextActionDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.PlanSignalsDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The weighted readiness-scoring engine — a faithful 1:1 port of the FE
 * {@code me/assessment/assessmentModel.js} (644 LOC, deleted): question
 * catalog, per-field weights, {@code earned += score × weight} accumulation,
 * the {@code base×0.9 + selfGrade×0.1} blend, band thresholds (85/60),
 * severity/priority gap ranking, hazard guide actions, and next-action
 * selection. Numbers must NOT drift — {@code AssessmentScoringServiceTest}
 * pins a hand-computed vector.
 *
 * <p>Guest-capable: {@code buildSummary} is a pure function of
 * (responses, planSignals, isAuthenticated, activeHazards). The resource
 * feeds empty signals for anonymous callers, so guests get the same server
 * math without any data access.</p>
 */
@Service
public class AssessmentScoringService {

    // --- Field registry (label / route / cta / pillar / severity / priority / weight) ---
    private record FieldMeta(String label, String route, String cta, String pillarKey,
                             String hazardKey, String guideSlug,
                             String severity, int priority, double weight) {}

    private record Guide(String title, String tag, String route, String accent, String accentBg) {}

    private static final Map<String, Guide> GUIDES = Map.of(
            "hurricane", new Guide("Hurricane preparedness", "Hurricane", "/hurricane-prep", "#0E7490", "#CFFAFE"),
            "wildfire", new Guide("Wildfire smoke + evacuation", "Wildfire", "/wildfire-prep", "#C2410C", "#FFEDD5"),
            "earthquake", new Guide("Earthquake preparedness", "Earthquake", "/earthquake-prep", "#7C3AED", "#EDE9FE"),
            "flood", new Guide("Flood preparedness", "Flood", "/flood-prep", "#0891B2", "#CFFAFE"),
            "blizzard", new Guide("Blizzard + winter storm", "Blizzard", "/blizzard-prep", "#1D4ED8", "#DBEAFE"));

    private static final Map<String, FieldMeta> FIELD_REGISTRY = buildRegistry();

    private static Map<String, FieldMeta> buildRegistry() {
        Map<String, FieldMeta> m = new LinkedHashMap<>();
        m.put("foodSupply", new FieldMeta("Emergency food", "/create-foodsupply", "Build a food plan", "mealPlan", null, null, "critical", 10, 1.2));
        m.put("waterSupply", new FieldMeta("Water storage", "/create-foodsupply", "Plan water and food", "mealPlan", null, null, "critical", 11, 1.2));
        m.put("communicationPlan", new FieldMeta("Communication plan", "/emergency-contacts", "Set contact plan", "contacts", null, null, "critical", 18, 1.1));
        m.put("emergencyContacts", new FieldMeta("Emergency contacts", "/emergency-contacts", "Add emergency contacts", "contacts", null, null, "critical", 20, 1.2));
        m.put("evacuationPlan", new FieldMeta("Evacuation plan", "/evacuation-wizard", "Create evacuation plan", "evacuation", null, null, "critical", 25, 1.2));
        m.put("emergencyKitCheck", new FieldMeta("Emergency kit", "/ask", "Review kit guidance", null, null, null, "high", 35, 1.0));
        m.put("powerOutagePlan", new FieldMeta("Power outage plan", "/ask", "Review outage guidance", null, null, null, "high", 40, 1.0));
        m.put("additionalSupplies", new FieldMeta("Additional supplies", "/ask", "Review supply guidance", null, null, null, "medium", 45, 0.9));
        m.put("firstAidKnowledge", new FieldMeta("First aid", "/ask", "Review first-aid guidance", null, null, null, "medium", 50, 0.9));
        m.put("specialNeedsPreparedness", new FieldMeta("Medical and accessibility needs", "/household", "Update household needs", "demographics", null, null, "high", 55, 1.0));
        m.put("petPreparedness", new FieldMeta("Pet preparedness", "/household", "Update pet planning", "demographics", null, null, "high", 58, 0.9));
        m.put("financialInsurancePreparedness", new FieldMeta("Financial recovery", "/ask", "Review recovery guidance", null, null, null, "medium", 70, 0.8));
        m.put("wildfirePrep", new FieldMeta("Wildfire readiness", "/wildfire-prep", "Read wildfire guide", null, "wildfire", "wildfire-prep", "high", 30, 0.9));
        m.put("floodPrep", new FieldMeta("Flood readiness", "/flood-prep", "Read flood guide", null, "flood", "flood-prep", "high", 32, 0.9));
        m.put("earthquakePrep", new FieldMeta("Earthquake readiness", "/earthquake-prep", "Read earthquake guide", null, "earthquake", "earthquake-prep", "high", 34, 0.9));
        m.put("hurricanePrep", new FieldMeta("Hurricane readiness", "/hurricane-prep", "Read hurricane guide", null, "hurricane", "hurricane-prep", "high", 36, 0.9));
        m.put("blizzardPrep", new FieldMeta("Winter storm readiness", "/blizzard-prep", "Read winter storm guide", null, "blizzard", "blizzard-prep", "high", 38, 0.9));
        return m;
    }

    private static final Map<String, Integer> SEVERITY_RANK =
            Map.of("critical", 0, "high", 1, "medium", 2, "low", 3);

    private static final Map<String, String[]> BAND_COPY = Map.of(
            "strong_foundation", new String[]{"Strong foundation",
                    "Your basics look solid. The best value now is keeping the plan current and closing smaller gaps."},
            "few_gaps", new String[]{"A few gaps",
                    "You have momentum. Focus on the next practical step so your plan becomes easier to use under stress."},
            "start_with_essentials", new String[]{"Start with essentials",
                    "Start small: people, places, food, and water. SitPrep can turn those into a usable plan quickly."});

    // --- Question catalog (declarative; conditions expressed as conditionHazard) ---
    public static final List<AssessmentQuestionDto> QUESTIONS = buildQuestions();

    private static AssessmentOptionDto opt(String label, String sub, Object value) {
        return new AssessmentOptionDto(label, sub, value);
    }

    private static List<AssessmentQuestionDto> buildQuestions() {
        final String YES = "Yes", KINDA = "Somewhat", NO = "Not yet";
        List<AssessmentQuestionDto> q = new ArrayList<>();
        q.add(new AssessmentQuestionDto("selfGrade", "How ready do you feel today?", "Starting point",
                "Choose the answer that best matches your current confidence.", "single", false, null, List.of(
                opt("Not prepared", "I do not have a plan or supplies yet.", 25),
                opt("Started", "I have a few pieces, but there is more to do.", 50),
                opt("Mostly ready", "I feel good, but want to tighten gaps.", 75),
                opt("Very ready", "My household knows the plan and where things are.", 100))));
        q.add(new AssessmentQuestionDto("emergencyContacts", "Emergency contacts", "People",
                "Do you have a contact list your household can use quickly?", "single", false, null, List.of(
                opt(YES, "The list is current and shared.", "yes"),
                opt(KINDA, "We have names, but it needs cleanup.", "kinda"),
                opt(NO, "We have not made the list yet.", "no"))));
        q.add(new AssessmentQuestionDto("communicationPlan", "Communication plan", "People",
                "Does everyone know how to reconnect if calls or texts are spotty?", "single", false, null, List.of(
                opt(YES, "We know who to contact and where to check in.", "yes"),
                opt(KINDA, "We have talked about it but have not practiced.", "kinda"),
                opt(NO, "We do not have a communication plan.", "no"))));
        q.add(new AssessmentQuestionDto("evacuationPlan", "Evacuation plan", "Places",
                "Do you have meeting places and a destination if home is unsafe?", "single", false, null, List.of(
                opt(YES, "Meeting places and routes are known.", "yes"),
                opt(KINDA, "We have ideas, but they are not fully saved.", "kinda"),
                opt(NO, "We have not chosen places yet.", "no"))));
        q.add(new AssessmentQuestionDto("foodSupply", "Food", "Supplies",
                "Do you have enough shelf-stable food for at least 3 days?", "single", false, null, List.of(
                opt(YES, "Enough for everyone in the household.", "yes"),
                opt(KINDA, "Some food is set aside, but not enough.", "kinda"),
                opt(NO, "We have not planned emergency food yet.", "no"))));
        q.add(new AssessmentQuestionDto("waterSupply", "Water", "Supplies",
                "Do you have water stored for drinking and basic sanitation?", "single", false, null, List.of(
                opt(YES, "At least 1 gallon per person per day.", "yes"),
                opt(KINDA, "We have some, but not enough for 3 days.", "kinda"),
                opt(NO, "We have not stored water yet.", "no"))));
        q.add(new AssessmentQuestionDto("emergencyKitCheck", "Emergency kit", "Supplies",
                "Are basic kit items easy to find if you need to leave quickly?", "single", false, null, List.of(
                opt(YES, "Kit is stocked and easy to grab.", "yes"),
                opt(KINDA, "Some items are ready, but not all together.", "kinda"),
                opt(NO, "We do not have a kit yet.", "no"))));
        q.add(new AssessmentQuestionDto("powerOutagePlan", "Power outage", "Supplies",
                "Could your household handle an extended power outage?", "single", false, null, List.of(
                opt(YES, "Light, charging, heat/cooling, and food safety are covered.", "yes"),
                opt(KINDA, "We have flashlights or batteries, but no full plan.", "kinda"),
                opt(NO, "We have not prepared for outages.", "no"))));
        q.add(new AssessmentQuestionDto("firstAidKnowledge", "First aid", "Health",
                "Do you have first-aid supplies and someone who knows the basics?", "single", false, null, List.of(
                opt(YES, "Supplies are ready and someone knows how to use them.", "yes"),
                opt(KINDA, "We have supplies but need training or a refresh.", "kinda"),
                opt(NO, "We need to add first-aid planning.", "no"))));
        q.add(new AssessmentQuestionDto("petPreparedness", "Pets", "Household needs",
                "If you have pets, are their food, meds, and carriers planned for?", "single", true, null, List.of(
                opt(YES, "Pet needs are included in the plan.", "yes"),
                opt(KINDA, "We have a few pet items, but not a full plan.", "partial"),
                opt(NO, "We have pets but have not planned for them.", "no"),
                opt("Not applicable", "No pets in the household.", "na"))));
        q.add(new AssessmentQuestionDto("specialNeedsPreparedness", "Medical or accessibility needs", "Household needs",
                "Are medications, equipment, mobility, or support needs covered?", "single", true, null, List.of(
                opt(YES, "Those needs are built into the plan.", "yes"),
                opt(KINDA, "We have some details, but they need work.", "partial"),
                opt(NO, "We need to plan for these needs.", "no"),
                opt("Not applicable", "No special medical or accessibility needs.", "na"))));
        q.add(new AssessmentQuestionDto("financialInsurancePreparedness", "Financial recovery", "Recovery",
                "Have you reviewed insurance, documents, and emergency funds?", "single", true, null, List.of(
                opt(YES, "Documents and coverage are ready.", "yes"),
                opt(KINDA, "Some pieces are handled, but not all.", "partial"),
                opt(NO, "We have not reviewed this yet.", "no"))));
        q.add(new AssessmentQuestionDto("naturalDisasters", "Local hazards", "Your area",
                "Which hazards should your household be ready for?", "multi-select", false, null, List.of(
                opt("Flood", null, "flood"),
                opt("Earthquake", null, "earthquake"),
                opt("Hurricane", null, "hurricane"),
                opt("Wildfire", null, "wildfire"),
                opt("Blizzard or winter storm", null, "blizzard"))));
        q.add(new AssessmentQuestionDto("blizzardPrep", "Winter storm", "Local hazard",
                "Can you stay warm, lit, and safe through severe winter weather?", "single", true, "blizzard", List.of(
                opt(YES, "Heat, supplies, and travel plans are covered.", "yes"),
                opt(KINDA, "Some winter items are ready.", "partial"),
                opt(NO, "We need winter storm planning.", "no"))));
        q.add(new AssessmentQuestionDto("floodPrep", "Flood", "Local hazard",
                "Have you prepared for floodwater, drainage, documents, and safe routes?", "single", true, "flood", List.of(
                opt(YES, "Home and evacuation details are covered.", "yes"),
                opt(KINDA, "Some flood prep is done.", "partial"),
                opt(NO, "We need flood guidance.", "no"))));
        q.add(new AssessmentQuestionDto("earthquakePrep", "Earthquake", "Local hazard",
                "Have you secured heavy items and practiced what to do during shaking?", "single", true, "earthquake", List.of(
                opt(YES, "Home safety and drills are covered.", "yes"),
                opt(KINDA, "Some securing or planning is done.", "partial"),
                opt(NO, "We need earthquake guidance.", "no"))));
        q.add(new AssessmentQuestionDto("wildfirePrep", "Wildfire", "Local hazard",
                "Have you planned for smoke, defensible space, and fast evacuation?", "single", true, "wildfire", List.of(
                opt(YES, "Smoke, home, and evacuation plans are covered.", "yes"),
                opt(KINDA, "We have started, but need more detail.", "partial"),
                opt(NO, "We need wildfire guidance.", "no"))));
        q.add(new AssessmentQuestionDto("hurricanePrep", "Hurricane", "Local hazard",
                "Have you planned for wind, flooding, outages, and evacuation?", "single", true, "hurricane", List.of(
                opt(YES, "Home prep, supplies, and evacuation are covered.", "yes"),
                opt(KINDA, "We have some supplies or plans.", "partial"),
                opt(NO, "We need hurricane guidance.", "no"))));
        return List.copyOf(q);
    }

    // ---------------------------------------------------------------------

    private final HouseholdResolver householdResolver;
    private final DemographicRepo demographicRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final OriginLocationRepo originLocationRepo;

    public AssessmentScoringService(HouseholdResolver householdResolver,
                                    DemographicRepo demographicRepo,
                                    MealPlanDataRepo mealPlanDataRepo,
                                    EvacuationPlanRepo evacuationPlanRepo,
                                    MeetingPlaceRepo meetingPlaceRepo,
                                    EmergencyContactGroupRepo emergencyContactGroupRepo,
                                    OriginLocationRepo originLocationRepo) {
        this.householdResolver = householdResolver;
        this.demographicRepo = demographicRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.originLocationRepo = originLocationRepo;
    }

    /** Empty signals for anonymous callers — the guest path of the FE model. */
    public static PlanSignalsDto guestSignals() {
        return new PlanSignalsDto(false, false, false, false, false, false, false, false);
    }

    /** Saved-plan signals for an authenticated caller (port of getPlanSignals). */
    @Transactional(readOnly = true)
    public PlanSignalsDto planSignalsFor(String email) {
        String lower = email.trim().toLowerCase(Locale.ROOT);
        var demographic = demographicRepo.findByOwnerEmailIgnoreCase(email).orElse(null);
        int pets = demographic == null ? 0
                : demographic.getDogs() + demographic.getCats() + demographic.getPets();
        boolean hasHousehold = householdResolver.baseHouseholdIdFor(email) != null;
        boolean hasMeal = !mealPlanDataRepo.findOwnerEmailsIn(List.of(lower)).isEmpty();
        boolean hasEvac = !evacuationPlanRepo.findOwnerEmailsIn(List.of(lower)).isEmpty();
        boolean hasMeeting = !meetingPlaceRepo.findByOwnerEmail(email).isEmpty();
        boolean hasContacts = emergencyContactGroupRepo.findByOwnerEmailIgnoreCase(email).stream()
                .anyMatch(g -> g.getContacts() != null && !g.getContacts().isEmpty());
        boolean hasOrigins = !originLocationRepo.findByOwnerEmailIgnoreCase(email).isEmpty();
        return new PlanSignalsDto(hasHousehold, demographic != null, hasMeal,
                hasEvac, hasMeeting, hasContacts, hasOrigins, pets > 0);
    }

    /**
     * The weighted scoring — mirrors {@code buildAssessmentSummary} exactly:
     * hazard fields only count when their hazard is selected; "na"/blank
     * excluded; score = round(weightedBase×0.9 + selfGrade×0.1); bands at
     * 85/60; gaps severity→priority sorted; active hazards bump severity to
     * critical and priority by −12.
     */
    public AssessmentSummaryDto buildSummary(Map<String, Object> responses,
                                             PlanSignalsDto signals,
                                             boolean isAuthenticated,
                                             Set<String> activeHazards) {
        Map<String, Object> r = responses == null ? Map.of() : responses;
        List<String> selectedHazards = stringList(r.get("naturalDisasters"));
        Set<String> activeSet = activeHazards == null ? Set.of() : activeHazards;

        List<AssessmentItemDto> gaps = new ArrayList<>();
        List<AssessmentItemDto> strengths = new ArrayList<>();
        List<GuideActionDto> guideActions = new ArrayList<>();
        double totalWeight = 0;
        double earned = 0;

        for (Map.Entry<String, FieldMeta> e : FIELD_REGISTRY.entrySet()) {
            String key = e.getKey();
            FieldMeta meta = e.getValue();
            if (meta.hazardKey() != null && !selectedHazards.contains(meta.hazardKey())) continue;

            Object valueObj = r.get(key);
            String value = valueObj == null ? null : String.valueOf(valueObj);
            if (value == null || value.isEmpty() || "na".equals(value)) continue;

            Double score = answerScore(value);
            if (score == null) continue;

            totalWeight += meta.weight();
            earned += score * meta.weight();

            boolean savedPlanMatch = planMatchesField(key, signals);
            boolean activeHazard = meta.hazardKey() != null && activeSet.contains(meta.hazardKey());
            String severity = activeHazard ? "critical" : meta.severity();
            int priority = activeHazard ? meta.priority() - 12 : meta.priority();
            String source = savedPlanMatch && score == 1.0 ? "both"
                    : savedPlanMatch ? "saved_plan_started" : "self_report";

            if (score == 1.0) {
                strengths.add(new AssessmentItemDto(key, meta.label(), meta.route(), meta.cta(),
                        severity, priority, meta.hazardKey(), meta.guideSlug(), meta.pillarKey(), source,
                        savedPlanMatch
                                ? "You reported this as ready, and SitPrep has related saved plan data."
                                : "You reported this as ready."));
                continue;
            }

            String reason = savedPlanMatch
                    ? "You have started this in SitPrep, but your answer says it still needs attention."
                    : "no".equals(value)
                        ? "You marked " + meta.label().toLowerCase(Locale.ROOT) + " as not ready yet."
                        : "You marked " + meta.label().toLowerCase(Locale.ROOT) + " as partly ready.";
            gaps.add(new AssessmentItemDto(key, meta.label(), meta.route(), meta.cta(),
                    severity, priority, meta.hazardKey(), meta.guideSlug(), meta.pillarKey(), source, reason));
        }

        for (String hazardKey : selectedHazards) {
            Guide guide = GUIDES.get(hazardKey);
            if (guide == null) continue;
            Object prepObj = r.get(hazardKey + "Prep");
            String prep = prepObj == null ? null : String.valueOf(prepObj);
            boolean weak = prep == null || prep.isEmpty()
                    || "no".equals(prep) || "kinda".equals(prep) || "partial".equals(prep);
            if (!weak) continue;
            guideActions.add(new GuideActionDto(hazardKey, guide.title(), guide.route(),
                    guide.accent(), guide.accentBg(),
                    activeSet.contains(hazardKey)
                            ? guide.tag() + " may be active nearby, and your answer shows room to prepare."
                            : "You selected " + guide.tag().toLowerCase(Locale.ROOT) + " as a local hazard."));
        }

        gaps.sort(Comparator
                .comparingInt((AssessmentItemDto g) -> SEVERITY_RANK.getOrDefault(g.severity(), 9))
                .thenComparingInt(g -> g.priority() == null ? 999 : g.priority()));
        guideActions.sort(Comparator.comparingInt(g -> activeSet.contains(g.hazardKey()) ? 0 : 1));

        double baseScore = totalWeight > 0 ? (earned / totalWeight) * 100 : 0;
        int confidence = intOf(r.get("selfGrade"));
        int score = (int) Math.round(totalWeight > 0 ? baseScore * 0.9 + confidence * 0.1 : confidence);

        String band = score >= 85 ? "strong_foundation" : score >= 60 ? "few_gaps" : "start_with_essentials";
        String[] copy = BAND_COPY.get(band);

        boolean hasCriticalGap = gaps.stream().anyMatch(g -> "critical".equals(g.severity()));
        AssessmentItemDto nextGap = gaps.isEmpty() ? null : gaps.get(0);
        GuideActionDto nextGuide = guideActions.isEmpty() ? null : guideActions.get(0);

        NextActionDto nextAction;
        if (nextGap != null && (hasCriticalGap || nextGuide == null)) {
            nextAction = new NextActionDto(
                    nextGap.hazardKey() != null ? "guide" : "app_step",
                    nextGap.key(),
                    nextGap.hazardKey() != null ? nextGap.label()
                            : "Work on " + nextGap.label().toLowerCase(Locale.ROOT),
                    nextGap.reason(), nextGap.route(), nextGap.cta(),
                    nextGap.hazardKey() == null && !"/ask".equals(nextGap.route()));
        } else if (nextGuide != null) {
            nextAction = new NextActionDto("guide", nextGuide.hazardKey(), nextGuide.title(),
                    nextGuide.reason(), nextGuide.route(), "Read guide", false);
        } else {
            nextAction = new NextActionDto("review", "review", "Review and share your plan",
                    isAuthenticated
                            ? "Your answers show a strong foundation. Keep your plan fresh and share it with your household."
                            : "Your answers show a strong foundation. Create an account if you want to save and share a full plan.",
                    isAuthenticated ? "/view-plan" : "/create-account",
                    isAuthenticated ? "Review my plan" : "Save with free account",
                    !isAuthenticated);
        }

        return new AssessmentSummaryDto(2, Instant.now(),
                isAuthenticated ? "authenticated" : "guest",
                score, band, copy[0], copy[1], confidence,
                selectedHazards, signals, gaps, strengths, nextAction, guideActions);
    }

    // ---------------------------------------------------------------------

    private static Double answerScore(String value) {
        if ("yes".equals(value)) return 1.0;
        if ("kinda".equals(value) || "partial".equals(value)) return 0.5;
        if ("no".equals(value)) return 0.0;
        return null;
    }

    private static boolean planMatchesField(String key, PlanSignalsDto s) {
        if ("foodSupply".equals(key) || "waterSupply".equals(key)) return s.hasMealPlan();
        if ("emergencyContacts".equals(key) || "communicationPlan".equals(key)) return s.hasContacts();
        if ("evacuationPlan".equals(key)) return s.hasEvacuationPlan() || s.hasMeetingPlaces();
        if ("petPreparedness".equals(key)) return s.hasPets();
        if ("specialNeedsPreparedness".equals(key)) return s.hasDemographics();
        return false;
    }

    private static List<String> stringList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        return list.stream().filter(x -> x != null).map(String::valueOf).toList();
    }

    private static int intOf(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? 0 : (int) Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
