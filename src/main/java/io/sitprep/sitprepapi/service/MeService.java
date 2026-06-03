package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.HouseholdPlanDto;
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
    private static final int DTO_VERSION = 3;
    private static final TypeReference<Map<String, Object>> ASSESSMENT_SUMMARY_TYPE =
            new TypeReference<>() {};

    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final DemographicRepo demographicRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final OriginLocationRepo originLocationRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final PlanActivationRepo planActivationRepo;
    private final io.sitprep.sitprepapi.repo.PostRepo postRepo;
    private final io.sitprep.sitprepapi.repo.GroupReadStateRepo groupReadStateRepo;
    private final io.sitprep.sitprepapi.repo.GroupPostRepo groupPostRepo;
    private final io.sitprep.sitprepapi.repo.GroupMutePrefRepo groupMutePrefRepo;
    private final io.sitprep.sitprepapi.repo.HouseholdRitualRepo householdRitualRepo;
    private final ObjectMapper objectMapper;

    public MeService(
            UserInfoRepo userInfoRepo,
            GroupRepo groupRepo,
            DemographicRepo demographicRepo,
            MealPlanDataRepo mealPlanDataRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            OriginLocationRepo originLocationRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo,
            PlanActivationRepo planActivationRepo,
            io.sitprep.sitprepapi.repo.PostRepo postRepo,
            io.sitprep.sitprepapi.repo.GroupReadStateRepo groupReadStateRepo,
            io.sitprep.sitprepapi.repo.GroupPostRepo groupPostRepo,
            io.sitprep.sitprepapi.repo.GroupMutePrefRepo groupMutePrefRepo,
            io.sitprep.sitprepapi.repo.HouseholdRitualRepo householdRitualRepo,
            ObjectMapper objectMapper
    ) {
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.demographicRepo = demographicRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.originLocationRepo = originLocationRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.planActivationRepo = planActivationRepo;
        this.postRepo = postRepo;
        this.groupReadStateRepo = groupReadStateRepo;
        this.groupPostRepo = groupPostRepo;
        this.groupMutePrefRepo = groupMutePrefRepo;
        this.householdRitualRepo = householdRitualRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<MeDto> buildMe(String firebaseUid) {
        return loadUser(firebaseUid).map(this::assemble);
    }

    /** Lazy plans payload — only fetched when {@code me/plans/*} pages need it. */
    @Transactional(readOnly = true)
    public Optional<MePlansDto> buildMePlans(String firebaseUid) {
        return loadUser(firebaseUid).map(this::assemblePlans);
    }

    /**
     * User lookup with defense-in-depth. Any exception that escapes the
     * repo (SQL error, schema drift, primitive-from-NULL mapping, EAGER
     * collection load failure on a corrupt row, duplicate firebase_uid)
     * degrades to {@link Optional#empty()} so the caller surfaces a clean
     * 404 instead of a 500. The exception is logged with the uid so prod
     * issues are still searchable.
     */
    private Optional<UserInfo> loadUser(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank()) return Optional.empty();
        try {
            return userInfoRepo.findByFirebaseUid(firebaseUid.trim());
        } catch (Exception e) {
            log.error("MeService: user lookup failed uid={} cause={}",
                    firebaseUid, e.getMessage(), e);
            return Optional.empty();
        }
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

        // All households the user belongs to — a user may have several.
        List<Group> householdGroups = groupsById.values().stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .toList();
        Set<String> householdIds = new HashSet<>();
        for (Group g : householdGroups) {
            if (g.getGroupId() != null) householdIds.add(g.getGroupId());
        }

        // Base/main household: the one the user pinned (UserInfo.baseHouseholdId),
        // else fall back to the first household so the dashboard still anchors to
        // something before the backfill assigns a base.
        String baseId = user.getBaseHouseholdId();
        Group baseHousehold = householdGroups.stream()
                .filter(g -> g.getGroupId() != null && g.getGroupId().equals(baseId))
                .findFirst()
                .orElse(householdGroups.isEmpty() ? null : householdGroups.get(0));

        // Member-avatar preview — ONE batched profile lookup for the
        // circle-card + Home-base member stacks (up to 4 emails per circle).
        // Keyed by lowercased email so previewFor() matches case-insensitively.
        java.util.Set<String> previewEmails = new java.util.LinkedHashSet<>();
        for (Group g : groupsById.values()) {
            if (g.getGroupId() != null && householdIds.contains(g.getGroupId())) continue;
            if (g.getMemberEmails() != null)
                g.getMemberEmails().stream().filter(Objects::nonNull).limit(4).forEach(previewEmails::add);
        }
        if (baseHousehold != null && baseHousehold.getMemberEmails() != null)
            baseHousehold.getMemberEmails().stream().filter(Objects::nonNull).limit(4).forEach(previewEmails::add);
        java.util.Map<String, UserInfo> profileMap = new java.util.HashMap<>();
        if (!previewEmails.isEmpty()) {
            for (UserInfo u : userInfoRepo.findByUserEmailIn(new ArrayList<>(previewEmails))) {
                if (u.getUserEmail() != null) profileMap.putIfAbsent(u.getUserEmail().toLowerCase(), u);
            }
        }

        // Per-(user,group) last-read pointers for the unread badge / Unread
        // filter. ONE query; per-group lookup is in-memory. Empty map → all
        // groups read=0 (deliberate "start clean on rollout").
        java.util.Map<String, java.time.Instant> readMap = new java.util.HashMap<>();
        if (email != null && !email.isBlank()) {
            for (var s : groupReadStateRepo.findByUserEmailIgnoreCase(email)) {
                if (s.getGroupId() != null && s.getLastReadAt() != null) {
                    readMap.put(s.getGroupId(), s.getLastReadAt());
                }
            }
        }

        // Per-(user,group) mute + quiet-hours pref. Same one-query-
        // then-in-memory pattern. Past mute deadlines are filtered
        // out at populate time so the wire only carries deadlines
        // the FE should treat as active. Quiet-hours fields are
        // carried verbatim — enforcement decides at dispatch time.
        java.util.Map<String, java.time.Instant> muteMap = new java.util.HashMap<>();
        java.util.Map<String, io.sitprep.sitprepapi.domain.GroupMutePref> prefMap = new java.util.HashMap<>();
        if (email != null && !email.isBlank()) {
            java.time.Instant now = java.time.Instant.now();
            for (var m : groupMutePrefRepo.findByUserEmailIgnoreCase(email)) {
                if (m.getGroupId() == null) continue;
                prefMap.put(m.getGroupId(), m);
                if (m.getMutedUntil() == null) continue;
                if (m.getMutedUntil().isBefore(now)) continue;
                muteMap.put(m.getGroupId(), m.getMutedUntil());
            }
        }

        // Per-group most-recent-post timestamp for the "Active vs Quiet"
        // freshness meta. ONE batched query across every group the user
        // belongs to; groups with no posts yet stay out of the map and
        // fall back to Group.updatedAt in lastActivityFor(). The
        // existing findLatestPostsByGroupIds already correlates MAX(ts)
        // per groupId, so we just keep the timestamp.
        java.util.Map<String, java.time.Instant> latestPostMap = new java.util.HashMap<>();
        if (!groupsById.isEmpty()) {
            java.util.List<String> allIds = new java.util.ArrayList<>(groupsById.keySet());
            try {
                for (var p : groupPostRepo.findLatestPostsByGroupIds(allIds)) {
                    if (p.getGroupId() == null || p.getTimestamp() == null) continue;
                    java.time.Instant cur = latestPostMap.get(p.getGroupId());
                    // The query can return >1 row per group when multiple
                    // posts share the exact MAX(timestamp) — keep the
                    // larger / first non-null.
                    if (cur == null || p.getTimestamp().isAfter(cur)) {
                        latestPostMap.put(p.getGroupId(), p.getTimestamp());
                    }
                }
            } catch (Exception e) {
                log.warn("MeService: latestPostMap fetch failed ({}). Falling back to updatedAt. cause={}",
                        logCtx, e.getMessage());
            }
        }

        List<GroupSummary> managed = new ArrayList<>();
        List<GroupSummary> joined = new ArrayList<>();
        for (Group g : groupsById.values()) {
            // Households live in their own "Your households" section, never in
            // the circles lists.
            if (g.getGroupId() != null && householdIds.contains(g.getGroupId())) continue;
            GroupSummary summary = toGroupSummary(g, email, profileMap, readMap, latestPostMap, muteMap, prefMap);
            boolean isOwner = email != null && !email.isBlank()
                    && email.equalsIgnoreCase(g.getOwnerEmail());
            if (isOwner || adminGroupIds.contains(g.getGroupId())) managed.add(summary);
            else joined.add(summary);
        }

        // Per-household summaries for the "Your households" section.
        List<HouseholdSummary> households = new ArrayList<>();
        for (Group g : householdGroups) {
            boolean isBase = baseHousehold != null
                    && Objects.equals(g.getGroupId(), baseHousehold.getGroupId());
            households.add(toHouseholdSummary(g, email, isBase));
        }

        HouseholdDto householdDto = baseHousehold == null ? null
                : toHouseholdDto(baseHousehold, demographic, profileMap, readMap, latestPostMap, muteMap, prefMap);

        ReadinessDto readiness = computeReadiness(
                user, householdDto, demographic != null, hasMealPlan, hasEvac, hasContacts, email
        );

        // Most recent non-expired activation owned by this user, if any.
        // Drives the Active Dashboard auto-promote on /home (per
        // docs/ECOSYSTEM_INTEGRATION.md step 5). Wrapped in safeGet so a
        // misbehaving query doesn't sink the whole /me payload.
        String activeActivationId = email.isBlank() ? null : safeGet(
                "activeActivationId", logCtx,
                () -> planActivationRepo
                        .findFirstActiveByOwnerEmail(email, Instant.now())
                        .map(a -> a.getId())
                        .orElse(null),
                null);

        return new MeDto(
                toProfile(user),
                householdDto,
                households,
                new GroupsDto(managed, joined),
                readiness,
                activeActivationId,
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

    /**
     * Full household plan document, keyed by householdId — the shared,
     * multi-admin view + printable (Phase 3). Returns the FULL entities the
     * view needs (contacts with phone/medical, meal menu, meeting-place notes,
     * shelter details) plus the household's identity + demographics, rather
     * than the lossy summaries in MePlansDto. Member-gated at the resource.
     */
    @Transactional(readOnly = true)
    public HouseholdPlanDto buildHouseholdPlanDocument(String householdId) {
        String logCtx = "household=" + householdId + " plan-doc";

        Group g = safeGet("hh.group", logCtx,
                () -> groupRepo.findByGroupId(householdId).orElse(null), null);
        Demographic demographic = safeGet("hh.demographic", logCtx,
                () -> demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId).orElse(null), null);
        MealPlanData mealPlan = safeGet("hh.mealPlan", logCtx,
                () -> mealPlanDataRepo.findFirstByHouseholdId(householdId).orElse(null), null);
        List<EvacuationPlan> evacPlans = safeGet("hh.evacPlans", logCtx,
                () -> evacuationPlanRepo.findByHouseholdId(householdId), List.of());
        List<MeetingPlace> meetingPlaces = safeGet("hh.meetingPlaces", logCtx,
                () -> meetingPlaceRepo.findByHouseholdId(householdId), List.of());
        List<OriginLocation> originLocations = safeGet("hh.originLocations", logCtx,
                () -> originLocationRepo.findByHouseholdId(householdId), List.of());
        List<EmergencyContactGroup> contactGroups = safeGet("hh.contactGroups", logCtx,
                () -> emergencyContactGroupRepo.findByHouseholdId(householdId), List.of());

        return new HouseholdPlanDto(
                householdId,
                g == null ? null : g.getGroupName(),
                g == null ? null : g.getAddress(),
                g == null ? null : g.getLatitude(),
                g == null ? null : g.getLongitude(),
                g == null ? null : g.getZipCode(),
                demographic,
                meetingPlaces,
                evacPlans,
                originLocations,
                mealPlan,
                contactGroups,
                g == null ? null : g.getPlanLastConfirmedAt()
        );
    }

    /**
     * §3 of docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md — stamp the
     * household plan as confirmed-as-current right now. Does not touch
     * any plan-component entity; only bumps {@code Group.planLastConfirmedAt}.
     * The FE freshness sub-copy reads that timestamp and decides when
     * to nudge "quick refresh?". Returns the refreshed plan document so
     * the caller can render the new state without a follow-up GET.
     *
     * <p>Auth + household-membership gating is the resource's
     * responsibility — this method assumes the caller has already
     * verified.</p>
     */
    @Transactional
    public HouseholdPlanDto confirmHouseholdPlan(String householdId) {
        Group g = groupRepo.findByGroupId(householdId)
                .orElseThrow(() -> new IllegalArgumentException("Household not found: " + householdId));
        g.setPlanLastConfirmedAt(java.time.Instant.now());
        groupRepo.save(g);
        return buildHouseholdPlanDocument(householdId);
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
                status,
                u.getLastActiveAt(),
                u.getLastAssessmentAt(),
                u.getOnboardingCompletedAt(),
                u.getOnboardingTermsAcceptedAt(),
                u.getOnboardingLocationEnabledAt(),
                u.getOnboardingNotificationsEnabledAt(),
                u.getBio(),
                u.getCoverImageUrl(),
                u.getProfileVisibility(),
                u.getSearchable(),
                parseAssessmentSummary(u),
                // Per-group map-visibility preferences. Defensive copy so a
                // downstream mutation can't ripple back into the JPA-managed
                // entity. Map may be empty (never null on the wire — keeps FE
                // state shape predictable).
                u.getGroupLocationSharing() == null
                        ? java.util.Map.of()
                        : new java.util.HashMap<>(u.getGroupLocationSharing())
        );
    }

    private Map<String, Object> parseAssessmentSummary(UserInfo u) {
        String json = u.getAssessmentSummaryJson();
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, ASSESSMENT_SUMMARY_TYPE);
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (Exception e) {
            log.warn("MeService: bad assessment summary json userId={} cause={}",
                    u.getId(), e.getMessage());
            return null;
        }
    }

    private HouseholdDto toHouseholdDto(Group g, Demographic d, java.util.Map<String, UserInfo> profiles, java.util.Map<String, java.time.Instant> readMap, java.util.Map<String, java.time.Instant> latestPostMap, java.util.Map<String, java.time.Instant> muteMap, java.util.Map<String, io.sitprep.sitprepapi.domain.GroupMutePref> prefMap) {
        DemographicDto demoDto = d == null ? null : new DemographicDto(
                d.getAdults(), d.getKids(), d.getInfants(),
                d.getDogs(), d.getCats(), d.getPets()
        );
        int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
        int adminCount = g.getAdminEmails() == null ? 0 : g.getAdminEmails().size();
        io.sitprep.sitprepapi.domain.GroupMutePref pref =
                g.getGroupId() == null ? null : prefMap.get(g.getGroupId());
        // §4 — weekly check-in ritual lookup. One extra query per /me call
        // for the base household; bounded since at-most-one ritual per
        // (household, kind) in Round 1. Null when the admin hasn't opted
        // in yet; the FE renders "Set a weekly check-in" in that case.
        var ritual = g.getGroupId() == null
                ? java.util.Optional.<io.sitprep.sitprepapi.domain.HouseholdRitual>empty()
                : householdRitualRepo.findFirstByHouseholdIdAndKind(g.getGroupId(), "check-in");
        String weeklyCheckInScheduleSpec = ritual
                .map(io.sitprep.sitprepapi.domain.HouseholdRitual::getScheduleSpec)
                .orElse(null);
        java.time.Instant weeklyCheckInPausedUntil = ritual
                .map(io.sitprep.sitprepapi.domain.HouseholdRitual::getPausedUntil)
                .orElse(null);
        String weeklyCheckInTimezone = ritual
                .map(io.sitprep.sitprepapi.domain.HouseholdRitual::getTimezone)
                .orElse(null);
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
                null,
                g.getAlert(),
                g.getActiveHazardType(),
                previewFor(g, profiles),
                unreadCountFor(g, readMap),
                lastActivityFor(g, latestPostMap),
                g.getGroupId() == null ? null : muteMap.get(g.getGroupId()),
                pref == null ? null : pref.getQuietStart(),
                pref == null ? null : pref.getQuietEnd(),
                pref == null ? null : pref.getQuietTimezone(),
                weeklyCheckInScheduleSpec,
                weeklyCheckInPausedUntil,
                weeklyCheckInTimezone
        );
    }

    private HouseholdSummary toHouseholdSummary(Group g, String userEmail, boolean isBase) {
        int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
        int adminCount = g.getAdminEmails() == null ? 0 : g.getAdminEmails().size();
        return new HouseholdSummary(
                g.getGroupId(),
                g.getGroupName(),
                memberCount,
                adminCount,
                resolveRole(g, userEmail),
                isBase,
                null, // per-household readiness — computed in Phase 3
                g.getAlert(),
                g.getActiveHazardType()
        );
    }

    private GroupSummary toGroupSummary(Group g, String userEmail, java.util.Map<String, UserInfo> profiles, java.util.Map<String, java.time.Instant> readMap, java.util.Map<String, java.time.Instant> latestPostMap, java.util.Map<String, java.time.Instant> muteMap, java.util.Map<String, io.sitprep.sitprepapi.domain.GroupMutePref> prefMap) {
        String role = resolveRole(g, userEmail);
        // Always derive from the member-email list — the denormalized
        // Group.memberCount drifts (set to 1 at creation, not kept in
        // sync on every join/leave), which surfaced as stale "1 member"
        // counts on group cards. memberEmails is EAGER-loaded, so .size()
        // is the cheap, accurate source of truth (matches the household
        // builders above).
        int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
        int pendingCount = g.getPendingMemberEmails() == null ? 0 : g.getPendingMemberEmails().size();
        io.sitprep.sitprepapi.domain.GroupMutePref pref =
                g.getGroupId() == null ? null : prefMap.get(g.getGroupId());
        return new GroupSummary(
                g.getGroupId(),
                g.getGroupName(),
                g.getGroupType(),
                memberCount,
                pendingCount,
                role,
                g.getAlert(),
                g.getActiveHazardType(),
                g.getUpdatedAt(),
                previewFor(g, profiles),
                unreadCountFor(g, readMap),
                lastActivityFor(g, latestPostMap),
                g.getGroupId() == null ? null : muteMap.get(g.getGroupId()),
                pref == null ? null : pref.getQuietStart(),
                pref == null ? null : pref.getQuietEnd(),
                pref == null ? null : pref.getQuietTimezone()
        );
    }

    /**
     * Most-recent activity instant for {@code g}. Returns the latest
     * GroupPost timestamp when one exists; falls back to
     * {@code Group.updatedAt}, then {@code Group.createdAt}, then
     * {@code Instant.now()} as a last resort so the FE never has to
     * defend against null on the wire.
     */
    private java.time.Instant lastActivityFor(Group g, java.util.Map<String, java.time.Instant> latestPostMap) {
        java.time.Instant post = g.getGroupId() == null ? null : latestPostMap.get(g.getGroupId());
        if (post != null) return post;
        if (g.getUpdatedAt() != null) return g.getUpdatedAt();
        if (g.getCreatedAt() != null) return g.getCreatedAt();
        return java.time.Instant.now();
    }

    /**
     * Posts in {@code g} newer than the viewer's last-read pointer.
     * Returns 0 when no pointer exists for this group — the unread badge
     * stays empty until the user explicitly marks the circle read.
     */
    private int unreadCountFor(Group g, java.util.Map<String, java.time.Instant> readMap) {
        if (g.getGroupId() == null) return 0;
        java.time.Instant since = readMap.get(g.getGroupId());
        if (since == null) return 0;
        return groupPostRepo.countByGroupIdAndTimestampAfter(g.getGroupId(), since);
    }

    /**
     * Up to 4 member avatars for a circle card's member stack. Reads the
     * pre-batched profile map (keyed by lowercased email) — O(1) per
     * member, no per-circle DB hit.
     */
    private java.util.List<MemberAvatar> previewFor(Group g, java.util.Map<String, UserInfo> profiles) {
        if (g.getMemberEmails() == null || profiles.isEmpty()) return java.util.List.of();
        java.util.List<MemberAvatar> out = new java.util.ArrayList<>();
        for (String e : g.getMemberEmails()) {
            if (e == null) continue;
            UserInfo u = profiles.get(e.toLowerCase());
            if (u != null) out.add(new MemberAvatar(u.getId(), u.getUserFirstName(), u.getProfileImageURL()));
            if (out.size() >= 4) break;
        }
        return out;
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
                m.getPhoneNumber(), m.getTierKey(), m.getLat(), m.getLng(), m.isDeploy()
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
            boolean contactsDone,
            String email
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

        // Personal-task pillar rollup — Phase 1 of BUSINESS_MODEL.md.
        // Drives the My Readiness card on /home. We count user's
        // personal Post rows (kind="task", groupId=null) grouped by
        // their "pillar:X" tag. FE computes displayed percent using
        // template-catalog denominators (added vs recommendedMin).
        //
        // Defense in depth: any failure here (corrupt rows, missing
        // tag column, etc.) degrades to a null rollup so the rest of
        // the /me payload still ships. The FE falls back to the
        // soft-default percentages when the rollup is null.
        PillarRollup pillars = email.isBlank() ? null : safeGet(
                "pillarRollup", "email=" + email,
                () -> computePillarRollup(email),
                null);

        return new ReadinessDto(percent, steps, pillars);
    }

    /**
     * Single fetch of the user's personal tasks, grouped by pillar
     * tag, counted by status. The query is bounded (a user's personal
     * tasks max out at ~50 rows even for a maximally engaged user) so
     * we do the grouping in Java rather than a SQL GROUP BY — easier
     * to maintain and the row volume doesn't justify the complexity.
     *
     * <p>Tasks without a {@code pillar:X} tag are silently dropped
     * (legacy / hand-created tasks). Unknown pillar tags are also
     * dropped — we only count the four canonical pillars.</p>
     */
    private PillarRollup computePillarRollup(String email) {
        var rows = postRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(email);
        int sAdd = 0, sDone = 0;
        int pAdd = 0, pDone = 0;
        int prAdd = 0, prDone = 0;
        int fAdd = 0, fDone = 0;
        for (var p : rows) {
            if (!"task".equals(p.getKind())) continue;
            if (p.getGroupId() != null) continue; // org tasks don't count
            String pillar = extractPillar(p.getTags());
            if (pillar == null) continue;
            boolean isDone = p.getStatus() == io.sitprep.sitprepapi.domain.Post.PostStatus.DONE;
            switch (pillar) {
                case "supplies": sAdd++; if (isDone) sDone++; break;
                case "plan":     pAdd++; if (isDone) pDone++; break;
                case "practice": prAdd++; if (isDone) prDone++; break;
                case "family":   fAdd++; if (isDone) fDone++; break;
                default: /* unknown pillar — ignore */ break;
            }
        }
        return new PillarRollup(
                new PillarCounts(sAdd, sDone),
                new PillarCounts(pAdd, pDone),
                new PillarCounts(prAdd, prDone),
                new PillarCounts(fAdd, fDone)
        );
    }

    /**
     * Find the first {@code pillar:X} tag on a task and return the
     * value. Tasks can have multiple tags in theory; pillar is the
     * only one MeService cares about, and we treat the first match
     * as authoritative.
     */
    private static String extractPillar(java.util.Set<String> tags) {
        if (tags == null) return null;
        for (String tag : tags) {
            if (tag != null && tag.startsWith("pillar:")) {
                return tag.substring("pillar:".length()).toLowerCase();
            }
        }
        return null;
    }
}
