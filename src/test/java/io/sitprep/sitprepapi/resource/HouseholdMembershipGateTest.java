package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdAccompanimentService;
import io.sitprep.sitprepapi.service.HouseholdEventService;
import io.sitprep.sitprepapi.service.HouseholdManualMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A household id in the path is not permission to use it.
 *
 * <p>Until 2026-08-24 manual-members (all four verbs), accompaniments (all four)
 * and the household events GET checked only that the caller was signed in. Pass
 * someone else's household id and you could read their children by name and age,
 * delete them, insert yourself as the supervising adult for one of them, release
 * a real guardian's claim, or read the family's whole activity timeline. Ids are
 * not secret — other endpoints hand them out.</p>
 *
 * <p>Each test below asserts two things together, and the second is the one that
 * matters: the request is refused <em>and</em> the service is never reached. A
 * gate that throws after the work has happened is not a gate.</p>
 *
 * <p>No Spring context; the resources are invoked directly with a stubbed
 * SecurityContext and a real HouseholdAccessService stub, matching
 * PostAssignAuthorizationTest.</p>
 */
class HouseholdMembershipGateTest {

    private static final String HOUSEHOLD = "hh-42";
    private static final String MEMBER = "member@x.com";
    private static final String OUTSIDER = "outsider@x.com";

    private HouseholdAccessService access;
    private HouseholdManualMemberService manualMembers;
    private HouseholdAccompanimentService accompaniments;
    private HouseholdEventService events;

    private HouseholdManualMemberResource manualMemberResource;
    private HouseholdAccompanimentResource accompanimentResource;
    private HouseholdEventResource eventResource;

    @BeforeEach
    void setUp() {
        access = mock(HouseholdAccessService.class);
        manualMembers = mock(HouseholdManualMemberService.class);
        accompaniments = mock(HouseholdAccompanimentService.class);
        events = mock(HouseholdEventService.class);

        manualMemberResource = new HouseholdManualMemberResource(manualMembers, access);
        accompanimentResource = new HouseholdAccompanimentResource(accompaniments, access);
        eventResource = new HouseholdEventResource(events, access);

        // Real behaviour of requireCanReadHousehold: 403 for a non-member.
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This household's plan is not shared with you"))
                .when(access).requireCanReadHousehold(eq(OUTSIDER), eq(HOUSEHOLD));
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

    private void assertForbidden(Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    // ---- manual members: children's names and ages, readable AND deletable ----

    @Test
    void outsiderCannotListAnotherFamilysManualMembers() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> manualMemberResource.list(HOUSEHOLD));
        verify(manualMembers, never()).list(anyString());
    }

    @Test
    void outsiderCannotAddToAnotherFamilysRoster() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> manualMemberResource.add(HOUSEHOLD, null));
        verify(manualMembers, never()).add(anyString(), any());
    }

    @Test
    void outsiderCannotEditAnotherFamilysManualMember() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> manualMemberResource.update(HOUSEHOLD, "m-1", null));
        verify(manualMembers, never()).update(anyString(), anyString(), any());
    }

    @Test
    void outsiderCannotDeleteAnotherFamilysChild() {
        // The destructive one: removal cascades into accompaniment links.
        authenticateAs(OUTSIDER);
        assertForbidden(() -> manualMemberResource.remove(HOUSEHOLD, "m-1"));
        verify(manualMembers, never()).remove(anyString(), anyString());
    }

    @Test
    void memberCanStillUseTheirOwnHouseholdsRoster() {
        authenticateAs(MEMBER);
        when(manualMembers.list(HOUSEHOLD)).thenReturn(List.of());
        assertEquals(HttpStatus.OK, manualMemberResource.list(HOUSEHOLD).getStatusCode());
        verify(manualMembers).list(HOUSEHOLD);
    }

    // ---- accompaniments: who is with whom, during a live crisis ----

    @Test
    void outsiderCannotSeeWhoIsWithWhom() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> accompanimentResource.list(HOUSEHOLD));
        verify(accompaniments, never()).list(anyString());
    }

    @Test
    void outsiderCannotClaimToBeSupervisingAnotherFamilysChild() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> accompanimentResource.claim(HOUSEHOLD,
                new HouseholdAccompanimentResource.ClaimRequest(null, null, true)));
        verify(accompaniments, never()).claim(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void outsiderCannotConfirmOnAnotherFamilysBehalf() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> accompanimentResource.confirm(HOUSEHOLD,
                new HouseholdAccompanimentResource.ConfirmRequest("manual", "m-1")));
        verify(accompaniments, never()).confirm(anyString(), anyString(), anyString());
    }

    @Test
    void outsiderCannotReleaseARealGuardiansClaim() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> accompanimentResource.release(HOUSEHOLD, "manual", "m-1"));
        verify(accompaniments, never()).release(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void memberCanStillReadTheirOwnHouseholdsAccompaniments() {
        authenticateAs(MEMBER);
        when(accompaniments.list(HOUSEHOLD)).thenReturn(List.of());
        assertEquals(HttpStatus.OK, accompanimentResource.list(HOUSEHOLD).getStatusCode());
        verify(accompaniments).list(HOUSEHOLD);
    }

    // ---- events: the one ungated route in a file whose other two were gated ----

    @Test
    void outsiderCannotReadAnotherFamilysTimeline() {
        authenticateAs(OUTSIDER);
        assertForbidden(() -> eventResource.list(HOUSEHOLD, null, null));
        verify(events, never()).list(anyString(), any(), any());
    }

    @Test
    void memberCanStillReadTheirOwnHouseholdsTimeline() {
        authenticateAs(MEMBER);
        when(events.list(eq(HOUSEHOLD), any(), any())).thenReturn(List.of());
        assertEquals(HttpStatus.OK, eventResource.list(HOUSEHOLD, null, null).getStatusCode());
        verify(events).list(eq(HOUSEHOLD), any(), any());
    }
}
