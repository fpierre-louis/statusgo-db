package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
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

    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmail(email);
    }

    public UserInfo createUser(UserInfo userInfo) {
        // Always tie user to authenticated email
        userInfo.setUserEmail(AuthUtils.getCurrentUserEmail());
        return userInfoRepo.save(userInfo);
    }

    public UserInfo updateUser(UserInfo userInfo) {
        String email = AuthUtils.getCurrentUserEmail();

        return userInfoRepo.findByUserEmail(email)
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
        // Admin-level use
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

                        // Prevent patching restricted fields
                        if (Set.of("id", "userEmail").contains(key)) return;

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
}
