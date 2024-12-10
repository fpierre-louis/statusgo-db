package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.UserInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/userinfo")
public class UserInfoResource {

    private final UserInfoService userInfoService;

    @Autowired
    public UserInfoResource(UserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    @GetMapping
    public List<UserInfo> getAllUsers() {
        return userInfoService.getAllUsers();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserInfo> getUserById(@PathVariable String id) {
        Optional<UserInfo> userInfo = userInfoService.getUserById(id);
        return userInfo.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<UserInfo> getUserByEmail(@PathVariable String email) {
        Optional<UserInfo> userInfo = userInfoService.getUserByEmail(email);
        return userInfo.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public UserInfo createUser(@RequestBody UserInfo userInfo) {
        return userInfoService.createUser(userInfo);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserInfo> updateUser(@PathVariable String id, @RequestBody UserInfo userDetails) {
        Optional<UserInfo> optionalUser = userInfoService.getUserById(id);
        if (optionalUser.isPresent()) {
            UserInfo userInfo = optionalUser.get();

            // Update all the fields including the new ones
            userInfo.setUserEmail(userDetails.getUserEmail());
            userInfo.setUserFirstName(userDetails.getUserFirstName());
            userInfo.setUserLastName(userDetails.getUserLastName());
            userInfo.setUserStatus(userDetails.getUserStatus());
            userInfo.setTitle(userDetails.getTitle());
            userInfo.setSubscription(userDetails.getSubscription());
            userInfo.setSubscriptionPackage(userDetails.getSubscriptionPackage());
            userInfo.setDateSubscribed(userDetails.getDateSubscribed());
            userInfo.setFcmtoken(userDetails.getFcmtoken());
            userInfo.setManagedGroupIDs(userDetails.getManagedGroupIDs());
            userInfo.setJoinedGroupIDs(userDetails.getJoinedGroupIDs());
            userInfo.setProfileImageURL(userDetails.getProfileImageURL());
            userInfo.setStatusColor(userDetails.getStatusColor());

            // Set the new fields, no need to check for null since activeGroupAlertCount is an int and defaults to 0
            userInfo.setActiveGroupAlertCounts(userDetails.getActiveGroupAlertCounts());
            userInfo.setGroupAlertLastUpdated(Instant.now());

            // Ensure these fields are being set from the request
            userInfo.setPhone(userDetails.getPhone());
            userInfo.setAddress(userDetails.getAddress());
            userInfo.setLongitude(userDetails.getLongitude());
            userInfo.setLatitude(userDetails.getLatitude());

            UserInfo updatedUser = userInfoService.updateUser(userInfo);
            return ResponseEntity.ok(updatedUser);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userInfoService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * PATCH method for updating specific fields in UserInfo
     * Only updates the fields provided in the `updates` map.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<UserInfo> patchUser(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        Optional<UserInfo> optionalUser = userInfoService.getUserById(id);
        if (optionalUser.isPresent()) {
            UserInfo userInfo = optionalUser.get();

            // Update only the fields provided in the "updates" map
            updates.forEach((key, value) -> {
                try {
                    Field field = ReflectionUtils.findField(UserInfo.class, key);
                    if (field != null) {
                        field.setAccessible(true);
                        ReflectionUtils.setField(field, userInfo, value);
                    } else {
                        System.out.println("Field not found: " + key);
                    }
                } catch (Exception e) {
                    System.out.println("Error updating field " + key + ": " + e.getMessage());
                }
            });

            // Optionally, update the group alert timestamp if any fields were updated
            userInfo.setGroupAlertLastUpdated(Instant.now());

            // Save the updated user
            UserInfo updatedUser = userInfoService.updateUser(userInfo);
            return ResponseEntity.ok(updatedUser);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
