package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.UserInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
        return userInfoService.saveUser(userInfo);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserInfo> updateUser(@PathVariable String id, @RequestBody UserInfo userDetails) {
        Optional<UserInfo> optionalUser = userInfoService.getUserById(id);
        if (optionalUser.isPresent()) {
            UserInfo userInfo = optionalUser.get();
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
            userInfo.setGroupAlert(userDetails.getGroupAlert());
            userInfo.setJoinedGroupIDs(userDetails.getJoinedGroupIDs());
            userInfo.setProfileImageURL(userDetails.getProfileImageURL());
            userInfo.setStatusColor(userDetails.getStatusColor());
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
}
