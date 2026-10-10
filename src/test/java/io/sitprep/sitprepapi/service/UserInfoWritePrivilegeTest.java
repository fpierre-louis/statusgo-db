package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A user cannot grant themselves privileges through their own profile record
 * (docs/epics/userinfo-write-privilege/EXEC.md). Reproduced on prod 2026-09-27:
 * a plain account PATCHed its own {@code verifiedPublisherEmergencyPostingEnabled}
 * — the gate on posting a pinned emergency alert — and got 200.
 */
class UserInfoWritePrivilegeTest {

    private UserInfoRepo repo;
    private UserInfoService service;
    private UserInfo stored;

    @BeforeEach
    void setUp() {
        repo = mock(UserInfoRepo.class);
        service = new UserInfoService(repo, mock(HouseholdEventService.class), mock(GroupRepo.class),
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), mock(WebSocketMessageSender.class),
                mock(LocationPresenceService.class), mock(HouseholdProvisioningService.class));
        stored = new UserInfo();
        stored.setId("u-1");
        stored.setUserEmail("a@x.com");
        stored.setFirebaseUid("uid-a");
        stored.setUserStatus("SAFE");
        stored.setSubscription("Basic");
        stored.setBaseHouseholdId("hh-mine");
        when(repo.findById("u-1")).thenReturn(Optional.of(stored));
        when(repo.findByUserEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(stored));
        when(repo.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ── PATCH: privileged fields are refused, and nothing is written ───────

    @ParameterizedTest
    @ValueSource(strings = {
            "verifiedPublisher", "verifiedPublisherEmergencyPostingEnabled", "verifiedPublisherKind",
            "verifiedSince", "verifiedBy", "verifiedPublisherGroupId",
            "subscription", "subscriptionPackage", "dateSubscribed",
            "subscriptionOverridePackage", "subscriptionOverrideExpiresAt",
            "firebaseUid", "baseHouseholdId", "searchable", "groupLocationSharing",
            "lastKnownLat", "lastKnownLng", "userStatusLastUpdated", "statusSetByEmail",
            "lastActiveAt", "guestAccount", "version", "activeGroupAlertCounts"})
    void aPrivilegedOrServerOwnedFieldIsRefused(String field) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(field, "x");
        assertForbidden(() -> service.patchUserById("u-1", updates));
    }

    @Test
    void theReproducedEscalationIsRefusedAndLeavesTheFlagsFalse() {
        assertForbidden(() -> service.patchUserById("u-1", Map.of(
                "verifiedPublisherEmergencyPostingEnabled", true,
                "verifiedPublisher", true)));
        assertThat(stored.isVerifiedPublisherEmergencyPostingEnabled()).isFalse();
        assertThat(stored.isVerifiedPublisher()).isFalse();
    }

    @Test
    void aRefusedKeyStopsTheWholePatchNotJustItself() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("bio", "hello");
        updates.put("subscription", "Premium");
        assertForbidden(() -> service.patchUserById("u-1", updates));
        assertThat(stored.getBio()).isNull();
        assertThat(stored.getSubscription()).isEqualTo("Basic");
    }

    @Test
    void aRefusedKeyIsRefusedEvenWhenItsValueIsNull() {
        Map<String, Object> updates = new HashMap<>();
        updates.put("baseHouseholdId", null);
        assertForbidden(() -> service.patchUserById("u-1", updates));
        assertThat(stored.getBaseHouseholdId()).isEqualTo("hh-mine");
    }

    // ── PATCH: every shape the frontend sends still works ──────────────────

    @Test
    void theProfileEditorsDiffStillSaves_includingKeysThatAreNotFields() {
        Map<String, Object> diff = new HashMap<>();
        diff.put("userFirstName", "Ann");
        diff.put("bio", "Neighbor on 4th");
        diff.put("profileVisibility", "public");
        diff.put("address", "1 Main St");
        diff.put("userEmail", "other@x.com"); // ignored, as it always was
        diff.put("latitude", "40.1");          // not a UserInfo field — ignored
        diff.put("longitude", "-111.8");
        diff.put("dateOfBirth", "");
        UserInfo saved = service.patchUserById("u-1", diff);
        assertThat(saved.getUserFirstName()).isEqualTo("Ann");
        assertThat(saved.getBio()).isEqualTo("Neighbor on 4th");
        assertThat(saved.getProfileVisibility()).isEqualTo("public");
        assertThat(saved.getUserEmail()).isEqualTo("a@x.com");
    }

    @Test
    void onboardingTimestampsZipAndPushTokenStillSave() {
        String at = "2026-09-27T12:00:00Z";
        service.patchUserById("u-1", Map.of("onboardingTermsAcceptedAt", at));
        service.patchUserById("u-1", Map.of("onboardingLocationEnabledAt", at));
        service.patchUserById("u-1", Map.of("onboardingNotificationsEnabledAt", at));
        service.patchUserById("u-1", Map.of("onboardingCompletedAt", at));
        service.patchUserById("u-1", Map.of("lastKnownZip", "84043"));
        service.patchUserById("u-1", Map.of("fcmtoken", "tok-1"));
        assertThat(stored.getOnboardingCompletedAt()).isEqualTo(Instant.parse(at));
        assertThat(stored.getOnboardingTermsAcceptedAt()).isEqualTo(Instant.parse(at));
        assertThat(stored.getLastKnownZip()).isEqualTo("84043");
        assertThat(stored.getFcmtoken()).isEqualTo("tok-1");
    }

    @Test
    void theMembershipCacheWritesFromMembersJsAreIgnoredNotRefused() {
        // Members.js sends these; they never landed (Set field, List value, the
        // reflective exception swallowed). Still a 200, still no write.
        stored.setJoinedGroupIDs(new java.util.HashSet<>(Set.of("g-server")));
        service.patchUserById("u-1", Map.of("joinedGroupIDs", List.of("g-1", "g-2")));
        service.patchUserById("u-1", Map.of("managedGroupIDs", List.of("g-1")));
        assertThat(stored.getJoinedGroupIDs()).containsExactly("g-server");
        assertThat(stored.getManagedGroupIDs()).isNull();
    }

    @Test
    void aStatusOnTheProfilePatchIsIgnored_notWritten_notRefused() {
        // Q19(a), 2026-10-09: status is written only by PATCH /me/status and
        // the proxy POST. The editor's diff used to echo it; an installed
        // build must keep its 200 for the name edit riding with it.
        UserInfo out = service.patchUserById("u-1",
                Map.of("userFirstName", "Ana", "userStatus", "HELP", "statusColor", "Red"));
        assertThat(out.getUserFirstName()).isEqualTo("Ana");
        assertThat(out.getUserStatus()).isEqualTo("SAFE");
        assertThat(out.getStatusColor()).isNull();
    }

    // ── PUT: the echoed record cannot carry a plan or a uid ────────────────

    @Test
    void putIgnoresSubscriptionAndABodyUid() {
        UserInfo body = new UserInfo();
        body.setUserFirstName("Ann");
        body.setUserStatus("SAFE");
        body.setSubscription("Premium");
        body.setSubscriptionPackage("Yearly");
        body.setDateSubscribed(Instant.parse("2020-01-01T00:00:00Z"));
        body.setFirebaseUid("uid-someone-else");
        body.setJoinedGroupIDs(Set.of("g-1"));
        UserInfo saved = service.updateUserById("u-1", body);
        assertThat(saved.getUserFirstName()).isEqualTo("Ann");
        assertThat(saved.getJoinedGroupIDs()).containsExactly("g-1"); // LeaveGroup's write still lands
        assertThat(saved.getSubscription()).isEqualTo("Basic");
        assertThat(saved.getSubscriptionPackage()).isNull();
        assertThat(saved.getDateSubscribed()).isNull();
        assertThat(saved.getFirebaseUid()).isEqualTo("uid-a");
    }

    // ── creation: the server picks the plan ────────────────────────────────

    @Test
    void aNewAccountCannotChooseItsOwnPlan() {
        when(repo.findByUserEmailIgnoreCase("new@x.com")).thenReturn(Optional.empty());
        UserInfo body = new UserInfo();
        body.setSubscription("Premium");
        body.setSubscriptionPackage("Lifetime");
        body.setDateSubscribed(Instant.parse("2020-01-01T00:00:00Z"));
        UserInfo created = service.upsertByEmail("new@x.com", body);
        assertThat(created.getSubscription()).isEqualTo("Basic");
        assertThat(created.getSubscriptionPackage()).isEqualTo("Monthly");
        assertThat(created.getDateSubscribed()).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
