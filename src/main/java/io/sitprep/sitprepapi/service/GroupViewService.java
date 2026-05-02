package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.*;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.GroupPostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GroupViewService {

    private static final int DTO_VERSION = 1;
    private static final int RECENT_POST_LIMIT = 5;

    /** Sharing-mode constants — match the FE helper. */
    private static final String SHARE_ALWAYS = "always";
    private static final String SHARE_CHECK_IN_ONLY = "check-in-only";
    private static final String SHARE_NEVER = "never";

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupPostRepo postRepo;
    private final HouseholdManualMemberService manualMemberService;
    private final HouseholdAccompanimentService accompanimentService;

    public GroupViewService(GroupRepo groupRepo,
                            UserInfoRepo userInfoRepo,
                            GroupPostRepo postRepo,
                            HouseholdManualMemberService manualMemberService,
                            HouseholdAccompanimentService accompanimentService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.postRepo = postRepo;
        this.manualMemberService = manualMemberService;
        this.accompanimentService = accompanimentService;
    }

    @Transactional(readOnly = true)
    public Optional<GroupMemberViewDto> buildMemberView(String groupId, String viewerEmail) {
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        return groupRepo.findByGroupId(groupId).map(g -> assemble(g, normalize(viewerEmail)));
    }

    private GroupMemberViewDto assemble(Group g, String viewerEmail) {
        List<String> memberEmails = g.getMemberEmails() == null ? List.of() : g.getMemberEmails();
        Map<String, UserInfo> byEmail = memberEmails.isEmpty()
                ? Map.of()
                : userInfoRepo.findByUserEmailIn(memberEmails).stream()
                        .collect(Collectors.toMap(u -> normalize(u.getUserEmail()), u -> u, (a, b) -> a));

        boolean alertActive = "Active".equalsIgnoreCase(g.getAlert());
        List<MemberSummary> members = memberEmails.stream()
                .map(email -> toMemberSummary(
                        email, byEmail.get(normalize(email)),
                        g.getGroupId(), g.getGroupType(), alertActive))
                .toList();

        boolean isHousehold = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType());
        List<HouseholdManualMemberDto> manualMembers = isHousehold
                ? manualMemberService.list(g.getGroupId())
                : List.of();
        List<HouseholdAccompanimentDto> accompaniments = isHousehold
                ? accompanimentService.list(g.getGroupId())
                : List.of();

        List<GroupPostSummaryDto> recentPosts = postRepo.findPostsByGroupId(g.getGroupId()).stream()
                .limit(RECENT_POST_LIMIT)
                .map(p -> toPostSummary(p, byEmail))
                .toList();

        return new GroupMemberViewDto(
                toGroupInfo(g),
                resolveViewerRole(g, viewerEmail),
                members,
                manualMembers,
                accompaniments,
                recentPosts,
                new MetaDto(Instant.now(), DTO_VERSION)
        );
    }

    private GroupInfo toGroupInfo(Group g) {
        int memberCount = g.getMemberCount() != null ? g.getMemberCount()
                : (g.getMemberEmails() == null ? 0 : g.getMemberEmails().size());
        return new GroupInfo(
                g.getGroupId(),
                g.getGroupName(),
                g.getGroupType(),
                g.getDescription(),
                g.getAddress(),
                g.getLatitude(),
                g.getLongitude(),
                g.getZipCode(),
                memberCount,
                g.getAlert(),
                g.getCreatedAt(),
                g.getUpdatedAt(),
                g.getPrivacy(),
                g.getGroupCode(),
                g.getOwnerName(),
                g.getOwnerEmail(),
                g.getAdminEmails() == null ? List.of() : List.copyOf(g.getAdminEmails()),
                g.getSubGroupIDs() == null ? List.of() : List.copyOf(g.getSubGroupIDs())
        );
    }

    private MemberSummary toMemberSummary(String email, UserInfo u,
                                          String groupId, String groupType,
                                          boolean alertActive) {
        if (u == null) {
            return new MemberSummary(normalize(email), null, null, null, null,
                    null, null, null, null);
        }
        SelfStatus status = new SelfStatus(
                u.getUserStatus(), u.getStatusColor(), u.getUserStatusLastUpdated()
        );

        // Gate live location on the member's per-group sharing pref +
        // current alert state. When the gate denies, lat/lng/at are null;
        // FE renders these members as "unknown" presence.
        Double lat = u.getLastKnownLat();
        Double lng = u.getLastKnownLng();
        Instant locAt = u.getLastKnownLocationAt();
        if (!shouldShareLocation(u, groupId, groupType, alertActive)) {
            lat = null;
            lng = null;
            locAt = null;
        }

        return new MemberSummary(
                normalize(u.getUserEmail()),
                u.getUserFirstName(),
                u.getUserLastName(),
                u.getProfileImageURL(),
                status,
                u.getLastActiveAt(),
                lat, lng, locAt
        );
    }

    /**
     * Mirrors the FE's {@code shouldShareLocation} helper. Defaults: a
     * household defaults to {@code check-in-only}, anything else to
     * {@code never}. {@code check-in-only} reveals location while the
     * group's alert is Active; {@code never} hides it; {@code always}
     * always reveals.
     */
    private static boolean shouldShareLocation(UserInfo u, String groupId,
                                               String groupType, boolean alertActive) {
        Map<String, String> map = u.getGroupLocationSharing();
        String mode = map == null ? null : map.get(groupId);
        if (mode == null || mode.isBlank()) {
            mode = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(groupType)
                    ? SHARE_CHECK_IN_ONLY : SHARE_NEVER;
        }
        return switch (mode) {
            case SHARE_ALWAYS -> true;
            case SHARE_NEVER -> false;
            case SHARE_CHECK_IN_ONLY -> alertActive;
            default -> false;
        };
    }

    private GroupPostSummaryDto toPostSummary(GroupPost p, Map<String, UserInfo> byEmail) {
        UserInfo u = p.getAuthor() == null ? null : byEmail.get(normalize(p.getAuthor()));
        GroupPostSummaryDto dto = new GroupPostSummaryDto();
        dto.setId(p.getId());
        dto.setGroupId(p.getGroupId());
        dto.setGroupName(p.getGroupName());
        dto.setAuthor(p.getAuthor());
        if (u != null) {
            dto.setAuthorFirstName(u.getUserFirstName());
            dto.setAuthorLastName(u.getUserLastName());
            dto.setAuthorProfileImageURL(u.getProfileImageURL());
        }
        dto.setContent(p.getContent());
        dto.setTimestamp(p.getTimestamp());
        return dto;
    }

    private String resolveViewerRole(Group g, String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return "none";
        if (viewerEmail.equalsIgnoreCase(g.getOwnerEmail())) return "owner";
        if (g.getAdminEmails() != null && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(viewerEmail))) return "admin";
        if (g.getMemberEmails() != null && g.getMemberEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(viewerEmail))) return "member";
        return "none";
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
