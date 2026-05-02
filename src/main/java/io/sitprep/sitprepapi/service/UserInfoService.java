package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.ProfileSummaryDto;
import io.sitprep.sitprepapi.dto.PublicProfileDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.TaskRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.PublicCdn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserInfoService {

    private final UserInfoRepo userInfoRepo;
    private final HouseholdEventService householdEventService;
    private final GroupRepo groupRepo;
    private final TaskRepo taskRepo;
    private final FollowService followService;
    private final BlockService blockService;

    @Autowired
    public UserInfoService(UserInfoRepo userInfoRepo,
                           HouseholdEventService householdEventService,
                           GroupRepo groupRepo,
                           TaskRepo taskRepo,
                           FollowService followService,
                           BlockService blockService) {
        this.userInfoRepo = userInfoRepo;
        this.householdEventService = householdEventService;
        this.groupRepo = groupRepo;
        this.taskRepo = taskRepo;
        this.followService = followService;
        this.blockService = blockService;
    }

    public List<UserInfo> getAllUsers() { return userInfoRepo.findAll(); }

    public Optional<UserInfo> getUserById(String id) { return userInfoRepo.findById(id); }

    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmailIgnoreCase(email);
    }

    /**
     * Resolve the public-facing profile for {@code idOrEmail}. Per
     * {@code docs/PROFILE_AND_FOLLOW.md} build-order step 1 — read-only;
     * no follow / privacy / helps-given wiring yet. The lookup tries id
     * first, then email (case-insensitive), so the FE can pass either
     * key from a post byline or an avatar tap.
     *
     * <p>Folds in the user's public groups (excluding {@code Household})
     * and their authored community-feed posts (Tasks with
     * {@code groupId == null}, capped at 10) so the page renders with
     * one round trip — matches the codebase principle "backend shapes
     * the data, frontend just displays" (per CLAUDE.md).</p>
     *
     * <p>Returns {@code Optional.empty()} when no user matches; the
     * resource layer turns that into a 404.</p>
     */
    @Transactional(readOnly = true)
    public Optional<PublicProfileDto> getPublicProfile(String idOrEmail) {
        return getPublicProfile(idOrEmail, null);
    }

    @Transactional(readOnly = true)
    public Optional<PublicProfileDto> getPublicProfile(String idOrEmail, String viewerEmail) {
        if (idOrEmail == null || idOrEmail.isBlank()) return Optional.empty();
        String key = idOrEmail.trim();

        // id first (UUID); fall back to email so both routing styles work.
        Optional<UserInfo> hit = userInfoRepo.findById(key);
        if (hit.isEmpty()) hit = userInfoRepo.findByUserEmailIgnoreCase(key);
        if (hit.isEmpty()) return Optional.empty();

        UserInfo u = hit.get();
        String email = u.getUserEmail();

        // Block trumps everything — empty Optional turns into 404 at
        // the resource layer per docs/PROFILE_AND_FOLLOW.md step 5
        // ("Block trumps everything: a blocked user sees you don't
        // exist"). Symmetric — either direction triggers the 404.
        if (blockService.isAnyBlock(viewerEmail, email)) {
            return Optional.empty();
        }

        String viewerRelationship = followService.getRelationship(viewerEmail, email);

        // Privacy gate — when the viewer can't see the full profile,
        // return a stub. Self always passes the gate.
        boolean viewerIsSelf = "self".equals(viewerRelationship);
        if (!viewerIsSelf && !isProfileVisibleToViewer(u, viewerEmail, viewerRelationship)) {
            return Optional.of(PublicProfileDto.stub(u, viewerRelationship));
        }

        // Public groups — exclude Household (personal, not a public
        // trust signal) and de-dup by groupId in case the user appears
        // both as admin and member (legacy data quirk).
        List<Group> raw = email == null ? List.of() : groupRepo.findByMemberEmail(email);
        Map<String, Group> byId = new LinkedHashMap<>();
        for (Group g : raw) {
            if (g == null || g.getGroupId() == null) continue;
            if ("Household".equalsIgnoreCase(g.getGroupType())) continue;
            byId.putIfAbsent(g.getGroupId(), g);
        }
        List<PublicProfileDto.PublicGroupSummary> groupSummaries = byId.values().stream()
                .map(g -> new PublicProfileDto.PublicGroupSummary(
                        g.getGroupId(),
                        g.getGroupName(),
                        g.getGroupType(),
                        g.getMemberCount()
                ))
                .collect(Collectors.toList());

        // Public posts — community-scope Tasks (groupId == null) the
        // user authored, newest first, capped at 10. Group-scope posts
        // (GroupPost entity) stay group-internal.
        List<Task> tasks = email == null ? List.of()
                : taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(email);
        List<PublicProfileDto.PublicPostSummary> postSummaries = tasks.stream()
                .filter(t -> t.getGroupId() == null)
                .limit(10)
                .map(t -> {
                    String firstImageUrl = null;
                    List<String> keys = t.getImageKeys();
                    if (keys != null && !keys.isEmpty()) {
                        firstImageUrl = PublicCdn.toPublicUrl(keys.get(0));
                    }
                    return new PublicProfileDto.PublicPostSummary(
                            t.getId(),
                            t.getTitle(),
                            t.getKind(),
                            t.getDescription(),
                            firstImageUrl,
                            t.getStatus() == null ? null : t.getStatus().name(),
                            t.getCreatedAt()
                    );
                })
                .collect(Collectors.toList());

        // postCount is the FULL community-post count, not the truncated
        // list — so the trust row reads honestly even when the FE
        // paginates the visible cards.
        int postCount = (int) tasks.stream().filter(t -> t.getGroupId() == null).count();

        return Optional.of(PublicProfileDto.of(
                u,
                groupSummaries.size(),
                postCount,
                groupSummaries,
                postSummaries,
                viewerRelationship
        ));
    }

    /**
     * Privacy gate per docs/PROFILE_AND_FOLLOW.md step 5. Maps the
     * target's {@code profileVisibility} setting to a yes/no on
     * "can the viewer see the full profile":
     *
     * <ul>
     *   <li>{@code public}    — anyone</li>
     *   <li>{@code circles}   — viewer shares any group with target (default)</li>
     *   <li>{@code followers} — viewer follows target OR shares any group</li>
     *   <li>{@code private}   — viewer shares any group with target (same gate as circles, but the FE will gate posts more tightly)</li>
     * </ul>
     *
     * <p>Unknown / null setting falls through to the {@code circles}
     * default. Caller must have already confirmed the viewer is not
     * the target (self always passes).</p>
     */
    private boolean isProfileVisibleToViewer(UserInfo target, String viewerEmail, String viewerRelationship) {
        String setting = target.getProfileVisibility();
        if (setting == null || setting.isBlank()) setting = "circles";

        if ("public".equalsIgnoreCase(setting)) return true;

        boolean sharesCircle = sharesCircleWith(target, viewerEmail);

        if ("circles".equalsIgnoreCase(setting)) return sharesCircle;
        if ("private".equalsIgnoreCase(setting)) return sharesCircle;
        if ("followers".equalsIgnoreCase(setting)) {
            // "follower" = viewer follows target; "mutual" includes that.
            boolean viewerFollows = "follower".equals(viewerRelationship)
                    || "mutual".equals(viewerRelationship);
            return viewerFollows || sharesCircle;
        }
        // Unknown vocabulary — fail safe to circles default.
        return sharesCircle;
    }

    /**
     * True when {@code viewerEmail} is a member of any group {@code target}
     * is in (managed OR joined). The Group entity stores member lists as
     * a flat collection — we compare via lookup-by-email so the check is
     * a single repo round trip rather than walking both UserInfo group-id
     * sets and resolving each group.
     */
    private boolean sharesCircleWith(UserInfo target, String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return false;
        if (target.getUserEmail() == null) return false;

        // Pull both users' groups (Household excluded — those are private
        // by construction and shouldn't grant cross-visibility). Compare
        // by groupId.
        String targetEmail = target.getUserEmail();
        Set<String> targetGroupIds = groupRepo.findByMemberEmail(targetEmail).stream()
                .filter(g -> !"Household".equalsIgnoreCase(g.getGroupType()))
                .map(io.sitprep.sitprepapi.domain.Group::getGroupId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (targetGroupIds.isEmpty()) return false;

        return groupRepo.findByMemberEmail(viewerEmail).stream()
                .filter(g -> !"Household".equalsIgnoreCase(g.getGroupType()))
                .map(io.sitprep.sitprepapi.domain.Group::getGroupId)
                .anyMatch(targetGroupIds::contains);
    }

    /**
     * Batch lookup: returns a lightweight profile summary for each requested
     * email. Unknown or blank emails are skipped. Emails are normalized to
     * lowercase for the lookup; the returned {@code email} field preserves the
     * DB value so callers can still key by it.
     *
     * Replaces the frontend pattern of fanning out N {@code fetchUserProfileByEmail}
     * calls across a group roster (admin manage-members, status cards, etc.).
     */
    @Transactional(readOnly = true)
    public List<ProfileSummaryDto> getProfileSummariesByEmails(List<String> emails) {
        if (emails == null || emails.isEmpty()) return List.of();

        List<String> normalized = emails.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
        if (normalized.isEmpty()) return List.of();

        return userInfoRepo.findByUserEmailIn(normalized).stream()
                .map(u -> new ProfileSummaryDto(
                        u.getUserEmail(),
                        u.getUserFirstName(),
                        u.getUserLastName(),
                        u.getProfileImageURL(),
                        u.getUserStatus(),
                        u.getStatusColor(),
                        u.getUserStatusLastUpdated(),
                        u.getLastActiveAt(),
                        u.isVerifiedPublisher(),
                        u.getVerifiedPublisherKind()
                ))
                .collect(Collectors.toList());
    }

    // ✅ NEW
    public Optional<UserInfo> getUserByFirebaseUid(String uid) {
        if (uid == null || uid.isBlank()) return Optional.empty();
        return userInfoRepo.findByFirebaseUid(uid.trim());
    }

    public UserInfo createUser(UserInfo userInfo) {
        if (userInfo.getUserEmail() == null || userInfo.getUserEmail().isBlank())
            throw new IllegalArgumentException("userEmail is required");
        return userInfoRepo.save(userInfo);
    }

    public UserInfo updateUserById(String id, UserInfo incoming) {
        UserInfo existing = userInfoRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        existing.setUserFirstName(incoming.getUserFirstName());
        existing.setUserLastName(incoming.getUserLastName());
        existing.setPhone(incoming.getPhone());
        existing.setAddress(incoming.getAddress());
        existing.setUserStatus(incoming.getUserStatus());
        existing.setStatusColor(incoming.getStatusColor());
        existing.setProfileImageURL(incoming.getProfileImageURL());
        existing.setSubscription(incoming.getSubscription());
        existing.setSubscriptionPackage(incoming.getSubscriptionPackage());
        existing.setDateSubscribed(incoming.getDateSubscribed());
        existing.setFcmtoken(incoming.getFcmtoken());
        existing.setManagedGroupIDs(incoming.getManagedGroupIDs());
        existing.setJoinedGroupIDs(incoming.getJoinedGroupIDs());

        // ✅ keep uid if provided (but don’t null it)
        if (incoming.getFirebaseUid() != null && !incoming.getFirebaseUid().isBlank()) {
            existing.setFirebaseUid(incoming.getFirebaseUid().trim());
        }

        return userInfoRepo.save(existing);
    }

    public void deleteUser(String id) { userInfoRepo.deleteById(id); }

    /**
     * Merge an incoming partial map into the user's groupLocationSharing
     * preference. {@code null} mode values clear an entry (reverts to the
     * group's default). Unknown groups are accepted — the FE may
     * pre-record a preference before joining.
     */
    @Transactional
    public Map<String, String> mergeGroupLocationSharingByEmail(String email, Map<String, String> patch) {
        if (email == null || email.isBlank()) return Map.of();
        return userInfoRepo.findByUserEmailIgnoreCase(email.trim())
                .map(u -> {
                    Map<String, String> map = u.getGroupLocationSharing();
                    if (map == null) {
                        map = new HashMap<>();
                        u.setGroupLocationSharing(map);
                    }
                    if (patch != null) {
                        for (Map.Entry<String, String> e : patch.entrySet()) {
                            String k = e.getKey();
                            String v = e.getValue();
                            if (k == null || k.isBlank()) continue;
                            if (v == null) map.remove(k);
                            else map.put(k, v);
                        }
                    }
                    userInfoRepo.save(u);
                    return new HashMap<>(map);
                })
                .orElseGet(HashMap::new);
    }

    /**
     * Presence-location ping handler. Updates {@code lastKnownLat/Lng} +
     * {@code lastKnownLocationAt} on the user identified by email. Silently
     * no-ops if the user doesn't exist (the FE may have stale identity).
     */
    @Transactional
    public void updateLastKnownLocationByEmail(String email, Double lat, Double lng) {
        if (email == null || email.isBlank() || lat == null || lng == null) return;
        userInfoRepo.findByUserEmailIgnoreCase(email.trim()).ifPresent(u -> {
            u.setLastKnownLat(lat);
            u.setLastKnownLng(lng);
            u.setLastKnownLocationAt(Instant.now());
            userInfoRepo.save(u);
        });
    }

    /**
     * Stamp {@code lastAssessmentAt = now()} on the user record. Backs
     * {@code POST /api/userinfo/me/assessment} which the frontend calls
     * when the Readiness Assessment quiz at /sitprep-quiz finishes.
     * Idempotent — calling twice just refreshes the timestamp.
     */
    @Transactional
    public void markAssessmentCompleteByEmail(String email) {
        if (email == null || email.isBlank()) return;
        userInfoRepo.findByUserEmailIgnoreCase(email.trim()).ifPresent(u -> {
            u.setLastAssessmentAt(Instant.now());
            userInfoRepo.save(u);
        });
    }

    public UserInfo patchUserById(String id, Map<String, Object> updates) {
        UserInfo userInfo = userInfoRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User with ID " + id + " not found"));

        // Capture pre-patch userStatus so we can fire a status-changed event
        // after a successful save when (and only when) it actually changed.
        String oldUserStatus = userInfo.getUserStatus();

        updates.forEach((key, value) -> {
            if (key == null || value == null) return;
            if (Set.of("id", "userEmail").contains(key)) return;

            if ("firebaseUid".equals(key) && (value.toString().isBlank())) return;

            try {
                Field field = ReflectionUtils.findField(UserInfo.class, key);
                if (field != null) {
                    field.setAccessible(true);
                    Object oldValue = ReflectionUtils.getField(field, userInfo);
                    if (!Objects.equals(oldValue, value)) {
                        ReflectionUtils.setField(field, userInfo, value);
                        if ("activeGroupAlertCounts".equals(key)) {
                            userInfo.setGroupAlertLastUpdated(Instant.now());
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("Error updating field " + key + ": " + e.getMessage());
            }
        });

        UserInfo saved = userInfoRepo.save(userInfo);

        String newStatus = saved.getUserStatus();
        if (newStatus != null && !Objects.equals(oldUserStatus, newStatus)
                && saved.getUserEmail() != null) {
            householdEventService.recordStatusChangedForActor(saved.getUserEmail(), newStatus);
        }

        return saved;
    }

    /** Existing: idempotent upsert by email */
    @Transactional
    public UserInfo upsertByEmail(String email, UserInfo patch) {
        String norm = Optional.ofNullable(email).map(String::trim).map(String::toLowerCase)
                .orElseThrow(() -> new IllegalArgumentException("email required"));

        Optional<UserInfo> existing = userInfoRepo.findByUserEmailIgnoreCase(norm);
        boolean isNew = existing.isEmpty();
        UserInfo entity = existing.orElseGet(() -> {
            UserInfo u = new UserInfo();
            u.setUserEmail(norm);
            u.setUserFirstName(Optional.ofNullable(patch.getUserFirstName()).orElse("User"));
            u.setUserLastName(Optional.ofNullable(patch.getUserLastName()).orElse(""));
            return u;
        });

        // ✅ attach UID if provided
        if (patch.getFirebaseUid() != null && !patch.getFirebaseUid().isBlank()) {
            entity.setFirebaseUid(patch.getFirebaseUid().trim());
        }

        // Profile fields (always safe to apply on merge)
        applyPatch(entity, patch);

        // System fields (status / subscription / FCM / group membership) are
        // ONLY initialized on create. On merge we preserve whatever is already
        // on the entity — those flow through their dedicated APIs
        // (patchUser, /api/groups/* membership endpoints, billing).
        if (isNew) applyInitialSystemDefaults(entity, patch);

        try {
            return userInfoRepo.save(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return userInfoRepo.findByUserEmailIgnoreCase(norm).orElseThrow(() -> e);
        }
    }

    /** ✅ NEW: idempotent upsert by Firebase UID (preferred for SitPrep + Rediscover) */
    @Transactional
    public UserInfo upsertByFirebaseUid(String uid, UserInfo patch) {
        String normUid = Optional.ofNullable(uid).map(String::trim)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("firebaseUid required"));

        // If patch email exists, normalize it (helps with initial creation)
        String normEmail = Optional.ofNullable(patch.getUserEmail())
                .map(String::trim).map(String::toLowerCase)
                .orElse(null);

        // 1) Prefer UID record
        Optional<UserInfo> byUid = userInfoRepo.findByFirebaseUid(normUid);
        if (byUid.isPresent()) {
            UserInfo entity = byUid.get();

            // If email is present and different, update it (still unique)
            if (normEmail != null && !normEmail.equalsIgnoreCase(entity.getUserEmail())) {
                entity.setUserEmail(normEmail);
            }

            applyPatch(entity, patch);
            entity.setFirebaseUid(normUid);
            return userInfoRepo.save(entity);
        }

        // 2) Fallback: if email exists, see if an old record exists and attach UID
        if (normEmail != null) {
            Optional<UserInfo> byEmail = userInfoRepo.findByUserEmailIgnoreCase(normEmail);
            if (byEmail.isPresent()) {
                UserInfo entity = byEmail.get();
                entity.setFirebaseUid(normUid);
                applyPatch(entity, patch);
                return userInfoRepo.save(entity);
            }
        }

        // 3) Create new
        UserInfo created = new UserInfo();
        created.setFirebaseUid(normUid);
        created.setUserEmail(normEmail != null ? normEmail : "unknown@email.invalid"); // you can enforce required email if you want
        created.setUserFirstName(Optional.ofNullable(patch.getUserFirstName()).orElse("User"));
        created.setUserLastName(Optional.ofNullable(patch.getUserLastName()).orElse(""));
        applyPatch(created, patch);
        applyInitialSystemDefaults(created, patch);
        return userInfoRepo.save(created);
    }

    /**
     * Apply user-editable profile fields from the incoming patch onto an
     * existing or new entity. Safe to call on merge — we only touch data that
     * the user controls through the profile UI.
     *
     * IMPORTANT: do NOT add system fields here (status, subscription, FCM
     * token, group membership). Those must only be initialized in
     * {@link #applyInitialSystemDefaults} on create, and mutated via their
     * dedicated APIs thereafter. Adding them here previously let auth upserts
     * silently wipe existing membership, status, and subscription state any
     * time the frontend's default "safe" patch came through.
     */
    private void applyPatch(UserInfo entity, UserInfo patch) {
        if (patch == null) return;

        if (patch.getUserFirstName() != null) entity.setUserFirstName(patch.getUserFirstName());
        if (patch.getUserLastName()  != null) entity.setUserLastName(patch.getUserLastName());
        if (patch.getProfileImageURL()!= null) entity.setProfileImageURL(patch.getProfileImageURL());
        if (patch.getPhone()         != null) entity.setPhone(patch.getPhone());
        if (patch.getAddress()       != null) entity.setAddress(patch.getAddress());
        if (patch.getTitle()         != null) entity.setTitle(patch.getTitle());
        if (patch.getLatitude()      != null) entity.setLatitude(patch.getLatitude());
        if (patch.getLongitude()     != null) entity.setLongitude(patch.getLongitude());
        if (patch.getBio()           != null) entity.setBio(patch.getBio());
        if (patch.getCoverImageUrl() != null) entity.setCoverImageUrl(patch.getCoverImageUrl());
        if (patch.getProfileVisibility() != null) entity.setProfileVisibility(patch.getProfileVisibility());

        // email handled by caller (uid upsert may need special rules)
    }

    /**
     * Set initial system-field defaults — only ever invoked on create. These
     * fields are managed via dedicated endpoints (status PATCH, membership
     * APIs, billing, FCM registration) after creation; never clobber them on
     * merge.
     */
    private void applyInitialSystemDefaults(UserInfo entity, UserInfo patch) {
        entity.setUserStatus(patch != null && patch.getUserStatus() != null
                ? patch.getUserStatus() : "NO RESPONSE");
        entity.setStatusColor(patch != null && patch.getStatusColor() != null
                ? patch.getStatusColor() : "Gray");
        entity.setSubscription(patch != null && patch.getSubscription() != null
                ? patch.getSubscription() : "Basic");
        entity.setSubscriptionPackage(patch != null && patch.getSubscriptionPackage() != null
                ? patch.getSubscriptionPackage() : "Monthly");
        if (patch != null && patch.getDateSubscribed() != null) {
            entity.setDateSubscribed(patch.getDateSubscribed());
        }
        // fcmtoken intentionally unset — registered via dedicated FCM update.
        // managedGroupIDs / joinedGroupIDs intentionally unset — user joins
        // groups via /api/groups/* membership APIs.
    }
}