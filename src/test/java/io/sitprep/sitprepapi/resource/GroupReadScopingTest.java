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
 * <p>The response is scoped rather than refused, because the non-member answer
 * already exists and is public: {@code /{id}/preview} serves this same reduced
 * view to invitees who have not signed in. So a stranger now learns nothing here
 * they could not already read there — while roughly twenty member-facing
 * frontend call sites keep working.</p>
 *
 * <p>The pending-member test is the one worth keeping. GroupRole.fromGroup
 * resolves a pending request to NONE, so a predicate built on it alone would
 * strip the pending list from the one person it is about, and the roster CTA
 * would invite them to join a group they have already asked to join.</p>
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
        ReflectionTestUtils.setField(resource, "objectMapper", new ObjectMapper());

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
    }

    @Test
    void strangerStillSeesWhatThePublicPreviewWouldShow() {
        // Scoping this route down to /preview's level is the whole argument for
        // stripping instead of refusing — so it must actually reach that level.
        JsonNode n = read(STRANGER);
        assertEquals(GROUP_ID, n.get("groupId").asText());
        assertEquals("The Reyes household", n.get("groupName").asText());
        assertEquals("Ana Reyes", n.get("ownerName").asText());
    }

    @Test
    void ownerAdminAndMemberAllStillSeeTheRoster() {
        for (String caller : List.of(OWNER, ADMIN, MEMBER)) {
            SecurityContextHolder.clearContext();
            JsonNode n = read(caller);
            assertTrue(n.has("memberEmails"), caller + " lost the roster");
            assertTrue(n.has("address"), caller + " lost the address");
        }
    }

    @Test
    void pendingMemberStillSeesTheirOwnPendingState() {
        // GroupRole.fromGroup returns NONE for a pending request, so this only
        // passes because the predicate checks pendingMemberEmails separately.
        assertTrue(read(PENDING).has("pendingMemberEmails"));
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
                        "Ana Reyes", 1, 3, null, null, null, null, false,
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
