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
     * Updates only the provided fields in the "updates" map
     * Ensures userStatusLastUpdated is only updated if userStatus actually changes
     */
    public UserInfo patchUser(String id, Map<String, Object> updates) {
        Optional<UserInfo> optionalUser = getUserById(id);
        if (optionalUser.isPresent()) {
            UserInfo userInfo = optionalUser.get();

            updates.forEach((key, value) -> {
                if (key == null || value == null) {
                    System.out.println("Invalid key-value pair: Key=" + key + ", Value=" + value);
                    return;
                }

                try {
                    switch (key) {
                        case "userStatus":
                            // Only update if the value is different
                            if (!Objects.equals(userInfo.getUserStatus(), value)) {
                                userInfo.setUserStatus(value.toString());
                                userInfo.setUserStatusLastUpdated(Instant.now());
                                System.out.println("Updated userStatus to " + value + " and userStatusLastUpdated to " + Instant.now());
                            } else {
                                System.out.println("No change in userStatus for user ID " + id);
                            }
                            break;

                        case "activeGroupAlertCounts":
                            // Update activeGroupAlertCounts and update groupAlertLastUpdated
                            int newAlertCount = Integer.parseInt(value.toString());
                            if (userInfo.getActiveGroupAlertCounts() != newAlertCount) {
                                userInfo.setActiveGroupAlertCounts(newAlertCount);
                                userInfo.setGroupAlertLastUpdated(Instant.now());
                                System.out.println("Updated activeGroupAlertCounts to " + newAlertCount + " and groupAlertLastUpdated to " + Instant.now());
                            } else {
                                System.out.println("No change in activeGroupAlertCounts for user ID " + id);
                            }
                            break;

                        case "userFirstName":
                            if (!Objects.equals(userInfo.getUserFirstName(), value)) {
                                userInfo.setUserFirstName(value.toString());
                                System.out.println("Updated userFirstName to " + value);
                            }
                            break;

                        case "userLastName":
                            if (!Objects.equals(userInfo.getUserLastName(), value)) {
                                userInfo.setUserLastName(value.toString());
                                System.out.println("Updated userLastName to " + value);
                            }
                            break;

                        case "title":
                            if (!Objects.equals(userInfo.getTitle(), value)) {
                                userInfo.setTitle(value.toString());
                                System.out.println("Updated title to " + value);
                            }
                            break;

                        case "subscription":
                            if (!Objects.equals(userInfo.getSubscription(), value)) {
                                userInfo.setSubscription(value.toString());
                                System.out.println("Updated subscription to " + value);
                            }
                            break;

                        case "subscriptionPackage":
                            if (!Objects.equals(userInfo.getSubscriptionPackage(), value)) {
                                userInfo.setSubscriptionPackage(value.toString());
                                System.out.println("Updated subscriptionPackage to " + value);
                            }
                            break;

                        case "fcmtoken":
                            if (!Objects.equals(userInfo.getFcmtoken(), value)) {
                                userInfo.setFcmtoken(value.toString());
                                System.out.println("Updated FCM token to " + value);
                            }
                            break;

                        case "statusColor":
                            if (!Objects.equals(userInfo.getStatusColor(), value)) {
                                userInfo.setStatusColor(value.toString());
                                System.out.println("Updated statusColor to " + value);
                            }
                            break;

                        default:
                            System.out.println("Field not found or unsupported for patch: " + key);
                            break;
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
