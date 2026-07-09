package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.EmergencyContactGroupDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.StatusRollup;
import io.sitprep.sitprepapi.dto.HouseholdReadinessDto;
import io.sitprep.sitprepapi.dto.MeDto.PillarCounts;
import io.sitprep.sitprepapi.dto.MeDto.PillarRollup;
import io.sitprep.sitprepapi.dto.MeetingPlaceDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.CommsReadinessDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.CommsRecommendationDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.PillarScoreDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.PulseDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.PulsePillarDto;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Global Readiness Engine (Thick-Server refactor — Phase 1.3 executed).
 * Three responsibilities, all previously scattered across the FE:
 *
 * <ol>
 *   <li><b>dominantStatus</b> — derived from the Phase 1 accountability
 *       rollup via {@link StatusRollups} (the SAME math the member-view
 *       ships; zero duplication of aggregation).</li>
 *   <li><b>Pulse</b> — the 4-pillar readiness percents + overall + tier.
 *       Faithful port of {@code useReadinessPulse.js}: displayed pct =
 *       {@code completed / max(added, recommendedMin)}; soft defaults
 *       (80/60/40) when a pillar has no tasks yet so brand-new households
 *       don't read "0% — you're failing"; plan pillar reads 100 during an
 *       active check-in; family ramps on roster size (manual dependents
 *       count). Numbers must not shift on cutover.</li>
 *   <li><b>Comms evaluation</b> — actionable Contacts/Meeting-place gaps,
 *       computed over the Phase 3 hardened contracts
 *       ({@link EmergencyContactGroupDto}, {@link MeetingPlaceDto} — mapped
 *       through the same factories the REST layer uses, so the evaluation
 *       reads the wire contract, not entity internals). Coverage rules
 *       mirror the contacts page's {@code computeContactCoverage} plus the
 *       FEMA/Red Cross typed contact and meeting-place requirements.</li>
 * </ol>
 */
@Service
public class HouseholdReadinessService {

    /** Mirrors useReadinessPulse.PILLAR_RECOMMENDED_MIN. */
    private static final int MIN_SUPPLIES = 5, MIN_PLAN = 5, MIN_PRACTICE = 4, MIN_FAMILY = 4;

    private final HouseholdManualMemberService manualMemberService;
    private final HouseholdAccompanimentService accompanimentService;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final UserSavedLocationService savedLocationService;

    public HouseholdReadinessService(HouseholdManualMemberService manualMemberService,
                                     HouseholdAccompanimentService accompanimentService,
                                     EmergencyContactGroupRepo emergencyContactGroupRepo,
                                     MeetingPlaceRepo meetingPlaceRepo,
                                     UserSavedLocationService savedLocationService) {
        this.manualMemberService = manualMemberService;
        this.accompanimentService = accompanimentService;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.savedLocationService = savedLocationService;
    }

    // ---------------------------------------------------------------------
    // dominantStatus (Phase 1.3 — zero-duplication derivation)
    // ---------------------------------------------------------------------

    /**
     * Dominant status for a household, from the SAME rollup math the
     * member-view ships. Caller supplies the (batched) UserInfo map so the
     * org dashboard resolves N households with ONE userinfo query.
     */
    @Transactional(readOnly = true)
    public String dominantStatusFor(Group household, Map<String, UserInfo> byEmail) {
        if (household == null) return "UNKNOWN";
        List<String> emails = household.getMemberEmails() == null
                ? List.of() : household.getMemberEmails();
        boolean alertActive = "Active".equalsIgnoreCase(household.getAlert());
        StatusRollup rollup = StatusRollups.compute(
                emails, byEmail,
                manualMemberService.list(household.getGroupId()),
                accompanimentService.list(household.getGroupId()),
                alertActive, household.getUpdatedAt());
        return StatusRollups.dominantStatus(rollup);
    }

    // ---------------------------------------------------------------------
    // Pulse (4 pillars + overall + tier) — port of useReadinessPulse
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PulseDto pulseFor(PillarRollup counts, Group household) {
        boolean alertActive = household != null && "Active".equalsIgnoreCase(household.getAlert());
        int memberCount = household == null || household.getMemberEmails() == null
                ? 0 : household.getMemberEmails().size();
        int manualCount = household == null ? 0
                : manualMemberService.list(household.getGroupId()).size();

        Integer suppliesLive = pillarPct(counts == null ? null : counts.supplies(), MIN_SUPPLIES);
        Integer planLive = pillarPct(counts == null ? null : counts.plan(), MIN_PLAN);
        Integer practiceLive = pillarPct(counts == null ? null : counts.practice(), MIN_PRACTICE);
        Integer familyLive = pillarPct(counts == null ? null : counts.family(), MIN_FAMILY);

        // Family fallback: roster ramp (full household reads 100; solo 50).
        int totalFamily = memberCount + manualCount;
        int familyRoster = totalFamily == 0 ? 0
                : Math.min(100, (int) Math.round((totalFamily * 100.0) / Math.max(2, totalFamily)));

        // No soft defaults: a pillar with no tasks scores 0. Readiness is
        // strictly requirement satisfaction + actual data (task counts, roster,
        // alert state) — never an assumed baseline. The Location-Based Risk
        // epic depends on honest zeros, not an inflated floor.
        List<PulsePillarDto> pillars = List.of(
                new PulsePillarDto("supplies",
                        suppliesLive != null ? suppliesLive : 0,
                        hint(counts == null ? null : counts.supplies(), MIN_SUPPLIES, "No supplies tasks yet")),
                new PulsePillarDto("plan",
                        alertActive ? 100 : (planLive != null ? planLive : 0),
                        alertActive ? "Active right now"
                                : hint(counts == null ? null : counts.plan(), MIN_PLAN, "No plan tasks yet")),
                new PulsePillarDto("practice",
                        practiceLive != null ? practiceLive : 0,
                        hint(counts == null ? null : counts.practice(), MIN_PRACTICE, "Not practiced yet")),
                new PulsePillarDto("family",
                        familyLive != null ? familyLive : familyRoster,
                        familyLive != null
                                ? hint(counts.family(), MIN_FAMILY, null)
                                : totalFamily == 0 ? "No household yet"
                                : totalFamily == 1 ? "Just you" : totalFamily + " of us"));

        int overall = (int) Math.round(
                pillars.stream().mapToInt(PulsePillarDto::pct).average().orElse(0));
        return new PulseDto(pillars, overall, tierKey(overall), tierLabel(overall));
    }

    /** Mirrors useReadinessPulse.pillarPctFromCounts. */
    private static Integer pillarPct(PillarCounts c, int recommendedMin) {
        if (c == null) return null;
        int added = c.added();
        if (added == 0) return null; // no tasks for this pillar → caller scores it 0
        int denom = Math.max(added, Math.max(recommendedMin, 1));
        return Math.min(100, (int) Math.round((c.completed() * 100.0) / denom));
    }

    private static String hint(PillarCounts c, int recommendedMin, String fallback) {
        if (c == null || c.added() == 0) return fallback;
        return c.completed() + " of " + Math.max(c.added(), recommendedMin) + " done";
    }

    /** Mirrors useReadinessPulse.readyTier thresholds. */
    static String tierKey(int pct) {
        if (pct >= 90) return "set";
        if (pct >= 70) return "good";
        if (pct >= 50) return "coming";
        if (pct >= 30) return "gaps";
        return "start";
    }

    static String tierLabel(int pct) {
        if (pct >= 90) return "We're set";
        if (pct >= 70) return "In good shape";
        if (pct >= 50) return "Coming along";
        if (pct >= 30) return "Some gaps to close";
        return "Just getting started";
    }

    public HouseholdReadinessDto assembleHouseholdReadiness(Group household,
                                                            PulseDto pulse,
                                                            CommsReadinessDto comms,
                                                            String dominantStatus) {
        HouseholdReadinessDto dto = new HouseholdReadinessDto();
        if (household != null) {
            dto.setGroupId(household.getGroupId());
            dto.setGroupName(household.getGroupName());
            dto.setOwnerName(household.getOwnerName());
            dto.setOwnerEmail(household.getOwnerEmail());
            dto.setMemberCount(household.getMemberEmails() == null ? 0 : household.getMemberEmails().size());
        }
        dto.setDominantStatus(dominantStatus);
        dto.setComms(comms);
        dto.setPulse(pulse);

        List<PillarScoreDto> scores = pillarScoresFor(pulse, comms);
        dto.setPillarScores(scores);
        dto.setReadinessPercent(overallFromPillarScores(scores));
        return dto;
    }

    public List<PillarScoreDto> pillarScoresFor(PulseDto pulse, CommsReadinessDto comms) {
        List<PillarScoreDto> scores = new ArrayList<>();
        if (pulse != null && pulse.pillars() != null) {
            pulse.pillars().forEach(p -> {
                String key = normalizePillarKey(p.key());
                scores.add(new PillarScoreDto(
                        key,
                        pillarLabel(key),
                        p.pct(),
                        tierKey(p.pct()),
                        p.hint()));
            });
        }
        if (comms != null) {
            List<CommsRecommendationDto> recs = comms.recommendations() == null
                    ? List.of() : comms.recommendations();
            String hint = recs.isEmpty()
                    ? "FEMA/Red Cross baseline complete"
                    : recs.get(0).label();
            scores.add(new PillarScoreDto(
                    "communications",
                    "Communications",
                    comms.score(),
                    tierKey(comms.score()),
                    hint));
        }
        return List.copyOf(scores);
    }

    public int overallFromPillarScores(List<PillarScoreDto> scores) {
        if (scores == null || scores.isEmpty()) return 0;
        return (int) Math.round(scores.stream().mapToInt(PillarScoreDto::score).average().orElse(0));
    }

    private static String normalizePillarKey(String key) {
        if ("plan".equals(key)) return "evacuation_plan";
        return key == null ? "unknown" : key;
    }

    private static String pillarLabel(String key) {
        return switch (key) {
            case "supplies" -> "Supplies";
            case "evacuation_plan" -> "Evacuation Plan";
            case "practice" -> "Practice";
            case "family" -> "Family";
            case "communications" -> "Communications";
            default -> "Readiness";
        };
    }

    // ---------------------------------------------------------------------
    // Communications / Contacts pillar evaluation (Phase 3 contracts)
    // ---------------------------------------------------------------------

    /** Evaluate the comms pillar for a plan owner (household head). */
    @Transactional(readOnly = true)
    public CommsReadinessDto commsFor(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) return evaluateComms(List.of(), List.of());

        List<EmergencyContactGroupDto> contactGroups = emergencyContactGroupRepo
                .findByOwnerEmailIgnoreCase(ownerEmail).stream()
                .map(EmergencyContactGroupDto::from)
                .toList();

        // Meeting places through the hardened contract — including the
        // stored-or-derived tierKey (home coords resolve the derivation).
        Optional<UserSavedLocation> home = savedLocationService.homeFor(ownerEmail);
        Double homeLat = home.map(UserSavedLocation::getLatitude).orElse(null);
        Double homeLng = home.map(UserSavedLocation::getLongitude).orElse(null);
        List<MeetingPlaceDto> places = meetingPlaceRepo.findByOwnerEmail(ownerEmail).stream()
                .map(p -> MeetingPlaceDto.from(p, homeLat, homeLng))
                .toList();

        return evaluateComms(contactGroups, places);
    }

    /** Pure evaluation over the hardened DTO contracts (unit-testable). */
    public CommsReadinessDto evaluateComms(List<EmergencyContactGroupDto> contactGroups,
                                           List<MeetingPlaceDto> places) {
        List<EmergencyContactGroupDto.ContactDto> all = contactGroups.stream()
                .flatMap(g -> g.contacts().stream())
                .toList();

        boolean hasContacts = !all.isEmpty();
        boolean hasOutOfArea = all.stream().anyMatch(c -> hasContactType(c, "OUT_OF_AREA"));
        boolean hasLocal = all.stream().anyMatch(c -> hasContactType(c, "LOCAL"));
        boolean hasMedical = all.stream().anyMatch(c -> hasContactType(c, "MEDICAL"));
        boolean missingPhones = hasContacts && all.stream().anyMatch(c -> !usablePhone(c.phone()));
        boolean missingAlt = hasContacts && all.stream().noneMatch(c ->
                notBlank(c.email()) || notBlank(c.address()));

        boolean indoorSafeRoom = hasMeetingTier(places, "INDOOR_SAFE_ROOM");
        boolean outsideHome = hasMeetingTier(places, "OUTSIDE_HOME");
        boolean outOfTown = hasMeetingTier(places, "OUT_OF_TOWN");

        int contactsComplete = (hasLocal ? 1 : 0) + (hasOutOfArea ? 1 : 0);
        int meetingComplete = (indoorSafeRoom ? 1 : 0) + (outsideHome ? 1 : 0) + (outOfTown ? 1 : 0);
        int contactsScore = percent(contactsComplete, 2);
        int meetingScore = percent(meetingComplete, 3);
        int score = percent(contactsComplete + meetingComplete, 5);

        // Legacy range flags remain for older screens while the UI migrates
        // to the doctrine-backed meetingTier fields.
        boolean nearHome = hasTier(places, "near_home");
        boolean neighborhood = hasTier(places, "neighborhood");
        boolean inTown = hasTier(places, "in_town");
        boolean outOfArea = hasTier(places, "out_of_area");

        List<CommsRecommendationDto> recs = new ArrayList<>();
        if (!hasContacts) {
            recs.add(new CommsRecommendationDto("add_contacts",
                    "Add your first emergency contacts",
                    "Add contacts", "/emergency-contacts"));
        } else {
            if (!hasOutOfArea) recs.add(new CommsRecommendationDto("out_of_area_contact",
                    "Pick one out-of-area emergency contact",
                    "Add out-of-area contact", "/emergency-contacts"));
            if (!hasLocal) recs.add(new CommsRecommendationDto("local_contact",
                    "Add one local emergency contact",
                    "Add local contact", "/emergency-contacts"));
            if (!hasMedical) recs.add(new CommsRecommendationDto("medical_contact",
                    "Include a doctor, clinic, or pharmacy",
                    "Add medical contact", "/emergency-contacts"));
            if (missingPhones) recs.add(new CommsRecommendationDto("contact_phones",
                    "Some contacts are missing a usable phone number",
                    "Fill in phone numbers", "/emergency-contacts"));
            if (missingAlt) recs.add(new CommsRecommendationDto("contact_alt_method",
                    "Add a second way to reach people — email or address",
                    "Add backup details", "/emergency-contacts"));
        }
        if (!indoorSafeRoom) recs.add(new CommsRecommendationDto("meetup_indoor_safe_room",
                "Choose an indoor safe room for shelter-in-place emergencies",
                "Set indoor safe room", "/evacuation-wizard"));
        if (!outsideHome) recs.add(new CommsRecommendationDto("meetup_outside_home",
                "Choose a meeting spot just outside the home",
                "Set meeting place", "/evacuation-wizard"));
        if (!outOfTown) recs.add(new CommsRecommendationDto("meetup_out_of_town",
                "Choose an out-of-town meeting place or destination",
                "Set meeting place", "/evacuation-wizard"));

        return new CommsReadinessDto(
                score, contactsScore, meetingScore,
                hasContacts, all.size(),
                hasOutOfArea, hasLocal, hasMedical,
                missingPhones, missingAlt,
                !hasLocal, !hasOutOfArea,
                !indoorSafeRoom, !outsideHome, !outOfTown,
                !nearHome, !neighborhood, !inTown, !outOfArea,
                List.copyOf(recs));
    }

    // ---------------------------------------------------------------------

    private static boolean hasTier(List<MeetingPlaceDto> places, String tier) {
        return places.stream().anyMatch(p -> tier.equals(p.tierKey()));
    }

    private static boolean hasMeetingTier(List<MeetingPlaceDto> places, String tier) {
        return places.stream().anyMatch(p -> tier.equals(p.meetingTier()));
    }

    private static boolean hasContactType(EmergencyContactGroupDto.ContactDto contact, String type) {
        return type.equals(contact.contactType());
    }

    private static int percent(int complete, int total) {
        if (total <= 0) return 0;
        return Math.min(100, (int) Math.round((complete * 100.0) / total));
    }

    /** ≥7 digits reads as usable — mirrors the contacts page's phone check. */
    private static boolean usablePhone(String phone) {
        if (phone == null) return false;
        int digits = 0;
        for (char c : phone.toCharArray()) if (Character.isDigit(c)) digits++;
        return digits >= 7;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
