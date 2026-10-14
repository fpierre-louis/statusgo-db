// src/main/java/io/sitprep/sitprepapi/service/GroupReadinessService.java
package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupReadinessSummaryDto;
import io.sitprep.sitprepapi.dto.HouseholdReadinessDto;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The org readiness rollup behind {@code GET /api/groups/{id}/readiness-summary}.
 *
 * <p><b>Audit ST-06, decision D15 (2026-10-13):</b> readiness is the four
 * server essentials, evaluated per member's BASE household through
 * {@link EssentialsReadinessService} (the rule Home and Ready for More use).
 * It used to count plans by the member's own email (four legacy owner-email
 * existence queries), so a spouse in a fully prepared household read "not
 * ready" because the other spouse authored the plans. The legacy count field
 * names stay (the admin dashboards read them) with this meaning; the DTO adds
 * {@code essentials[]} (key, label, done, total), {@code readinessPercent} and
 * {@code membersWithoutHousehold}.</p>
 */
@Service
@RequiredArgsConstructor
public class GroupReadinessService {

    /** Admin-facing labels for the four essentials, in Home's order. */
    static final Map<String, String> ESSENTIAL_LABELS = Map.of(
            "demographics", "Household details",
            "mealPlan", "Meal plan",
            "evacuation", "Evacuation plan",
            "contacts", "Emergency contacts");

    private static final EssentialsResult NOTHING_DONE = new EssentialsResult(false, false, false, false);

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final HouseholdReadinessService readinessEngine;
    private final EssentialsReadinessService essentials;

    @Transactional(readOnly = true)
    public GroupReadinessSummaryDto buildReadinessSummary(String groupId) {
        // ✅ Look up the *current* group with its members
        Group group = groupRepo.findByGroupIdWithMembers(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));

        List<String> memberEmails = normalizeEmailList(group.getMemberEmails());

        GroupReadinessSummaryDto dto = new GroupReadinessSummaryDto();
        dto.setGroupId(group.getGroupId());
        dto.setGroupName(group.getGroupName());
        dto.setGroupType(group.getGroupType());
        dto.setGeneratedAt(Instant.now());

        if (memberEmails.isEmpty()) {
            dto.setTotalMembers(0);
            dto.setMembersWithDemographic(0);
            dto.setMembersWithMealPlan(0);
            dto.setMembersWithEvacuationPlan(0);
            dto.setMembersWithEmergencyContacts(0);
            dto.setFullyReadyMembers(0);
            dto.setMembersWithoutHousehold(0);
            dto.setEssentials(essentialCounts(new int[4], 0));
            dto.setReadinessPercent(null); // nobody to measure: no number, not 0%
            dto.setMembersCurrentlySafe(0);
            dto.setMembersNeedingAssistance(0);
            dto.setStatusCounts(Collections.emptyMap());

            // Still show any Household subgroups if they exist
            populateHouseholdSubgroups(dto, group);
            return dto;
        }

        dto.setTotalMembers(memberEmails.size());

        // --- Bulk fetch UserInfo for status counts ---
        Map<String, UserInfo> userInfoByEmail = userInfoRepo.findByUserEmailIn(memberEmails)
                .stream()
                .collect(Collectors.toMap(
                        u -> safeEmail(u.getUserEmail()),
                        Function.identity(),
                        (a, b) -> a
                ));

        // --- Essentials per member's base household (ST-06). One essentials
        // evaluation per household; a member is evaluated on their own only
        // when the household's rows don't already make it complete (Home's
        // owner-email fallback can differ per member).
        Map<String, Group> households = loadHouseholds(userInfoByEmail.values());
        Map<String, EssentialsResult> byHousehold = new HashMap<>();

        int[] done = new int[4];
        int fullyReadyMembers = 0;
        int membersWithoutHousehold = 0;

        int membersSafe = 0;
        int membersNeedsAssistance = 0;

        Map<String, Integer> statusCounts = new HashMap<>();

        for (String normEmail : memberEmails) {
            UserInfo info = userInfoByEmail.get(normEmail);
            String baseId = info == null ? null : info.getBaseHouseholdId();
            Group household = baseId == null ? null : households.get(baseId);

            EssentialsResult r;
            if (household == null) {
                membersWithoutHousehold++;
                r = NOTHING_DONE;
            } else {
                EssentialsResult shared = byHousehold.computeIfAbsent(baseId,
                        k -> essentials.evaluate(household, null, false));
                r = shared.complete() ? shared : essentials.evaluate(household, normEmail, true);
            }
            for (int i = 0; i < 4; i++) {
                if (r.isDone(EssentialsReadinessService.ESSENTIAL_KEYS.get(i))) done[i]++;
            }
            if (r.complete()) fullyReadyMembers++;

            String status = info != null ? safeString(info.getUserStatus()) : "UNKNOWN";

            String normalizedStatus = normalizeStatus(status);
            statusCounts.merge(normalizedStatus, 1, Integer::sum);

            if (isSafeStatus(normalizedStatus)) {
                membersSafe++;
            } else {
                membersNeedsAssistance++;
            }
        }

        dto.setMembersWithDemographic(done[0]);
        dto.setMembersWithMealPlan(done[1]);
        dto.setMembersWithEvacuationPlan(done[2]);
        dto.setMembersWithEmergencyContacts(done[3]);
        dto.setFullyReadyMembers(fullyReadyMembers);
        dto.setMembersWithoutHousehold(membersWithoutHousehold);
        dto.setEssentials(essentialCounts(done, memberEmails.size()));
        dto.setReadinessPercent(Math.round(100f * fullyReadyMembers / memberEmails.size()));

        dto.setMembersCurrentlySafe(membersSafe);
        dto.setMembersNeedingAssistance(membersNeedsAssistance);
        dto.setStatusCounts(statusCounts);

        // 🔹 Only direct Household subgroups for THIS group (2 levels max)
        populateHouseholdSubgroups(dto, group);

        return dto;
    }

    // ----------------- Household subgroup helper -----------------

    /**
     * For the given parent group, look at *its* subGroupIDs only.
     * For each sub-group, if groupType == "Household", compute a simple readiness snapshot.
     * No parent traversal, no deeper levels.
     */
    private void populateHouseholdSubgroups(GroupReadinessSummaryDto dto, Group parentGroup) {
        // 1) Take ONLY this group's direct subGroupIDs
        List<String> subGroupIds = Optional.ofNullable(parentGroup.getSubGroupIDs())
                .orElse(Collections.emptyList())
                .stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        if (subGroupIds.isEmpty()) {
            dto.setTotalHouseholdGroups(0);
            dto.setTotalHouseholdMembers(0);
            dto.setHouseholds(Collections.emptyList());
            return;
        }

        // 2) Fetch those sub-groups and keep only Household-type groups
        List<Group> householdGroups = groupRepo.findAllById(subGroupIds).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .collect(Collectors.toList());

        if (householdGroups.isEmpty()) {
            dto.setTotalHouseholdGroups(0);
            dto.setTotalHouseholdMembers(0);
            dto.setHouseholds(Collections.emptyList());
            return;
        }

        List<HouseholdReadinessDto> householdDtos = new ArrayList<>();
        int totalHouseholdMembers = 0;

        // ONE batched UserInfo fetch across every household's members so the
        // engine's dominant-status derivation adds zero per-household status
        // queries (T-12: batch the engine on fan-out surfaces).
        List<String> allHouseholdEmails = householdGroups.stream()
                .flatMap(g -> normalizeEmailList(g.getMemberEmails()).stream())
                .distinct()
                .collect(Collectors.toList());
        Map<String, UserInfo> statusByEmail = allHouseholdEmails.isEmpty()
                ? Collections.emptyMap()
                : userInfoRepo.findByUserEmailIn(allHouseholdEmails).stream()
                        .collect(Collectors.toMap(
                                u -> safeEmail(u.getUserEmail()),
                                Function.identity(),
                                (a, b) -> a));

        for (Group hg : householdGroups) {
            List<String> emails = normalizeEmailList(hg.getMemberEmails());
            int memberCount = emails.size();
            totalHouseholdMembers += memberCount;

            // Essentials for THIS household (ST-06): one evaluation, applied
            // to every member of it, the same rule Home shows them.
            EssentialsResult hr = essentials.evaluate(hg, hg.getOwnerEmail());
            int withDemo = hr.demographics() ? memberCount : 0;
            int withMeal = hr.mealPlan() ? memberCount : 0;
            int withEvac = hr.evacuation() ? memberCount : 0;
            int withContacts = hr.contacts() ? memberCount : 0;
            int fullyReady = hr.complete() ? memberCount : 0;

            HouseholdReadinessDto hDto = new HouseholdReadinessDto();
            hDto.setGroupId(hg.getGroupId());
            hDto.setGroupName(hg.getGroupName());
            hDto.setOwnerName(hg.getOwnerName());
            hDto.setOwnerEmail(hg.getOwnerEmail());
            hDto.setMemberCount(memberCount);

            hDto.setMembersWithDemographic(withDemo);
            hDto.setMembersWithMealPlan(withMeal);
            hDto.setMembersWithEvacuationPlan(withEvac);
            hDto.setMembersWithEmergencyContacts(withContacts);
            hDto.setFullyReadyMembers(fullyReady);

            // Global Readiness Engine (Phase 1.3): dominant status from the
            // SAME accountability rollup the member-view ships, + the
            // comms-pillar gap evaluation for the household head.
            hDto.setDominantStatus(readinessEngine.dominantStatusFor(hg, statusByEmail));
            hDto.setComms(readinessEngine.commsFor(hg.getOwnerEmail()));
            hDto.setPulse(readinessEngine.pulseFor(null, hg));
            hDto.setPillarScores(readinessEngine.pillarScoresFor(hDto.getPulse(), hDto.getComms()));
            hDto.setReadinessPercent(readinessEngine.overallFromPillarScores(hDto.getPillarScores()));

            householdDtos.add(hDto);
        }

        dto.setTotalHouseholdGroups(householdGroups.size());
        dto.setTotalHouseholdMembers(totalHouseholdMembers);
        dto.setHouseholds(householdDtos);
    }

    // ----------------- helpers -----------------

    /** The members' base households, one batched read. */
    private Map<String, Group> loadHouseholds(Collection<UserInfo> members) {
        List<String> ids = members.stream()
                .map(UserInfo::getBaseHouseholdId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) return Map.of();
        return groupRepo.findAllById(ids).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .collect(Collectors.toMap(Group::getGroupId, Function.identity(), (a, b) -> a));
    }

    private static List<GroupReadinessSummaryDto.EssentialCount> essentialCounts(int[] done, int total) {
        List<GroupReadinessSummaryDto.EssentialCount> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            String key = EssentialsReadinessService.ESSENTIAL_KEYS.get(i);
            out.add(new GroupReadinessSummaryDto.EssentialCount(key, ESSENTIAL_LABELS.get(key), done[i], total));
        }
        return out;
    }

    private List<String> normalizeEmailList(List<String> raw) {
        return Optional.ofNullable(raw)
                .orElse(Collections.emptyList())
                .stream()
                .filter(Objects::nonNull)
                .map(this::safeEmail)
                .filter(e -> !e.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private String safeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String safeString(String s) {
        return s == null ? "" : s.trim();
    }

    private String normalizeStatus(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "UNKNOWN";
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        switch (upper) {
            case "SAFE":
            case "OK":
            case "GREEN":
                return "SAFE";
            case "HELP":
            case "NEED_HELP":
            case "ASSIST":
            case "RED":
                return "HELP";
            case "CHECK_IN":
            case "CHECKIN":
            case "YELLOW":
                return "CHECK_IN";
            default:
                return upper;
        }
    }

    private boolean isSafeStatus(String normalizedStatus) {
        return "SAFE".equalsIgnoreCase(normalizedStatus);
    }
}
