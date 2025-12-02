// src/main/java/io/sitprep/sitprepapi/service/GroupReadinessService.java
package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupReadinessSummaryDto;
import io.sitprep.sitprepapi.dto.HouseholdReadinessDto;
import io.sitprep.sitprepapi.repo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupReadinessService {

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final DemographicRepo demographicRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;

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

        int membersWithDemographic = 0;
        int membersWithMealPlan = 0;
        int membersWithEvacuationPlan = 0;
        int membersWithEmergencyContacts = 0;
        int fullyReadyMembers = 0;

        int membersSafe = 0;
        int membersNeedsAssistance = 0;

        Map<String, Integer> statusCounts = new HashMap<>();

        for (String normEmail : memberEmails) {
            // ❗ Use existsBy... so duplicates never cause NonUniqueResultException
            boolean hasDemo = demographicRepo.existsByOwnerEmailIgnoreCase(normEmail);
            boolean hasMeal = mealPlanDataRepo.findFirstByOwnerEmailIgnoreCase(normEmail).isPresent();
            boolean hasEvac = !evacuationPlanRepo.findByOwnerEmail(normEmail).isEmpty();
            boolean hasContacts = !emergencyContactGroupRepo.findByOwnerEmailIgnoreCase(normEmail).isEmpty();

            if (hasDemo) membersWithDemographic++;
            if (hasMeal) membersWithMealPlan++;
            if (hasEvac) membersWithEvacuationPlan++;
            if (hasContacts) membersWithEmergencyContacts++;

            if (hasDemo && hasMeal && hasEvac && hasContacts) {
                fullyReadyMembers++;
            }

            UserInfo info = userInfoByEmail.get(normEmail);
            String status = info != null ? safeString(info.getUserStatus()) : "UNKNOWN";

            String normalizedStatus = normalizeStatus(status);
            statusCounts.merge(normalizedStatus, 1, Integer::sum);

            if (isSafeStatus(normalizedStatus)) {
                membersSafe++;
            } else {
                membersNeedsAssistance++;
            }
        }

        dto.setMembersWithDemographic(membersWithDemographic);
        dto.setMembersWithMealPlan(membersWithMealPlan);
        dto.setMembersWithEvacuationPlan(membersWithEvacuationPlan);
        dto.setMembersWithEmergencyContacts(membersWithEmergencyContacts);
        dto.setFullyReadyMembers(fullyReadyMembers);

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

        for (Group hg : householdGroups) {
            List<String> emails = normalizeEmailList(hg.getMemberEmails());
            int memberCount = emails.size();
            totalHouseholdMembers += memberCount;

            int withDemo = 0;
            int withMeal = 0;
            int withEvac = 0;
            int withContacts = 0;
            int fullyReady = 0;

            for (String email : emails) {
                // Same simple existence checks here
                boolean hasDemo = demographicRepo.existsByOwnerEmailIgnoreCase(email);
                boolean hasMeal = mealPlanDataRepo.findFirstByOwnerEmailIgnoreCase(email).isPresent();
                boolean hasEvac = !evacuationPlanRepo.findByOwnerEmail(email).isEmpty();
                boolean hasContacts = !emergencyContactGroupRepo.findByOwnerEmailIgnoreCase(email).isEmpty();

                if (hasDemo) withDemo++;
                if (hasMeal) withMeal++;
                if (hasEvac) withEvac++;
                if (hasContacts) withContacts++;

                if (hasDemo && hasMeal && hasEvac && hasContacts) {
                    fullyReady++;
                }
            }

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

            // We keep dominantStatus available, but your widget doesn't need it right now
            hDto.setDominantStatus(null);

            householdDtos.add(hDto);
        }

        dto.setTotalHouseholdGroups(householdGroups.size());
        dto.setTotalHouseholdMembers(totalHouseholdMembers);
        dto.setHouseholds(householdDtos);
    }

    // ----------------- helpers -----------------

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