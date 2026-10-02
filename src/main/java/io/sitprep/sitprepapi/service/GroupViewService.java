package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.LocationSharing;
import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.*;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.GroupPostSummaryDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
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

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final GroupPostRepo postRepo;
    private final HouseholdManualMemberService manualMemberService;
    private final HouseholdAccompanimentService accompanimentService;
    private final PlatformAccessService platformAccessService;
    private final AgencyStaffService agencyStaffService;
    private final CheckInRequestService checkInRequestService;
    private final NotificationLogRepo notificationLogRepo;
    private final UserSavedLocationRepo savedLocationRepo;
    private final MemberAlertAreaService alertAreas;

    public GroupViewService(GroupRepo groupRepo,
                            UserInfoRepo userInfoRepo,
                            GroupPostRepo postRepo,
                            HouseholdManualMemberService manualMemberService,
                            HouseholdAccompanimentService accompanimentService,
                            PlatformAccessService platformAccessService,
                            AgencyStaffService agencyStaffService,
                            CheckInRequestService checkInRequestService,
                            NotificationLogRepo notificationLogRepo,
                            UserSavedLocationRepo savedLocationRepo,
                            MemberAlertAreaService alertAreas) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.postRepo = postRepo;
        this.manualMemberService = manualMemberService;
        this.accompanimentService = accompanimentService;
        this.platformAccessService = platformAccessService;
        this.agencyStaffService = agencyStaffService;
        this.checkInRequestService = checkInRequestService;
        this.notificationLogRepo = notificationLogRepo;
        this.savedLocationRepo = savedLocationRepo;
        this.alertAreas = alertAreas;
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
        // RC-2 · the two facts the roster needs beside a status: was this
        // person asked, and did a message go out. Both are read once for the
        // whole group rather than per member.
        Map<String, Instant> askedAt = checkInRequestService.askedAtByEmail(g);
        // The READ window (open check-in's start, else the last 24 h) — the
        // write-side window is "now" outside a check-in, which matched nothing.
        Instant windowStart = CheckInRequestService.readWindowStartFor(g, Instant.now());
        Map<String, GroupMemberViewDto.DispatchOutcome> dispatch =
                dispatchByEmail(memberEmails, windowStart);
        // One query for every "At <place>" on the roster — only for members
        // whose location this group may see at all.
        Map<Long, UserSavedLocation> currentPlaces = currentPlacesFor(
                byEmail.values(), g.getGroupId(), g.getGroupType(), alertActive);
        boolean isHousehold = HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType());
        // Phones travel only between members of the same household. The read
        // gate above also admits platform admins and agency staff; neither is a
        // member, so neither gets a phone number from this payload.
        boolean includePhones = isHousehold && GroupRole.fromGroup(g, viewerEmail) != GroupRole.NONE;
        // Built once per ingest snapshot (not per member, not per read), and
        // only when at least one member's location is visible here at all.
        List<MemberAlertAreaService.AreaAlert> activeAreas =
                anyLocated(byEmail.values(), g.getGroupId(), g.getGroupType(), alertActive)
                        && alertAreas != null
                        ? alertAreas.activeAreas()
                        : null;
        List<MemberSummary> members = memberEmails.stream()
                .map(email -> toMemberSummary(
                        email, byEmail.get(normalize(email)),
                        g.getGroupId(), g.getGroupType(), alertActive,
                        askedAt.get(normalize(email)),
                        dispatch.get(normalize(email)),
                        currentPlaces, includePhones, activeAreas))
                .toList();

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

        // Anchored on when the check-in STARTED — the same line the server's
        // freshness rule and the map use. It was `updatedAt`, so any edit to
        // the household during a check-in moved which answers counted
        // (open-items plan 1.3, 2026-10-02). updatedAt stays only as the
        // fallback for check-ins opened before alertActivatedAt existed.
        StatusRollup rollup = computeRollup(
                memberEmails, byEmail, manualMembers, accompaniments,
                alertActive, StatusRollups.anchorFor(g));

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
                new MetaDto(Instant.now(), DTO_VERSION),
                viewerCapabilities(g, viewerEmail)
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
                g.getPlanTier(),
                checkInEndsAt(g),
                "active".equalsIgnoreCase(g.getAlert()) ? g.getAlertActivatedAt() : null
        );
    }

    /** The running check-in's automatic end: V90's column, else start + window. */
    private Instant checkInEndsAt(Group g) {
        if (!"active".equalsIgnoreCase(g.getAlert()) || g.getAlertActivatedAt() == null) return null;
        return g.getAlertExpiresAt() != null
                ? g.getAlertExpiresAt()
                : g.getAlertActivatedAt().plus(java.time.Duration.ofHours(checkInHours));
    }

    /**
     * The first name of whoever set this person's status, or null when they
     * set it themselves.
     *
     * <p>One lookup per member with a proxy-set status, which in practice is a
     * handful during a check-in and none the rest of the time — the column is
     * null on every self-report, so the branch below skips the query entirely
     * for the common case.</p>
     */
    private String statusSetByName(UserInfo u) {
        String setBy = u == null ? null : u.getStatusSetByEmail();
        if (setBy == null || setBy.isBlank()) return null;
        return userInfoRepo.findByUserEmailIgnoreCase(setBy)
                .map(UserInfo::getUserFirstName)
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .orElse(null);
    }

    /**
     * What SitPrep can say about the check-in notification for each member,
     * within the current window.
     *
     * <p>Absent from the returned map means {@link GroupMemberViewDto.DispatchOutcome#UNKNOWN}
     * — no record, which is NOT the same as a failure. A muted category is
     * dropped by {@code PushPolicyService} before any row is written, so the
     * honest answer there is "we cannot confirm a message went out".</p>
     */
    private Map<String, GroupMemberViewDto.DispatchOutcome> dispatchByEmail(
            List<String> memberEmails, Instant windowStart) {
        Map<String, GroupMemberViewDto.DispatchOutcome> out = new java.util.HashMap<>();
        if (memberEmails == null || memberEmails.isEmpty() || windowStart == null) return out;
        for (String raw : memberEmails) {
            String email = normalize(raw);
            if (email == null) continue;
            try {
                List<NotificationLog> rows = notificationLogRepo
                        .findByRecipientEmailAndTypeAndTimestampAfterOrderByTimestampAsc(
                                email, "check_in_request", windowStart);
                if (rows == null || rows.isEmpty()) continue; // stays UNKNOWN
                // The LAST attempt is the one that describes the current state:
                // a retry that succeeded after a failure is a send, not a failure.
                NotificationLog latest = rows.get(rows.size() - 1);
                out.put(email, latest.isSuccess()
                        ? GroupMemberViewDto.DispatchOutcome.SENT
                        : GroupMemberViewDto.DispatchOutcome.FAILED);
            } catch (Exception e) {
                // Unknown is the truthful fallback; never invent a negative.
            }
        }
        return out;
    }

    /**
     * The saved places the roster may name, keyed by id — fetched for members
     * whose location this group can see, and only those. A member behind the
     * gate contributes nothing to the query, so the gate is applied before any
     * place row is even read.
     */
    private Map<Long, UserSavedLocation> currentPlacesFor(Collection<UserInfo> users,
                                                         String groupId, String groupType,
                                                         boolean alertActive) {
        if (savedLocationRepo == null || users == null || users.isEmpty()) return Map.of();
        Set<Long> ids = new HashSet<>();
        for (UserInfo u : users) {
            if (u == null || u.getCurrentPlaceId() == null) continue;
            if (!shouldShareLocation(u, groupId, groupType, alertActive)) continue;
            ids.add(u.getCurrentPlaceId());
        }
        if (ids.isEmpty()) return Map.of();
        Map<Long, UserSavedLocation> out = new HashMap<>();
        for (UserSavedLocation p : savedLocationRepo.findAllById(ids)) {
            if (p != null && p.getId() != null) out.put(p.getId(), p);
        }
        return out;
    }

    private static boolean anyLocated(Collection<UserInfo> users, String groupId,
                                      String groupType, boolean alertActive) {
        for (UserInfo u : users) {
            if (u != null && u.getLastKnownLat() != null && u.getLastKnownLng() != null
                    && shouldShareLocation(u, groupId, groupType, alertActive)) {
                return true;
            }
        }
        return false;
    }

    MemberSummary toMemberSummary(String email, UserInfo u,
                                  String groupId, String groupType,
                                  boolean alertActive,
                                  Instant checkInRequestedAt,
                                  GroupMemberViewDto.DispatchOutcome dispatch,
                                  Map<Long, UserSavedLocation> currentPlaces,
                                  boolean includePhone,
                                  List<MemberAlertAreaService.AreaAlert> activeAreas) {
        String dispatchWire = (dispatch == null
                ? GroupMemberViewDto.DispatchOutcome.UNKNOWN
                : dispatch).wire();
        if (u == null) {
            return new MemberSummary(normalize(email), null, null, null, null,
                    null, null, null, null, checkInRequestedAt, dispatchWire,
                    null, null, null, null, null, null);
        }
        SelfStatus status = new SelfStatus(
                u.getUserStatus(), u.getStatusColor(), u.getUserStatusLastUpdated(),
                statusSetByName(u)
        );

        // Gate live location on the member's per-group sharing pref +
        // current alert state. When the gate denies, lat/lng/at are null;
        // FE renders these members as "unknown" presence.
        Double lat = u.getLastKnownLat();
        Double lng = u.getLastKnownLng();
        Instant locAt = u.getLastKnownLocationAt();
        boolean shared = shouldShareLocation(u, groupId, groupType, alertActive);
        if (!shared) {
            lat = null;
            lng = null;
            locAt = null;
        }

        // ── THE SAME GATE, FOR EVERY FIELD DERIVED FROM THE FIX (V83) ──────
        // `located` is false both when the gate is closed and when there has
        // never been a fix, and every derived field is null in both cases —
        // identically. That is the point: a roster must not be able to tell
        // "chose not to share with this group" from "location never turned on"
        // (locked 2026-07-02; a person hiding from an abuser relies on it).
        // Add a new location-derived field? It goes inside this branch.
        boolean located = shared && lat != null && lng != null;
        GroupMemberViewDto.AtPlace atPlace = null;
        String lastSeenNear = null;
        String locationSource = null;
        Integer locationAccuracyM = null;
        List<String> inAlertIds = null;
        if (located) {
            UserSavedLocation place = u.getCurrentPlaceId() == null || currentPlaces == null
                    ? null
                    : currentPlaces.get(u.getCurrentPlaceId());
            atPlace = LocationPresenceService.atPlaceOf(place, u.getUserEmail(), u.getCurrentPlaceSince());
            lastSeenNear = blankToNull(u.getLastSeenNearLabel());
            locationSource = LocationPresenceService.normalizeSource(u.getLocationSource());
            locationAccuracyM = u.getLocationAccuracyM();
            inAlertIds = activeAreas == null || alertAreas == null
                    ? null
                    : alertAreas.idsFor(activeAreas, lat, lng);
        }

        return new MemberSummary(
                normalize(u.getUserEmail()),
                u.getUserFirstName(),
                u.getUserLastName(),
                DtoImages.avatar(u.getProfileImageUrl()),
                status,
                u.getLastActiveAt(),
                lat, lng, locAt,
                checkInRequestedAt,
                dispatchWire,
                atPlace,
                lastSeenNear,
                locationSource,
                locationAccuracyM,
                includePhone ? blankToNull(u.getPhone()) : null,
                inAlertIds
        );
    }

    /**
     * Whether {@code u}'s live location is visible to this group right now.
     *
     * <p>The rule itself, its defaults and the reason {@code never} is absolute
     * all live in {@link LocationSharing}, which is the single owner. This used
     * to be one of THREE independent implementations — the others in
     * {@code UserInfoService} and, wrongly, in the frontend — and the drift
     * between them is what that class was created to end. Do not reinline it.</p>
     */
    private static boolean shouldShareLocation(UserInfo u, String groupId,
                                               String groupType, boolean alertActive) {
        return LocationSharing.shouldShare(
                u.getGroupLocationSharing(), groupId, groupType, alertActive);
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

    private static GroupMemberViewDto.ViewerCapabilities viewerCapabilities(Group g, String viewerEmail) {
        MemberActionPolicy.Capabilities c = MemberActionPolicy.of(g, viewerEmail);
        return new GroupMemberViewDto.ViewerCapabilities(
                c.setOthersStatus(), c.nudge(), c.askEveryone(), c.pingMissing());
    }

    @org.springframework.beans.factory.annotation.Value("${app.groupAlert.decayHours:48}")
    private int checkInHours = 48;

    private String resolveViewerRole(Group g, String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return "none";
        if (viewerEmail.equalsIgnoreCase(g.getOwnerEmail())) return "owner";
        if (g.getAdminEmails() != null && g.getAdminEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(viewerEmail))) return "admin";
        if (g.getMemberEmails() != null && g.getMemberEmails().stream()
                .anyMatch(e -> e != null && e.equalsIgnoreCase(viewerEmail))) return "member";
        return "none";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
