package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.UserInfoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/userinfo")
@CrossOrigin(origins = "http://localhost:3000")
public class UserInfoResource {

    private final UserInfoService userInfoService;

    public UserInfoResource(UserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    @GetMapping
    public List<UserInfo> getAllUsers() {
        return userInfoService.getAllUsers();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserInfo> getUserById(@PathVariable String id) {
        return userInfoService.getUserById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<UserInfo> getUserByEmail(@PathVariable String email) {
        return userInfoService.getUserByEmail(email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ✅ NEW: fetch by Firebase UID
    @GetMapping("/firebase/{uid}")
    public ResponseEntity<UserInfo> getUserByFirebaseUid(@PathVariable String uid) {
        return userInfoService.getUserByFirebaseUid(uid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ✅ Idempotent create/update by email from the body (no auth)
    @PostMapping
    public ResponseEntity<UserInfo> createOrUpsert(@RequestBody UserInfo incoming) {
        final String email = Optional.ofNullable(incoming.getUserEmail())
                .map(String::trim).map(String::toLowerCase)
                .orElseThrow(() -> new IllegalArgumentException("userEmail is required"));
        UserInfo saved = userInfoService.upsertByEmail(email, incoming);
        return ResponseEntity.ok(saved);
    }

    // ✅ NEW: preferred upsert by Firebase UID
    @PostMapping("/firebase")
    public ResponseEntity<UserInfo> createOrUpsertByUid(@RequestBody UserInfo incoming) {
        final String uid = Optional.ofNullable(incoming.getFirebaseUid())
                .map(String::trim)
                .orElseThrow(() -> new IllegalArgumentException("firebaseUid is required"));
        UserInfo saved = userInfoService.upsertByFirebaseUid(uid, incoming);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserInfo> updateUser(@PathVariable String id, @RequestBody UserInfo userDetails) {
        return ResponseEntity.ok(userInfoService.updateUserById(id, userDetails));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userInfoService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserInfo> patchUser(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(userInfoService.patchUserById(id, updates));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}