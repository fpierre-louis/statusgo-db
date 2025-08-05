package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.UserInfoService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        return userInfoService.getAllUsers(); // Admin-level endpoint
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserInfo> getUserById(@PathVariable String id) {
        Optional<UserInfo> userInfo = userInfoService.getUserById(id);
        return userInfo.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/email")
    public ResponseEntity<UserInfo> getUserByEmail() {
        String email = AuthUtils.getCurrentUserEmail();
        Optional<UserInfo> userInfo = userInfoService.getUserByEmail(email);
        return userInfo.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public UserInfo createUser(@RequestBody UserInfo userInfo) {
        return userInfoService.createUser(userInfo);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserInfo> updateUser(@PathVariable String id, @RequestBody UserInfo userDetails) {
        String email = AuthUtils.getCurrentUserEmail();
        Optional<UserInfo> optionalUser = userInfoService.getUserById(id);

        if (optionalUser.isPresent() && optionalUser.get().getUserEmail().equalsIgnoreCase(email)) {
            UserInfo updatedUser = userInfoService.updateUser(userDetails);
            return ResponseEntity.ok(updatedUser);
        } else {
            return ResponseEntity.status(403).build(); // Forbidden
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userInfoService.deleteUser(id); // Admin/internal
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserInfo> patchUser(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        if (updates.isEmpty()) return ResponseEntity.badRequest().build();

        try {
            UserInfo updatedUser = userInfoService.patchUser(id, updates);
            return ResponseEntity.ok(updatedUser);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).build(); // Unauthorized patch
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build(); // User not found
        } catch (Exception e) {
            return ResponseEntity.status(500).build(); // Server error
        }
    }
}
