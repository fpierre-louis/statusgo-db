package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.ProfileSummaryDto;
import io.sitprep.sitprepapi.service.UserInfoService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User profile CRUD.
 *
 * <p>Phase E enforcement on every WRITE — the verified Firebase token is
 * the source of identity. Body-supplied email or firebaseUid is overridden
 * to the token's values on signup/upsert, so a signed-in user cannot
 * create or mutate another user's record. PUT / PATCH / DELETE additionally
 * verify the {@code {id}} resource belongs to the caller.</p>
 *
 * <p>Reads stay open through the rollout because some flows (group rosters,
 * profile previews, share-link OG generation) hit them anonymously and we
 * don't want to break them mid-rollout. Tighten when we audit reads.</p>
 */
@RestController
@RequestMapping("/api/userinfo")
@CrossOrigin(origins = "http://localhost:3000")
public class UserInfoResource {

    private final UserInfoService userInfoService;

    public UserInfoResource(UserInfoService userInfoService) {
        this.userInfoService = userInfoService;
    }

    /**
     * Dump every user. Originally a dev/debug endpoint; now tightened to
     * require a verified token at minimum. Real admin gating (e.g. via a
     * platform-level admin role) is a follow-up — for now any signed-in
     * user can see this, which is still better than fully open since
     * the response includes other users' emails + group memberships.
     */
    @GetMapping
    public List<UserInfo> getAllUsers() {
        AuthUtils.requireAuthenticatedEmail();
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

    @GetMapping("/firebase/{uid}")
    public ResponseEntity<UserInfo> getUserByFirebaseUid(@PathVariable String uid) {
        return userInfoService.getUserByFirebaseUid(uid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Batch profile lookup. Body: {@code { "emails": ["a@x", "b@y", ...] }}.
     * Returns one {@link ProfileSummaryDto} per known email; unknown/blank
     * emails are omitted. Replaces per-email fan-out on group rosters.
     *
     * Stays open during the rollout — group roster renders fan out to this
     * even before the user finishes auth handshake. Enforce alongside the
     * other reads in a later pass.
     */
    @PostMapping("/profiles/batch")
    public ResponseEntity<List<ProfileSummaryDto>> getProfilesBatch(@RequestBody BatchProfilesRequest request) {
        List<String> emails = request == null ? List.of() : request.emails();
        return ResponseEntity.ok(userInfoService.getProfileSummariesByEmails(emails));
    }

    public record BatchProfilesRequest(List<String> emails) {}

    /**
     * Idempotent upsert by email — used by the signup path after Firebase
     * creates the auth account. The verified email overrides whatever's in
     * the body, so this can't be abused to create records for other users.
     */
    @PostMapping
    public ResponseEntity<UserInfo> createOrUpsert(@RequestBody UserInfo incoming) {
        String email = AuthUtils.requireAuthenticatedEmail();
        incoming.setUserEmail(email);
        // Pass verified UID through too so the upsertByEmail fallback path
        // (which also accepts uid for back-compat) attaches it correctly.
        String uid = AuthUtils.getCurrentFirebaseUid();
        if (uid != null && (incoming.getFirebaseUid() == null || incoming.getFirebaseUid().isBlank())) {
            incoming.setFirebaseUid(uid);
        }
        UserInfo saved = userInfoService.upsertByEmail(email, incoming);
        return ResponseEntity.ok(saved);
    }

    /**
     * Idempotent upsert by Firebase UID — preferred path. Verified UID
     * overrides body. If the body carries an email, accept it (the upsert
     * uses both as lookup keys); if not, fall back to the verified email.
     */
    @PostMapping("/firebase")
    public ResponseEntity<UserInfo> createOrUpsertByUid(@RequestBody UserInfo incoming) {
        String uid = AuthUtils.requireAuthenticatedUid();
        incoming.setFirebaseUid(uid);
        if (incoming.getUserEmail() == null || incoming.getUserEmail().isBlank()) {
            incoming.setUserEmail(AuthUtils.getCurrentUserEmail());
        }
        UserInfo saved = userInfoService.upsertByFirebaseUid(uid, incoming);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserInfo> updateUser(@PathVariable String id, @RequestBody UserInfo userDetails) {
        ensureOwns(id);
        return ResponseEntity.ok(userInfoService.updateUserById(id, userDetails));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        ensureOwns(id);
        userInfoService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserInfo> patchUser(@PathVariable String id, @RequestBody Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) return ResponseEntity.badRequest().build();
        ensureOwns(id);
        return ResponseEntity.ok(userInfoService.patchUserById(id, updates));
    }

    /**
     * Resource-level ownership check. Reject 404 if the record doesn't
     * exist (don't leak which ids exist), 403 if it exists but belongs
     * to someone else. Calling code can assume the verified caller owns
     * {@code id} after this returns.
     */
    private void ensureOwns(String id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Optional<UserInfo> existing = userInfoService.getUserById(id);
        if (existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        UserInfo u = existing.get();
        boolean emailMatches = u.getUserEmail() != null
                && u.getUserEmail().equalsIgnoreCase(caller);
        // Belt-and-suspenders: also accept matching firebaseUid in case
        // the user's email has been changed but the uid is still valid.
        String uid = AuthUtils.getCurrentFirebaseUid();
        boolean uidMatches = uid != null && uid.equals(u.getFirebaseUid());
        if (!emailMatches && !uidMatches) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "User record belongs to a different account");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
