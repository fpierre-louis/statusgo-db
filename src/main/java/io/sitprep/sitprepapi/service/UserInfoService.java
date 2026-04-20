package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

@Service
public class UserInfoService {

    private final UserInfoRepo userInfoRepo;

    @Autowired
    public UserInfoService(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    public List<UserInfo> getAllUsers() { return userInfoRepo.findAll(); }

    public Optional<UserInfo> getUserById(String id) { return userInfoRepo.findById(id); }

    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmailIgnoreCase(email);
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

    public UserInfo patchUserById(String id, Map<String, Object> updates) {
        UserInfo userInfo = userInfoRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User with ID " + id + " not found"));

        updates.forEach((key, value) -> {
            if (key == null || value == null) return;
            if (Set.of("id", "userEmail").contains(key)) return;

            // ✅ avoid UID being overwritten with null/blank
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

        return userInfoRepo.save(userInfo);
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