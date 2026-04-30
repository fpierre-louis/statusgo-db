package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.ProfileSummaryDto;
import io.sitprep.sitprepapi.dto.PublicProfileDto;
import io.sitprep.sitprepapi.service.AccountDeletionService;
import io.sitprep.sitprepapi.service.AccountDeletionService.OwnedGroupsBlockingException;
import io.sitprep.sitprepapi.service.BlockService;
import io.sitprep.sitprepapi.service.FollowService;
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
    private final AccountDeletionService accountDeletionService;
    private final FollowService followService;
    private final BlockService blockService;

    public UserInfoResource(UserInfoService userInfoService,
                            AccountDeletionService accountDeletionService,
                            FollowService followService,
                            BlockService blockService) {
        this.userInfoService = userInfoService;
        this.accountDeletionService = accountDeletionService;
        this.followService = followService;
        this.blockService = blockService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserInfo> getUserById(@PathVariable String id) {
        AuthUtils.requireAuthenticatedEmail();
        return userInfoService.getUserById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<UserInfo> getUserByEmail(@PathVariable String email) {
        AuthUtils.requireAuthenticatedEmail();
        return userInfoService.getUserByEmail(email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/firebase/{uid}")
    public ResponseEntity<UserInfo> getUserByFirebaseUid(@PathVariable String uid) {
        AuthUtils.requireAuthenticatedEmail();
        return userInfoService.getUserByFirebaseUid(uid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Public profile read. Per {@code docs/PROFILE_AND_FOLLOW.md}
     * build-order step 1 — read-only; resolves the user by either id
     * or email so the FE can route from {@code /profile/:userId} or
     * an avatar-tap byline. Returns the full
     * {@link PublicProfileDto} (identity + cover + bio + verified
     * tier + groups + posts) so the page renders with one round trip.
     *
     * <p>Auth: requires a verified Firebase token (the resource layer
     * doesn't yet honor {@code profileVisibility}; that gate lands in
     * step 5 alongside Block + privacy controls).</p>
     */
    @GetMapping("/{idOrEmail}/profile")
    public ResponseEntity<PublicProfileDto> getPublicProfile(@PathVariable String idOrEmail) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        return userInfoService.getPublicProfile(idOrEmail, viewer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Follow / unfollow {@code idOrEmail}. Per
     * {@code docs/PROFILE_AND_FOLLOW.md} build-order step 3 — follow
     * is one-way; mutual emerges when both sides have a row, no
     * separate friend tier. Idempotent: tapping Follow when you
     * already follow is a no-op (the FE optimistic pattern can
     * double-fire on anxious taps without erroring out).
     *
     * <p>Returns 204 on success, 404 when the target doesn't exist,
     * 400 when the caller tries to follow themselves.</p>
     */
    @PostMapping("/{idOrEmail}/follow")
    public ResponseEntity<Void> follow(@PathVariable String idOrEmail) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        Optional<String> targetEmail = followService.resolveEmail(idOrEmail);
        if (targetEmail.isEmpty()) return ResponseEntity.notFound().build();
        followService.follow(viewer, targetEmail.get());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{idOrEmail}/follow")
    public ResponseEntity<Void> unfollow(@PathVariable String idOrEmail) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        Optional<String> targetEmail = followService.resolveEmail(idOrEmail);
        if (targetEmail.isEmpty()) return ResponseEntity.notFound().build();
        followService.unfollow(viewer, targetEmail.get());
        return ResponseEntity.noContent().build();
    }

    /**
     * Block / unblock {@code idOrEmail}. Per docs/PROFILE_AND_FOLLOW.md
     * step 5 — block is the safety primitive that trumps everything
     * else. Block tears down both follow edges as a side effect so the
     * relationship is fully severed.
     *
     * <p>Returns 204 on success, 404 when target doesn't exist, 400
     * when the caller tries to block themselves. Idempotent: blocking
     * an already-blocked user is silent success.</p>
     */
    @PostMapping("/{idOrEmail}/block")
    public ResponseEntity<Void> block(@PathVariable String idOrEmail) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        Optional<String> targetEmail = followService.resolveEmail(idOrEmail);
        if (targetEmail.isEmpty()) return ResponseEntity.notFound().build();
        blockService.block(viewer, targetEmail.get());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{idOrEmail}/block")
    public ResponseEntity<Void> unblock(@PathVariable String idOrEmail) {
        String viewer = AuthUtils.requireAuthenticatedEmail();
        Optional<String> targetEmail = followService.resolveEmail(idOrEmail);
        if (targetEmail.isEmpty()) return ResponseEntity.notFound().build();
        blockService.unblock(viewer, targetEmail.get());
        return ResponseEntity.noContent().build();
    }

    /**
     * Batch profile lookup. Body: {@code { "emails": ["a@x", "b@y", ...] }}.
     * Returns one {@link ProfileSummaryDto} per known email; unknown/blank
     * emails are omitted. Replaces per-email fan-out on group rosters.
     */
    @PostMapping("/profiles/batch")
    public ResponseEntity<List<ProfileSummaryDto>> getProfilesBatch(@RequestBody BatchProfilesRequest request) {
        AuthUtils.requireAuthenticatedEmail();
        List<String> emails = request == null ? List.of() : request.emails();
        return ResponseEntity.ok(userInfoService.getProfileSummariesByEmails(emails));
    }

    public record BatchProfilesRequest(List<String> emails) {}

    /**
     * Presence-location ping. Frontend's {@code useTrackPresence} hook
     * captures the user's current geolocation (when permission is granted)
     * and sends it here, throttled client-side. We update three fields on
     * the verified caller's UserInfo:
     *   {@code lastKnownLat, lastKnownLng, lastKnownLocationAt}
     *
     * <p>Distinct from {@code latitude}/{@code longitude} which back the
     * user's home address. The presence dot on the household Family tab
     * (home / nearby / out) reads from these.</p>
     */
    @PatchMapping("/me/location")
    public ResponseEntity<Void> updateMyLocation(@RequestBody UpdateLocationRequest body) {
        String email = AuthUtils.requireAuthenticatedEmail();
        if (body == null || body.lat() == null || body.lng() == null) {
            return ResponseEntity.badRequest().build();
        }
        userInfoService.updateLastKnownLocationByEmail(email, body.lat(), body.lng());
        return ResponseEntity.noContent().build();
    }

    public record UpdateLocationRequest(Double lat, Double lng) {}

    /**
     * Mark the Readiness Assessment quiz as just-completed. Sets
     * {@code UserInfo.lastAssessmentAt} to "now" on the verified caller's
     * record. The frontend posts this when the quiz at /sitprep-quiz
     * finishes; the value drives the quarterly nudge banner on /home
     * (per docs/ECOSYSTEM_INTEGRATION.md step 6). No body, no parameters
     * — the verified token is the only input. Idempotent: posting twice
     * just refreshes the timestamp to the latest call.
     */
    @PostMapping("/me/assessment")
    public ResponseEntity<Void> markAssessmentComplete() {
        String email = AuthUtils.requireAuthenticatedEmail();
        userInfoService.markAssessmentCompleteByEmail(email);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hard-deletes the verified caller's account and associated personal
     * data. Apple App Store compliance (since 2022) — apps that support
     * account creation must offer in-app deletion.
     *
     * <p>Returns:
     * <ul>
     *   <li>200 + DeletionResult on success.</li>
     *   <li>409 + {@link OwnedGroupsBlocking} body when the user owns a
     *       multi-member household OR any non-household group. The body
     *       lists the blocking groups so the FE can render
     *       "transfer ownership of X first."</li>
     * </ul>
     * The frontend should sign the user out + redirect to /welcome on a
     * 200 response.
     */
    @DeleteMapping("/me/account")
    public ResponseEntity<?> deleteMyAccount() {
        String email = AuthUtils.requireAuthenticatedEmail();
        try {
            return ResponseEntity.ok(accountDeletionService.deleteAccount(email));
        } catch (OwnedGroupsBlockingException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new OwnedGroupsBlocking(ex.blocked()));
        }
    }

    public record OwnedGroupsBlocking(
            List<AccountDeletionService.BlockingGroup> blockedGroups
    ) {}

    /**
     * Per-group location sharing preferences. Body is a partial map of
     * {@code groupId} → mode ({@code always} | {@code check-in-only} |
     * {@code never}). The request merges into the user's existing map —
     * absent keys are left untouched. Pass mode {@code null} to clear an
     * entry (revert to the group's default).
     */
    @PatchMapping("/me/group-location-sharing")
    public ResponseEntity<Map<String, String>> updateGroupLocationSharing(
            @RequestBody Map<String, String> body) {
        String email = AuthUtils.requireAuthenticatedEmail();
        Map<String, String> merged = userInfoService.mergeGroupLocationSharingByEmail(email, body);
        return ResponseEntity.ok(merged);
    }

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
