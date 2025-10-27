package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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

    public List<UserInfo> getAllUsers() {
        return userInfoRepo.findAll(); // Typically admin-only
    }

    public Optional<UserInfo> getUserById(String id) {
        return userInfoRepo.findById(id);
    }

    // ✅ use case-insensitive lookup
    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmailIgnoreCase(email);
    }

    // ⚠️ Prefer not to use this under permitAll; POST now uses upsertByEmail(...)
    public UserInfo createUser(UserInfo userInfo) {
        String authedEmail = AuthUtils.getCurrentUserEmail();
        if (authedEmail == null) {
            throw new UnsupportedOperationException("createUser() requires authenticated principal");
        }
        userInfo.setUserEmail(authedEmail);
        return userInfoRepo.save(userInfo);
    }

    public UserInfo updateUser(UserInfo userInfo) {
        String email = AuthUtils.getCurrentUserEmail();
        return userInfoRepo.findByUserEmailIgnoreCase(email)
                .map(existing -> {
                    existing.setUserFirstName(userInfo.getUserFirstName());
                    existing.setUserLastName(userInfo.getUserLastName());
                    existing.setPhone(userInfo.getPhone());
                    existing.setAddress(userInfo.getAddress());
                    existing.setUserStatus(userInfo.getUserStatus());
                    return userInfoRepo.save(existing);
                })
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + email));
    }

    public void deleteUser(String id) {
        userInfoRepo.deleteById(id);
    }

    /**
     * Partially update a UserInfo object (used in PATCH).
     * Only allowed if user matches and fields are permitted.
     */
    public UserInfo patchUser(String id, Map<String, Object> updates) {
        String currentEmail = AuthUtils.getCurrentUserEmail();

        return userInfoRepo.findById(id)
                .map(userInfo -> {
                    if (!userInfo.getUserEmail().equalsIgnoreCase(currentEmail)) {
                        throw new SecurityException("Unauthorized to patch this user.");
                    }

                    updates.forEach((key, value) -> {
                        if (key == null || value == null) return;
                        if (Set.of("id", "userEmail").contains(key)) return; // restricted

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
                })
                .orElseThrow(() -> new IllegalArgumentException("User with ID " + id + " not found"));
    }

    /** Create or update by email (case-insensitive). Never throws on duplicate. */
    @Transactional
    public UserInfo upsertByEmail(String email, UserInfo patch) {
        String norm = Optional.ofNullable(email).map(String::trim).map(String::toLowerCase)
                .orElseThrow(() -> new IllegalArgumentException("email required"));

        UserInfo entity = userInfoRepo.findByUserEmailIgnoreCase(norm).orElseGet(() -> {
            UserInfo u = new UserInfo();
            u.setUserEmail(norm);
            // non-nullable defaults
            u.setUserFirstName(Optional.ofNullable(patch.getUserFirstName()).orElse("User"));
            u.setUserLastName(Optional.ofNullable(patch.getUserLastName()).orElse(""));
            return u;
        });

        // Selective, null-safe updates
        if (patch.getUserFirstName() != null) entity.setUserFirstName(patch.getUserFirstName());
        if (patch.getUserLastName()  != null) entity.setUserLastName(patch.getUserLastName());
        if (patch.getProfileImageURL()!= null) entity.setProfileImageURL(patch.getProfileImageURL());
        if (patch.getPhone()         != null) entity.setPhone(patch.getPhone());
        if (patch.getAddress()       != null) entity.setAddress(patch.getAddress());
        if (patch.getUserStatus()    != null) entity.setUserStatus(patch.getUserStatus());
        if (patch.getStatusColor()   != null) entity.setStatusColor(patch.getStatusColor());
        if (patch.getManagedGroupIDs()!= null) entity.setManagedGroupIDs(patch.getManagedGroupIDs());
        if (patch.getJoinedGroupIDs()!= null) entity.setJoinedGroupIDs(patch.getJoinedGroupIDs());
        if (patch.getSubscription()  != null) entity.setSubscription(patch.getSubscription());
        if (patch.getSubscriptionPackage()!= null) entity.setSubscriptionPackage(patch.getSubscriptionPackage());
        if (patch.getDateSubscribed()!= null) entity.setDateSubscribed(patch.getDateSubscribed());
        if (patch.getFcmtoken()      != null) entity.setFcmtoken(patch.getFcmtoken());

        try {
            return userInfoRepo.save(entity);
        } catch (DataIntegrityViolationException e) {
            // In case of a race that just inserted the same email
            return userInfoRepo.findByUserEmailIgnoreCase(norm).orElseThrow(() -> e);
        }
    }
}
