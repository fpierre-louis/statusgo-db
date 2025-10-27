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

    public List<UserInfo> getAllUsers() { return userInfoRepo.findAll(); }

    public Optional<UserInfo> getUserById(String id) { return userInfoRepo.findById(id); }

    // ✅ case-insensitive email lookup
    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmailIgnoreCase(email);
    }

    // ⚠️ Unused in MVP (kept for completeness)
    public UserInfo createUser(UserInfo userInfo) {
        if (userInfo.getUserEmail() == null || userInfo.getUserEmail().isBlank())
            throw new IllegalArgumentException("userEmail is required");
        return userInfoRepo.save(userInfo);
    }

    // ✅ Update by ID (no principal)
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
        return userInfoRepo.save(existing);
    }

    public void deleteUser(String id) { userInfoRepo.deleteById(id); }

    // ✅ Patch by ID (no principal)
    public UserInfo patchUserById(String id, Map<String, Object> updates) {
        UserInfo userInfo = userInfoRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User with ID " + id + " not found"));

        updates.forEach((key, value) -> {
            if (key == null || value == null) return;
            if (Set.of("id", "userEmail").contains(key)) return; // don’t change identity
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

    /** ✅ Idempotent upsert by email (no principal) */
    @Transactional
    public UserInfo upsertByEmail(String email, UserInfo patch) {
        String norm = Optional.ofNullable(email).map(String::trim).map(String::toLowerCase)
                .orElseThrow(() -> new IllegalArgumentException("email required"));

        UserInfo entity = userInfoRepo.findByUserEmailIgnoreCase(norm).orElseGet(() -> {
            UserInfo u = new UserInfo();
            u.setUserEmail(norm);
            u.setUserFirstName(Optional.ofNullable(patch.getUserFirstName()).orElse("User"));
            u.setUserLastName(Optional.ofNullable(patch.getUserLastName()).orElse(""));
            return u;
        });

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
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return userInfoRepo.findByUserEmailIgnoreCase(norm).orElseThrow(() -> e);
        }
    }
}
