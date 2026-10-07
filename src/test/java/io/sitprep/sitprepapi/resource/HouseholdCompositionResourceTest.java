package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdCompositionService;
import io.sitprep.sitprepapi.service.HouseholdCompositionService.CountsBelowNamedException;
import io.sitprep.sitprepapi.service.HouseholdCompositionService.CountsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Composition is member-only; counts writes are admin-only; a floor violation is a structured 409. */
class HouseholdCompositionResourceTest {

    private static final String HH = "hh-1";
    private HouseholdCompositionService service;
    private HouseholdAccessService access;
    private HouseholdCompositionResource resource;

    @BeforeEach
    void setUp() {
        service = mock(HouseholdCompositionService.class);
        access = mock(HouseholdAccessService.class);
        resource = new HouseholdCompositionResource(service, access);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access)
                .requireCanReadHousehold(eq("outsider@x.com"), eq(HH));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access)
                .requireCanAdminHousehold(eq("member@x.com"), eq(HH));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aNonMemberCannotReadTheComposition_andTheServiceIsNeverReached() {
        as("outsider@x.com");
        assertThatThrownBy(() -> resource.get(HH)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(service, never()).compose(anyString(), anyString());
    }

    @Test
    void anonymousIs401() {
        assertThatThrownBy(() -> resource.get(HH)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void aMemberWhoIsNotAnAdminCannotWriteCounts() {
        as("member@x.com");
        assertThatThrownBy(() -> resource.setCounts(HH, new CountsRequest(1, null, null, null, null, null, null),
                new MockHttpServletRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(service, never()).setCounts(anyString(), any(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void belowNamedIsA409ThatNamesTheBandAndItsFloor() {
        as("admin@x.com");
        when(service.setCounts(eq(HH), any(), eq("admin@x.com")))
                .thenThrow(new CountsBelowNamedException("ADULT", 3, 1));
        ResponseEntity<?> res = resource.setCounts(HH, new CountsRequest(1, null, null, null, null, null, null),
                new MockHttpServletRequest("PUT", "/api/households/hh-1/composition/counts"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("band", "ADULT").containsEntry("minimum", 3).containsEntry("requested", 1);
        assertThat((String) body.get("message")).contains("3 named adults");
        assertThat(body.get("error").toString()).contains("BELOW_NAMED");
    }

    private static void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
