package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class UserInfoService {

    private final UserInfoRepo userInfoRepo;

    @Autowired
    public UserInfoService(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    /**
     * Get all users
     */
    public List<UserInfo> getAllUsers() {
        return userInfoRepo.findAll();
    }

    /**
     * Get user by ID
     */
    public Optional<UserInfo> getUserById(String id) {
        return userInfoRepo.findById(id);
    }

    /**
     * Get user by Email
     */
    public Optional<UserInfo> getUserByEmail(String email) {
        return userInfoRepo.findByUserEmail(email);
    }

    /**
     * Create a new user
     */
    public UserInfo createUser(UserInfo userInfo) {
        return userInfoRepo.save(userInfo);
    }

    /**
     * Update an entire UserInfo object (used in PUT)
     */
    public UserInfo updateUser(UserInfo userInfo) {
        return userInfoRepo.save(userInfo);
    }

    /**
     * Delete a user by ID
     */
    public void deleteUser(String id) {
        userInfoRepo.deleteById(id);
    }

    /**
     * Partially update a UserInfo object (used in PATCH)
     */
    public UserInfo patchUser(String id, Map<String, Object> updates) {
        Optional<UserInfo> optionalUser = getUserById(id);
        if (optionalUser.isPresent()) {
            UserInfo userInfo = optionalUser.get();

            // Update only the fields provided in the "updates" map
            updates.forEach((key, value) -> {
                try {
                    if (key == null || value == null) {
                        System.out.println("Key or Value is null for key: " + key);
                        return;
                    }

                    Field field = ReflectionUtils.findField(UserInfo.class, key);
                    if (field != null) {
                        field.setAccessible(true);
                        Object oldValue = ReflectionUtils.getField(field, userInfo);

                        // Update the field only if the value is different
                        if (!Objects.equals(oldValue, value)) {
                            ReflectionUtils.setField(field, userInfo, value);
                            System.out.println("Updated field: " + key + " from " + oldValue + " to " + value);

                            // Check if "userStatus" was updated, then update the userStatusLastUpdated timestamp
                            if ("userStatus".equals(key) && !Objects.equals(oldValue, value)) {
                                userInfo.setUserStatusLastUpdated(Instant.now());
                                System.out.println("Updated userStatusLastUpdated because userStatus changed.");
                            }

                            // Check if "activeGroupAlertCounts" was updated, then update the alert timestamp
                            if ("activeGroupAlertCounts".equals(key) && !Objects.equals(oldValue, value)) {
                                userInfo.setGroupAlertLastUpdated(Instant.now());
                                System.out.println("Updated groupAlertLastUpdated because activeGroupAlertCounts changed.");
                            }
                        } else {
                            System.out.println("No change detected for field: " + key);
                        }
                    } else {
                        System.out.println("Field not found: " + key);
                    }
                } catch (Exception e) {
                    System.out.println("Error updating field " + key + ": " + e.getMessage());
                }
            });

            // Save and return the updated user
            return userInfoRepo.save(userInfo);
        } else {
            throw new IllegalArgumentException("User with ID " + id + " not found");
        }
    }


    /**
     * Update only the user status (no need to load the full object)
     */
    public void updateUserStatus(String id, String status) {
        userInfoRepo.updateUserStatus(id, status);
    }

    /**
     * Update user status and status color (no need to load the full object)
     */
    public void updateUserStatusAndColor(String id, String status, String color) {
        userInfoRepo.updateUserStatusAndColor(id, status, color);
    }

    /**
     * Update user status and status color using native query (fastest method)
     */
    public void updateUserStatusAndColorNative(String id, String status, String color) {
        userInfoRepo.updateUserStatusAndColorNative(id, status, color);
    }
}
