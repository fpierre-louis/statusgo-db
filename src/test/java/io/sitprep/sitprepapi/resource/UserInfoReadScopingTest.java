package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.service.AccountDeletionService;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.BlockService;
import io.sitprep.sitprepapi.service.FollowService;
import io.sitprep.sitprepapi.service.PlatformAccessService;
import io.sitprep.sitprepapi.service.UserInfoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GET /api/userinfo/{id} · /email/{email} · /firebase/{uid} hand back the raw
 * UserInfo entity. Until 2026-08-24 they handed it to <em>anyone</em> signed in,
 * for <em>any</em> user — including the FCM token, live device location, home
 * address and home coordinate — while the bulk dump of the same data fifteen
 * lines up in the same file required VIEW_PII and wrote an audit record.
 *
 * <p>These tests pin the boundary in both directions. The subject still gets
 * their whole record (AuthContext provisioning and the FCM-token sync read
 * fields on this list and would silently start re-writing the token every boot
 * without them), and a stranger gets the record minus the private fields.</p>
 *
 * <p>The phone assertion is the one that looks like an oversight and is not.
 * MapView fetches a subgroup owner's profile specifically to show their phone
 * number during an emergency; stripping it server-side breaks that before any
 * frontend could stop asking. It is recorded here so the exception stays a
 * decision rather than becoming an accident.</p>
 */
class UserInfoReadScopingTest {

    private static final String SUBJECT = "subject@x.com";
    private static final String STRANGER = "stranger@x.com";

    private UserInfoService userInfoService;
    private UserInfoResource resource;

    @BeforeEach
    void setUp() {
        userInfoService = mock(UserInfoService.class);
        resource = new UserInfoResource(
                userInfoService,
                mock(AccountDeletionService.class),
                mock(FollowService.class),
                mock(BlockService.class),
                mock(PlatformAccessService.class),
                mock(AdminAuditLogService.class),
                new ObjectMapper());
        when(userInfoService.getUserByEmail(SUBJECT)).thenReturn(Optional.of(populated()));
        when(userInfoService.getUserById("u-1")).thenReturn(Optional.of(populated()));
        when(userInfoService.getUserByFirebaseUid("uid-1")).thenReturn(Optional.of(populated()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /** Every field the scoping cares about, populated so absence is meaningful. */
    private UserInfo populated() {
        UserInfo u = new UserInfo();
        u.setId("u-1");
        u.setFirebaseUid("uid-1");
        u.setUserEmail(SUBJECT);
        u.setUserFirstName("Sam");
        u.setPhone("+15551234567");
        u.setAddress("742 Evergreen Terrace");
        u.setLatitude(40.76);
        u.setLongitude(-111.89);
        u.setFcmtoken("fcm-secret-token");
        u.setLastKnownLat(40.70);
        u.setLastKnownLng(-111.80);
        u.setLastKnownZip("84101");
        u.setBaseHouseholdId("hh-42");
        u.setAssessmentSummaryJson("{\"score\":3}");
        u.setSubscriptionOverrideReason("comped by sales");
        return u;
    }

    private JsonNode read(String callerEmail) {
        authenticateAs(callerEmail);
        ResponseEntity<JsonNode> res = resource.getUserByEmail(SUBJECT);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        return res.getBody();
    }

    @Test
    void strangerGetsNoFcmToken() {
        assertFalse(read(STRANGER).has("fcmtoken"));
    }

    @Test
    void strangerGetsNoLiveDeviceLocation() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("lastKnownLat"));
        assertFalse(n.has("lastKnownLng"));
        assertFalse(n.has("lastKnownLocationAt"));
        assertFalse(n.has("lastKnownZip"));
    }

    @Test
    void strangerGetsNoHomeAddressOrHomeCoordinate() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("address"));
        // latitude/longitude are delegating accessors over the embedded
        // homeLocation, not fields — easy to miss when writing the denylist.
        assertFalse(n.has("latitude"));
        assertFalse(n.has("longitude"));
    }

    @Test
    void strangerGetsNoHouseholdIdToEnumerateWith() {
        assertFalse(read(STRANGER).has("baseHouseholdId"));
    }

    @Test
    void strangerGetsNoPrivateAssessmentOrBillingOverrides() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("assessmentSummaryJson"));
        assertFalse(n.has("subscriptionOverrideReason"));
    }

    @Test
    void strangerStillGetsTheIdentityFieldsRostersRenderFrom() {
        // patchMemberStatus resolves a member's id through this route, and the
        // roster renders name + email from it. Scoping must not break either.
        JsonNode n = read(STRANGER);
        assertEquals("u-1", n.get("id").asText());
        assertEquals(SUBJECT, n.get("userEmail").asText());
        assertEquals("Sam", n.get("userFirstName").asText());
    }

    @Test
    void phoneIsDeliberatelyStillVisibleCrossUser() {
        // MapView shows a subgroup owner's phone during an emergency. Removing
        // this needs a coordinated frontend change, not a unilateral strip.
        assertEquals("+15551234567", read(STRANGER).get("phone").asText());
    }

    @Test
    void subjectStillSeesTheirWholeRecord() {
        JsonNode n = read(SUBJECT);
        assertEquals("fcm-secret-token", n.get("fcmtoken").asText());
        assertEquals("742 Evergreen Terrace", n.get("address").asText());
        assertEquals("hh-42", n.get("baseHouseholdId").asText());
        assertEquals(40.70, n.get("lastKnownLat").asDouble(), 1e-9);
    }

    @Test
    void emailCaseDoesNotDecideWhoIsTheSubject() {
        authenticateAs("SUBJECT@X.com");
        assertTrue(resource.getUserByEmail(SUBJECT).getBody().has("fcmtoken"));
    }

    @Test
    void theOtherTwoLookupsScopeIdentically() {
        authenticateAs(STRANGER);
        assertFalse(resource.getUserById("u-1").getBody().has("fcmtoken"));
        assertFalse(resource.getUserByFirebaseUid("uid-1").getBody().has("fcmtoken"));
    }

    @Test
    void batchLookupIsBoundedSoItCannotBeUsedToEnumerateAccounts() {
        // Unknown emails are omitted from the response, which makes an unbounded
        // list an oracle: post fifty thousand addresses, and whatever comes back
        // is a SitPrep account with a name and an avatar attached.
        authenticateAs(STRANGER);
        List<String> tooMany = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> "u" + i + "@x.com").toList();

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> resource.getProfilesBatch(new UserInfoResource.BatchProfilesRequest(tooMany)));
        // 400, not a silent truncation — a half-resolved roster looks complete.
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(userInfoService, never()).getProfileSummariesByEmails(any());
    }

    @Test
    void aNormalSizedRosterStillResolves() {
        authenticateAs(STRANGER);
        List<String> roster = List.of("a@x.com", "b@x.com");
        when(userInfoService.getProfileSummariesByEmails(roster)).thenReturn(List.of());
        assertEquals(HttpStatus.OK,
                resource.getProfilesBatch(new UserInfoResource.BatchProfilesRequest(roster)).getStatusCode());
        verify(userInfoService).getProfileSummariesByEmails(roster);
    }

    @Test
    void missingUserIsStill404() {
        authenticateAs(STRANGER);
        when(userInfoService.getUserByEmail("nobody@x.com")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                resource.getUserByEmail("nobody@x.com").getStatusCode());
    }
}
