package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiResponse;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.service.AckRateLimiter;
import io.sitprep.sitprepapi.service.PlanActivationService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the emergency-map endpoints' auth boundary + envelope
 * (docs/map/MAP_API_CONTRACT.md). No Spring context — the resource is invoked
 * directly with a stubbed SecurityContext.
 */
class PlanActivationMapResourceTest {

    private PlanActivationService service;
    private PlanActivationResource resource;

    private static final MapPoiDto POINT = new MapPoiDto(
            "activation:shelter:2", "shelter", "proprietary:activation", "Red Cross",
            40.2, -111.2, null, null, null, null, null, null, null, null, "5 Safe Ave",
            "shelter-primary", null, null, null, null);

    @BeforeEach
    void setUp() {
        service = mock(PlanActivationService.class);
        resource = new PlanActivationResource(service, mock(AckRateLimiter.class));
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

    @Test
    void ownerMap_unauthenticated_returns401_serviceNeverCalled() {
        SecurityContextHolder.clearContext();
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> resource.ownerMap("act-1"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(service, never()).getActivationMap(any(), any());
    }

    @Test
    void ownerMap_authenticated_returnsEnvelope_withCallerEmail() {
        authenticateAs("owner@x.com");
        when(service.getActivationMap("act-1", "owner@x.com")).thenReturn(List.of(POINT));

        ResponseEntity<ApiResponse<List<MapPoiDto>>> resp = resource.ownerMap("act-1");

        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().data().size());
        assertNull(resp.getBody().error());
        assertNotNull(resp.getBody().meta());
        verify(service).getActivationMap(eq("act-1"), eq("owner@x.com"));
    }

    @Test
    void recipientMap_isPublic_returnsEnvelope_noAuthRequired() {
        SecurityContextHolder.clearContext(); // guest / no token
        when(service.getRecipientMap("act-1")).thenReturn(List.of(POINT));

        ResponseEntity<ApiResponse<List<MapPoiDto>>> resp = resource.recipientMap("act-1");

        assertTrue(resp.getStatusCode().is2xxSuccessful());
        assertNotNull(resp.getBody());
        assertEquals(1, resp.getBody().data().size());
        assertNotNull(resp.getBody().meta());
    }
}
