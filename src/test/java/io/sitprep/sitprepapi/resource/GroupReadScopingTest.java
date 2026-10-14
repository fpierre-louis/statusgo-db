package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GroupReadinessSummaryDto;
import io.sitprep.sitprepapi.service.GroupReadinessService;
import io.sitprep.sitprepapi.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GET /api/groups/{groupId} returned the raw Group entity to any signed-in
 * caller. <b>Households are Group rows</b> — so for a household id that was the
 * family roster plus the home address and its coordinate, handed to anyone with
 * an account, along with the code used to join.
 *
 * <p>The response is scoped rather than refused, so the member-facing frontend
 * call sites keep working. For a household a non-member reads only its id, its
 * type and their own {@code viewer} block: a household has no public preview
 * ({@code /preview} answers 404, JN-04 / D4), so there is nothing else a
 * stranger may learn (B4 review, 2026-10-14).</p>
 *
 * <p>A pending request is NOT a relationship (B2 / JN-02, 2026-10-14): anyone
 * can self-join any id and land in pending. The requester gets the stranger
 * projection plus their own pending row and {@code viewerStatus=PENDING}, so
 * the roster CTA still reads "Request sent" without the roster.</p>
 */
class GroupReadScopingTest {

    private static final String GROUP_ID = "grp-1";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String PENDING = "pending@x.com";
    private static final String STRANGER = "stranger@x.com";

    private GroupService groupService;
    private GroupResource resource;
    private GroupReadinessResource readinessResource;
    private GroupReadinessService readinessService;

    @BeforeEach
    void setUp() {
        groupService = mock(GroupService.class);
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(household());

        resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", groupService);
        ReflectionTestUtils.setField(resource, "objectMapper", new ObjectMapper().findAndRegisterModules());

        readinessService = mock(GroupReadinessService.class);
        readinessResource = new GroupReadinessResource(readinessService, groupService);
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

    /** A household — the case where "a group row" is somebody's family and home. */
    private Group household() {
        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setGroupName("The Reyes household");
        g.setGroupType("Household");
        g.setGroupCode("REYES-77");
        g.setOwnerEmail(OWNER);
        g.setOwnerName("Ana Reyes");
        g.setAdminEmails(List.of(ADMIN));
        g.setMemberEmails(List.of(OWNER, ADMIN, MEMBER));
        g.setPendingMemberEmails(List.of(PENDING));
        g.setAddress("742 Evergreen Terrace");
        g.setLatitude(40.76);
        g.setLongitude(-111.89);
        g.setZipCode("84101");
        g.setStripeCustomerId("cus_123");
        return g;
    }

    private JsonNode read(String caller) {
        authenticateAs(caller);
        return resource.getGroupById(GROUP_ID);
    }

    @Test
    void strangerGetsNoRoster() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("memberEmails"));
        assertFalse(n.has("adminEmails"));
        assertFalse(n.has("pendingMemberEmails"));
        assertFalse(n.has("ownerEmail"));
    }

    @Test
    void strangerGetsNoHomeAddressOrCoordinate() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("address"));
        assertFalse(n.has("latitude"));
        assertFalse(n.has("longitude"));
        assertFalse(n.has("zipCode"));
    }

    @Test
    void strangerGetsNoJoinCodeOrBillingIdentity() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("groupCode"));
        assertFalse(n.has("stripeCustomerId"));
        // The resolved tier names an active override tier (B1 review).
        assertFalse(n.has("effectivePlanTier"));
        assertFalse(n.has("planUnlocks"));
    }

    @Test
    void aStrangerToAHouseholdLearnsOnlyItsIdAndType() {
        // This test used to pin the family name and the owner's name as "what
        // the public preview would show". A household has NO preview
        // (/preview answers 404 since B2, JN-04 / D4), so the stranger level
        // for a household is nothing (B4 review, 2026-10-14). An
        // organization's stranger still gets the preview: GroupPageDtoScopeTest.
        JsonNode n = read(STRANGER);
        assertEquals(GROUP_ID, n.get("groupId").asText());
        assertEquals("Household", n.get("groupType").asText());
        for (String f : List.of("groupName", "ownerName", "description", "memberCount",
                "createdAt", "updatedAt", "privacy", "jurisdictionReady", "viewerAgencyPermissions")) {
            assertFalse(n.has(f), "a household stranger still sees " + f);
        }
    }

    @Test
    void ownerAdminAndMemberAllStillSeeTheRoster() {
        for (String caller : List.of(OWNER, ADMIN, MEMBER)) {
            SecurityContextHolder.clearContext();
            JsonNode n = read(caller);
            assertTrue(n.has("memberEmails"), caller + " lost the roster");
            assertTrue(n.has("address"), caller + " lost the address");
            assertTrue(n.has("effectivePlanTier"), caller + " lost the plan tier");
        }
    }

    // ---- a pending requester (B2 / JN-02, D3) ----
    //
    // This test used to pin the leak as intended: pending counted as a
    // relationship, and the fixture is a household, so anyone who self-joined
    // (and landed in pending) read the family roster, the home address, the
    // join code and the Stripe id. A requester now gets the stranger
    // projection plus their own pending state.

    @Test
    void aPendingRequesterGetsNoRosterAddressCodeOrBilling() {
        JsonNode n = read(PENDING);
        for (String f : List.of("memberEmails", "adminEmails", "ownerEmail", "address",
                "latitude", "longitude", "zipCode", "groupCode", "stripeCustomerId",
                "effectivePlanTier", "lastUpdatedBy", "alert")) {
            assertFalse(n.has(f), "pending caller still sees " + f);
        }
    }

    @Test
    void aPendingRequesterSeesOnlyTheirOwnPendingRow() {
        JsonNode n = read(PENDING);
        assertEquals("PENDING", n.get("viewerStatus").asText());
        assertEquals(1, n.get("pendingMemberEmails").size());
        assertEquals(PENDING, n.get("pendingMemberEmails").get(0).asText());
        // A household has no public face, so not even its name (B4 review).
        assertFalse(n.has("groupName"));
        assertFalse(n.has("ownerName"));
    }

    @Test
    void aStrangerHasNoViewerStatusAndNoPendingRows() {
        JsonNode n = read(STRANGER);
        assertFalse(n.has("viewerStatus"));
        assertFalse(n.has("pendingMemberEmails"));
    }

    @Test
    void strangerStripCoversTheMeasuredLeaks() {
        // BE-05: measured on prod (AUDIT §16.2) — a non-member read kept
        // lastUpdatedBy (an admin's email), the subgroup graph, the outreach
        // and reminder counters; staff override fields carry an email + note.
        Group g = household();
        g.setLastUpdatedBy(ADMIN);
        g.setSubGroupIDs(List.of("sub-1"));
        g.setParentGroupIDs(List.of("parent-1"));
        g.setCheckInRemindersFired(2);
        g.setAlert("Active");
        g.setAlertActivatedAt(java.time.Instant.parse("2026-10-13T12:00:00Z"));
        g.setSubscriptionOverrideBy("staff@sitprep.app");
        g.setSubscriptionOverrideReason("pilot");
        g.setVersion(7L);
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(g);

        JsonNode n = read(STRANGER);
        for (String f : List.of("lastUpdatedBy", "subGroupIDs", "parentGroupIDs",
                "checkInRemindersFired", "alert", "alertActivatedAt", "subscriptionOverrideBy",
                "subscriptionOverrideReason", "version", "ghostDemandSignal", "outreachOptOut",
                "outreachCount")) {
            assertFalse(n.has(f), "stranger still sees " + f);
        }
        assertFalse(n.toString().contains("@"), "an email survived: " + n);

        SecurityContextHolder.clearContext();
        JsonNode member = read(MEMBER);
        assertEquals(ADMIN, member.get("lastUpdatedBy").asText());
        assertEquals("Active", member.get("alert").asText());
    }

    // ---- readiness summary: a directory of which homes need help ----

    @Test
    void nonAdminCannotReadTheReadinessSummary() {
        authenticateAs(MEMBER);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> readinessResource.getReadinessSummary(GROUP_ID));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(readinessService, never()).buildReadinessSummary(anyString());
    }

    @Test
    void strangerCannotReadTheReadinessSummary() {
        authenticateAs(STRANGER);
        assertThrows(ResponseStatusException.class,
                () -> readinessResource.getReadinessSummary(GROUP_ID));
        verify(readinessService, never()).buildReadinessSummary(anyString());
    }

    // ---- an unknown id must not answer "500" where it means "no such group" ----

    @Test
    void previewOfAnUnknownIdIs404NotAnExceptionPage() {
        // GroupService signals a missing group with a bare RuntimeException.
        // /preview used to let it escape as a 500 — on a PUBLIC route that
        // invite recipients and link crawlers hit, which made the response
        // answer the very question the 404 exists to refuse: 500 for one id,
        // 200 for another, is a "does this id exist" oracle.
        when(groupService.getGroupPreview(eq("nope"), any()))
                .thenThrow(new RuntimeException("Group not found: nope"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> resource.getGroupPreview("nope"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void previewOfAKnownIdStillWorksAnonymously() {
        // The conversion must not swallow the happy path — this route serves
        // people who have not signed in yet.
        // A real record, not a mock — GroupPreviewDto is final, and a stub would
        // not have told us anything the constructor does not.
        io.sitprep.sitprepapi.dto.GroupPreviewDto dto =
                new io.sitprep.sitprepapi.dto.GroupPreviewDto(
                        GROUP_ID, "The Reyes household", "Household", "desc", "Private",
                        "Ana Reyes", 1, 3, null, null, null, false,
                        io.sitprep.sitprepapi.dto.GroupPreviewDto.STATUS_NONE);
        when(groupService.getGroupPreview(eq(GROUP_ID), any())).thenReturn(dto);

        assertEquals(HttpStatus.OK, resource.getGroupPreview(GROUP_ID).getStatusCode());
    }

    @Test
    void aConsideredStatusFromDeeperInTheStackIsNotLaunderedIntoA404() {
        // Without the ResponseStatusException passthrough, a deliberate 403
        // would come back as "no such group" — a gate's answer rewritten into a
        // claim about existence.
        when(groupService.getGroupPreview(eq("forbidden"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "nope"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> resource.getGroupPreview("forbidden"));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void theSiblingRouteStillConvertsTheSameWay() {
        // lookup() and /preview now share one conversion; this pins that the
        // extraction did not change the behaviour it was extracted from.
        authenticateAs(ADMIN);
        when(groupService.getGroupByPublicId("nope"))
                .thenThrow(new RuntimeException("Group not found: nope"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> readinessResource.getReadinessSummary("nope"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void adminCanStillReadTheReadinessSummary() {
        authenticateAs(ADMIN);
        when(readinessService.buildReadinessSummary(GROUP_ID)).thenReturn(mock(GroupReadinessSummaryDto.class));
        assertEquals(HttpStatus.OK, readinessResource.getReadinessSummary(GROUP_ID).getStatusCode());
        verify(readinessService).buildReadinessSummary(GROUP_ID);
    }
}
