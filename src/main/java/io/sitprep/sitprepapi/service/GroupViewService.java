package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.*;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.GroupPostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.Geo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
    private final PlatformAccessService platformAccessService;
    private final AgencyStaffService agencyStaffService;

    public GroupViewService(GroupRepo groupRepo,
                            UserInfoRepo userInfoRepo,
                            GroupPostRepo postRepo,
                            HouseholdManualMemberService manualMemberService,
                            HouseholdAccompanimentService accompanimentService,
                            PlatformAccessService platformAccessService,
                            AgencyStaffService agencyStaffService) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.postRepo = postRepo;
        this.manualMemberService = manualMemberService;
        this.accompanimentService = accompanimentService;
        this.platformAccessService = platformAccessService;
        this.agencyStaffService = agencyStaffService;
    }

    @Transactional(readOnly = true)
    public Optional<GroupMemberViewDto> buildMemberView(String groupId, String viewerEmail) {
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        return groupRepo.findByGroupId(groupId).map(g -> {
            String viewer = normalize(viewerEmail);
            requireCanReadGroup(g, viewer);
            return assemble(g, viewer);
        });
    }

    /**
     * READ gate for the consolidated group view.
     *
     * <p><b>Why this exists.</b> This endpoint was previously gated on
     * authentication ALONE — `AuthUtils.requireAuthenticatedEmail()` and nothing
     * else — so any signed-in user could read any group's full roster by id:
     * every member's email, name, self-status, last-active time, and (subject to
     * their per-group sharing pref) last known coordinates, plus the group's
     * address and owner/admin email lists. For households that is a family's
     * roster and whereabouts. Closing it.</p>
     *
     * <p><b>Who may read.</b> Deliberately the union of every population that a
     * shipped surface depends on — narrower would break working features:</p>
     * <ul>
     *   <li><b>owner / admin / member</b> — the group's own people. PENDING is
     *       excluded: a join request is not yet a membership, and the
     *       join-confirmation flow reads the sanitized {@code GroupPreviewDto}
     *       instead, which ships no rosters.</li>
     *   <li><b>platform admins</b> — the console's "Dashboard" link into any
     *       agency depends on this (Lane B3, {@code viewerPlatformRole}).</li>
     *   <li><b>agency staff</b> — a staff-only viewer is NOT a group member by
     *       design, and the agency console they may now open (Step 5) is built
     *       from this payload.</li>
     * </ul>
     *
     * <p><b>Known scaffolded caller.</b> {@code /h/share/:groupId}
     * (HouseholdGuestPage) renders a read-only household mirror for a
     * non-member. Its own header comment claims it is "gated by the existing
     * membership check on the server" — that check did not exist, so the route
     * worked BECAUSE of this hole. It is currently unreachable (nothing in the
     * UI links to it; real share links go to {@code /share/group/{id}}, which
     * uses the sanitized preview DTO), so this gate breaks no reachable
     * feature. If that guest view is ever revived, build it the way that file's
     * TODO already specifies — a separate redacted {@code public-view} endpoint
     * with a share token — not by widening this one.</p>
     */
    private void requireCanReadGroup(Group g, String viewer) {
        if (viewer == null || viewer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated request required");
        }
        if (GroupRole.fromGroup(g, viewer) != GroupRole.NONE) return;
        // Cheapest checks first; both of these are single indexed lookups and
        // only run for a viewer with no standing on the group itself.
        if (platformAccessService.resolve(viewer).role() != PlatformRole.NONE) return;
        if (g.getGroupId() != null && agencyStaffService.isStaff(viewer, g.getGroupId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "You don't have access to this group");
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

        // Pinned posts are fetched separately so the FE can render
        // them in a dedicated section regardless of how far back they
        // were pinned. Bounded cardinality (admins pin 0-3 per group)
        // so this stays a single small query.
        List<GroupPostSummaryDto> pinnedPosts = postRepo.findPinnedByGroupId(g.getGroupId()).stream()
                .map(p -> toPostSummary(p, byEmail))
                .toList();

        StatusRollup rollup = computeRollup(
                memberEmails, byEmail, manualMembers, accompaniments,
                alertActive, g.getUpdatedAt());

        return new GroupMemberViewDto(
                toGroupInfo(g),
                resolveViewerRole(g, viewerEmail),
                resolveViewerPlatformRole(viewerEmail),
                members,
                manualMembers,
                accompaniments,
                recentPosts,
                pinnedPosts,
                rollup,
                new MetaDto(Instant.now(), DTO_VERSION)
        );
    }

    /**
     * Accountability rollup — the single source of truth for "N of M
     * accounted for" (Thin-Client Refactor Phase 1). The math now lives in
     * {@link StatusRollups#compute} so the Global Readiness Engine derives
     * {@code dominantStatus} from the SAME aggregation (zero duplication);
     * this method is a pure delegate kept for call-site stability.
     *
     * <p>Anchor note: this uses {@code group.updatedAt} to match the FE hook
     * exactly. {@code GroupService.buildCheckInRollup} (the org check-in path)
     * uses {@code alertActivatedAt ?? updatedAt}; reconciling the two onto the
     * more-correct {@code alertActivatedAt} anchor is a documented follow-up
     * (they agree whenever the alert flip was the last edit, the common case).</p>
     */
    private StatusRollup computeRollup(List<String> memberEmails,
                                       Map<String, UserInfo> byEmail,
                                       List<HouseholdManualMemberDto> manualMembers,
                                       List<HouseholdAccompanimentDto> accompaniments,
                                       boolean alertActive,
                                       Instant updatedAt) {
        return StatusRollups.compute(memberEmails, byEmail, manualMembers,
                accompaniments, alertActive, updatedAt);
    }

    private GroupInfo toGroupInfo(Group g) {
        // Accurate count from the member list (the denormalized
        // Group.memberCount drifts and isn't kept in sync on join/leave).
        int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
        return new GroupInfo(
                g.getGroupId(),
                g.getGroupName(),
                g.getGroupType(),
                g.getDescription(),
                g.getAddress(),
                Geo.str(g.getLatitude()),
                Geo.str(g.getLongitude()),
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
                g.getSubGroupIDs() == null ? List.of() : List.copyOf(g.getSubGroupIDs()),
                g.getPlanTier()
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
                DtoImages.avatar(u.getProfileImageUrl()),
                status,
                u.getLastActiveAt(),
                lat, lng, locAt
        );
    }

    /**
     * Whether {@code u}'s live location is visible to this group right now.
     *
     * <p><b>"never" is an absolute opt-out</b> (locked 2026-07-02): a member who
     * selects {@code never} stays hidden <b>even during an Active alert</b>.
     * This deliberately protects users in extreme edge cases — e.g.
     * domestic-violence survivors — who cannot risk their location being
     * broadcast to a group under any circumstance. <b>Do NOT add an alert-time
     * override that reveals {@code never}.</b> The FE copy on
     * {@code MapVisibilityPage} is being updated to match (see the Front-End
     * phase note in {@code docs/MAP_REBUILD_PLAN.md}).</p>
     *
     * <p>Defaults for an unset entry: household → {@code check-in-only},
     * everything else → {@code never}. {@code always} always reveals;
     * {@code check-in-only} reveals only while the group's alert is Active;
     * {@code never} never reveals.</p>
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
            case SHARE_NEVER -> false;              // absolute — never overridden, even in an alert
            case SHARE_CHECK_IN_ONLY -> alertActive;
            default -> false;                       // unknown mode: fail closed (hidden)
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
            dto.setAuthorProfileImageUrl(DtoImages.avatar(u.getProfileImageUrl()));
        }
        dto.setContent(p.getContent());
        dto.setTimestamp(p.getTimestamp());
        dto.setPinnedAt(p.getPinnedAt());
        dto.setPinnedBy(p.getPinnedBy());
        return dto;
    }

    /**
     * The viewer's platform role, or null when they hold none.
     *
     * <p>Deliberately a SEPARATE resolver from {@link #resolveViewerRole},
     * which is left exactly as it was. {@code viewerRole} means "this person's
     * standing on this group's roster" and frontend WRITE gates branch on it
     * ({@code GroupVerificationPage} → submit a verification application;
     * {@code OrgAdminDashboard} → hand the role to the roster manager, which
     * decides whether to render promote / demote / remove). Folding platform
     * access into that value would have made those surfaces offer group-tier
     * mutations that {@code GroupResource.requireAdminOf} then refuses, because
     * no platform bypass exists there. See the field doc on
     * {@code GroupMemberViewDto.viewerPlatformRole}.</p>
     *
     * <p>Failure is non-fatal: this is a visibility nicety, and a hiccup
     * reading {@code platform_admin} must not take down every group page in the
     * app. {@code PlatformAccessService.resolve} converts a
     * {@code DataAccessException} into a 503, so it is caught here and
     * downgraded to "no platform role".</p>
     */
    private String resolveViewerPlatformRole(String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return null;
        try {
            PlatformRole role = platformAccessService.resolve(viewerEmail).role();
            return role == null || role == PlatformRole.NONE ? null : role.name();
        } catch (RuntimeException ex) {
            return null;
        }
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
