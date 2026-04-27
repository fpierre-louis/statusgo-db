package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.MeDto;
import io.sitprep.sitprepapi.dto.MeDto.*;
import io.sitprep.sitprepapi.dto.MePlansDto;
import io.sitprep.sitprepapi.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Assembles the consolidated {@link MeDto} for GET /api/me/{uid}.
 *
 * <p>Plans (mealPlan, evacuation, meetingPlaces, originLocations, contacts)
 * were split out of the main DTO and live behind {@code GET /api/me/{uid}/plans}
 * (returns {@link MePlansDto}). The dashboard / nav / status surfaces don't
 * need them; only {@code me/plans/*} pages do. Readiness existence flags
 * stay on {@link MeDto#readiness()} via cheap {@code existsBy*} queries.</p>
 *
 * <p>Design note: this endpoint is the single hydration point for the client's
 * MeContext, so partial data beats a 500. Every sub-fetch is wrapped in
 * {@link #safeGet} — if demographics has a duplicate row, meal plan table is
 * mid-migration, or a single group id is dirty, we log + degrade to null/empty
 * rather than failing the whole payload.</p>
 */
@Service
public class MeService {

    private static final Logger log = LoggerFactory.getLogger(MeService.class);
    private static final int DTO_VERSION = 2;

    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final DemographicRepo demographicRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final OriginLocationRepo originLocationRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;

    public MeService(
            UserInfoRepo userInfoRepo,
            GroupRepo groupRepo,
            DemographicRepo demographicRepo,
            MealPlanDataRepo mealPlanDataRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            OriginLocationRepo originLocationRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo
    ) {
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.demographicRepo = demographicRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.originLocationRepo = originLocationRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
    }

    @Transactional(readOnly = true)
    public Optional<MeDto> buildMe(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        return userInfoRepo.findByFirebaseUid(firebaseUid.trim()).map(this::assemble);
    }

    /** Lazy plans payload — only fetched when {@code me/plans/*} pages need it. */
    @Transactional(readOnly = true)
    public Optional<MePlansDto> buildMePlans(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        return userInfoRepo.findByFirebaseUid(firebaseUid.trim()).map(this::assemblePlans);
    }

    private MeDto assemble(UserInfo user) {
        String email = Optional.ofNullable(user.getUserEmail())
                .map(String::trim).map(String::toLowerCase).orElse("");
        String logCtx = "uid=" + user.getFirebaseUid() + " email=" + email;

        // Demographic stays inline — used for HouseholdDto.demographic AND
        // for the demographicsDone readiness flag, so a single fetch.
        Demographic demographic = safeGet("demographic", logCtx,
                () -> demographicRepo.findFirstByOwnerEmailIgnoreCaseOrderByIdDesc(email)
                        .orElse(null),
                null);

        // Plan readiness flags via cheap existence checks. The full plan
        // entities are only fetched when /api/me/{uid}/plans is hit.
        boolean hasMealPlan = email.isBlank() ? false : safeGet("hasMealPlan", logCtx,
                () -> mealPlanDataRepo.existsByOwnerEmailIgnoreCase(email), false);
        boolean hasEvac = email.isBlank() ? false : safeGet("hasEvac", logCtx,
                () -> evacuationPlanRepo.existsByOwnerEmailIgnoreCase(email), false);
        boolean hasContacts = email.isBlank() ? false : safeGet("hasContacts", logCtx,
                () -> emergencyContactGroupRepo.existsByOwnerEmailIgnoreCase(email), false);

        // Authoritative membership lookup: read from the Group side, not the
        // denormalized UserInfo.managedGroupIDs/joinedGroupIDs cache. That
        // cache can drift; Group.adminEmails/memberEmails are source of truth.
        List<Group> adminGroupList = safeGet("groupRepo.findByAdminEmail", logCtx,
                () -> email.isBlank() ? List.<Group>of() : groupRepo.findByAdminEmail(email),
                List.of());
        List<Group> memberGroupList = safeGet("groupRepo.findByMemberEmail", logCtx,
                () -> email.isBlank() ? List.<Group>of() : groupRepo.findByMemberEmail(email),
                List.of());

        Map<String, Group> groupsById = new LinkedHashMap<>();
        for (Group g : adminGroupList) {
            if (g != null && g.getGroupId() != null) groupsById.putIfAbsent(g.getGroupId(), g);
        }
        for (Group g : memberGroupList) {
            if (g != null && g.getGroupId() != null) groupsById.putIfAbsent(g.getGroupId(), g);
        }

        Set<String> adminGroupIds = new HashSet<>();
        for (Group g : adminGroupList) {
            if (g != null && g.getGroupId() != null) adminGroupIds.add(g.getGroupId());
        }

        Group household = groupsById.values().stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .findFirst()
                .orElse(null);

        List<GroupSummary> managed = new ArrayList<>();
        List<GroupSummary> joined = new ArrayList<>();
        for (Group g : groupsById.values()) {
            if (household != null && g.getGroupId() != null
                    && g.getGroupId().equals(household.getGroupId())) continue;
            GroupSummary summary = toGroupSummary(g, email);
            boolean isOwner = email != null && !email.isBlank()
                    && email.equalsIgnoreCase(g.getOwnerEmail());
            if (isOwner || adminGroupIds.contains(g.getGroupId())) managed.add(summary);
            else joined.add(summary);
        }

        HouseholdDto householdDto = household == null ? null
                : toHouseholdDto(household, demographic);

        ReadinessDto readiness = computeReadiness(
                user, householdDto, demographic != null, hasMealPlan, hasEvac, hasContacts
        );

        return new MeDto(
                toProfile(user),
                householdDto,
                new GroupsDto(managed, joined),
                readiness,
                new MetaDto(Instant.now(), DTO_VERSION)
        );
    }

    private MePlansDto assemblePlans(UserInfo user) {
        String email = Optional.ofNullable(user.getUserEmail())
                .map(String::trim).map(String::toLowerCase).orElse("");
        String logCtx = "uid=" + user.getFirebaseUid() + " email=" + email + " plans";

        MealPlanData mealPlan = safeGet("mealPlan", logCtx,
                () -> mealPlanDataRepo.findFirstByOwnerEmailIgnoreCase(email).orElse(null),
                null);
        List<EvacuationPlan> evacPlans = safeGet("evacPlans", logCtx,
                () -> evacuationPlanRepo.findByOwnerEmail(email),
                List.of());
        List<MeetingPlace> meetingPlaces = safeGet("meetingPlaces", logCtx,
                () -> meetingPlaceRepo.findByOwnerEmail(email),
                List.of());
        List<OriginLocation> originLocations = safeGet("originLocations", logCtx,
                () -> originLocationRepo.findByOwnerEmailIgnoreCase(email),
                List.of());
        List<EmergencyContactGroup> emergencyGroups = safeGet("emergencyContactGroups", logCtx,
                () -> emergencyContactGroupRepo.findByOwnerEmailIgnoreCase(email),
                List.of());

        return new MePlansDto(
                mealPlan == null ? null : toMealPlanSummary(mealPlan),
                evacPlans.stream().map(this::toEvacSummary).toList(),
                meetingPlaces.stream().map(this::toMeetingPlaceSummary).toList(),
                originLocations.stream().map(this::toOriginLocationSummary).toList(),
                emergencyGroups.stream().map(this::toEmergencyContactGroupSummary).toList(),
                new MePlansDto.MetaDto(Instant.now(), DTO_VERSION)
        );
    }

    /** Run a repo call; on any exception, log and return {@code fallback}. */
    private <T> T safeGet(String step, String logCtx, Supplier<T> op, T fallback) {
        try {
            T result = op.get();
            return result == null ? fallback : result;
        } catch (Exception e) {
            log.warn("MeService: sub-fetch [{}] failed ({}). Using fallback. cause={}",
                    step, logCtx, e.getMessage());
            return fallback;
        }
    }

    private ProfileDto toProfile(UserInfo u) {
        SelfStatusDto status = new SelfStatusDto(
                u.getUserStatus(), u.getStatusColor(), u.getUserStatusLastUpdated()
        );
        return new ProfileDto(
                u.getId(),
                u.getFirebaseUid(),
                Optional.ofNullable(u.getUserEmail()).map(String::toLowerCase).orElse(null),
                u.getUserFirstName(),
                u.getUserLastName(),
                u.getTitle(),
                u.getPhone(),
                u.getAddress(),
                u.getLatitude(),
                u.getLongitude(),
                u.getProfileImageURL(),
                u.getSubscription(),
                status
        );
    }

    private HouseholdDto toHouseholdDto(Group g, Demographic d) {
        DemographicDto demoDto = d == null ? null : new DemographicDto(
                d.getAdults(), d.getKids(), d.getInfants(),
                d.getDogs(), d.getCats(), d.getPets()
        );
        int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
        int adminCount = g.getAdminEmails() == null ? 0 : g.getAdminEmails().size();
        return new HouseholdDto(
                g.getGroupId(),
                g.getGroupName(),
                g.getAddress(),
                g.getLatitude(),
                g.getLongitude(),
                g.getZipCode(),
                memberCount,
                adminCount,
                demoDto,
                null
        );
    }

    private GroupSummary toGroupSummary(Group g, String userEmail) {
        String role = resolveRole(g, userEmail);
        int memberCount = g.getMemberCount() != null ? g.getMemberCount()
                : (g.getMemberEmails() == null ? 0 : g.getMemberEmails().size());
        int pendingCount = g.getPendingMemberEmails() == null ? 0 : g.getPendingMemberEmails().size();
        return new GroupSummary(
                g.getGroupId(),
                g.getGroupName(),
                g.getGroupType(),
                memberCount,
                pendingCount,
                role,
                g.getAlert(),
                g.getUpdatedAt()
        );
    }

    private String resolveRole(Group g, String userEmail) {
        if (userEmail != null && userEmail.equalsIgnoreCase(g.getOwnerEmail())) return "owner";
        if (g.getAdminEmails() != null && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(userEmail))) return "admin";
        return "member";
    }

    private MePlansDto.MealPlanSummary toMealPlanSummary(MealPlanData m) {
        Integer qty = m.getPlanDuration() == null ? null : m.getPlanDuration().getQuantity();
        String unit = m.getPlanDuration() == null ? null : m.getPlanDuration().getUnit();
        int planCount = m.getMealPlan() == null ? 0 : m.getMealPlan().size();
        return new MePlansDto.MealPlanSummary(m.getId(), qty, unit, m.getNumberOfMenuOptions(), planCount);
    }

    private MePlansDto.EvacPlanSummary toEvacSummary(EvacuationPlan e) {
        return new MePlansDto.EvacPlanSummary(
                e.getId(), e.getName(), e.getOrigin(), e.getDestination(), e.isDeploy(),
                e.getShelterName(), e.getLat(), e.getLng(), e.getTravelMode()
        );
    }

    private MePlansDto.MeetingPlaceSummary toMeetingPlaceSummary(MeetingPlace m) {
        return new MePlansDto.MeetingPlaceSummary(
                m.getId(), m.getName(), m.getLocation(), m.getAddress(),
                m.getPhoneNumber(), m.getLat(), m.getLng(), m.isDeploy()
        );
    }

    private MePlansDto.OriginLocationSummary toOriginLocationSummary(OriginLocation o) {
        return new MePlansDto.OriginLocationSummary(
                o.getId(), o.getName(), o.getAddress(), o.getLat(), o.getLng()
        );
    }

    private MePlansDto.EmergencyContactGroupSummary toEmergencyContactGroupSummary(EmergencyContactGroup g) {
        int count = g.getContacts() == null ? 0 : g.getContacts().size();
        return new MePlansDto.EmergencyContactGroupSummary(g.getId(), g.getName(), count);
    }

    /**
     * Readiness now takes existence flags directly — the full plan entities
     * aren't needed for the dashboard ring, only whether each step is done.
     * Saves ~5 list fetches on every /me hit.
     */
    private ReadinessDto computeReadiness(
            UserInfo u,
            HouseholdDto household,
            boolean demographicsDone,
            boolean mealPlanDone,
            boolean evacDone,
            boolean contactsDone
    ) {
        boolean profileDone =
                u.getUserFirstName() != null && !u.getUserFirstName().isBlank()
                && u.getAddress() != null && !u.getAddress().isBlank();
        boolean householdDone = household != null;

        List<ReadinessStep> steps = List.of(
                new ReadinessStep("profile", profileDone),
                new ReadinessStep("household", householdDone),
                new ReadinessStep("demographics", demographicsDone),
                new ReadinessStep("mealPlan", mealPlanDone),
                new ReadinessStep("evacuation", evacDone),
                new ReadinessStep("contacts", contactsDone)
        );

        long done = steps.stream().filter(ReadinessStep::done).count();
        int percent = (int) Math.round((done * 100.0) / steps.size());
        return new ReadinessDto(percent, steps);
    }
}
