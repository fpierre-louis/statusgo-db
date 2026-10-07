package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.service.HouseholdClaimService;
import io.sitprep.sitprepapi.service.HouseholdClaimService.AcceptResult;
import io.sitprep.sitprepapi.service.HouseholdClaimService.ClaimInvite;
import io.sitprep.sitprepapi.service.HouseholdClaimService.ClaimStateException;
import io.sitprep.sitprepapi.service.HouseholdClaimService.Preview;
import io.sitprep.sitprepapi.service.HouseholdClaimService.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Wire shapes and status codes of the claim routes. */
class HouseholdClaimResourceTest {

    private HouseholdClaimService service;
    private HouseholdClaimResource resource;

    @BeforeEach
    void setUp() {
        service = mock(HouseholdClaimService.class);
        resource = new HouseholdClaimResource(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mintIs201ForANewLinkAnd200ForTheReusedOne() {
        as("admin@x.com");
        Instant now = Instant.now();
        when(service.mint("hh", "m1", "admin@x.com"))
                .thenReturn(new ClaimInvite("tok", "/claim/tok", "hh", "m1", now, now.plusSeconds(60), false))
                .thenReturn(new ClaimInvite("tok", "/claim/tok", "hh", "m1", now, now.plusSeconds(60), true));
        assertThat(resource.mint("hh", "m1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resource.mint("hh", "m1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void mintAndAcceptNeedAnAccount_resolveDoesNot() {
        assertThatThrownBy(() -> resource.mint("hh", "m1")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> resource.accept("tok")).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(service, never()).accept(anyString(), anyString());

        when(service.resolve("tok")).thenReturn(new Preview(State.OK, "The Lees", "Maya", HouseholdBand.TEEN, "Dione", Instant.now()));
        ResponseEntity<Map<String, Object>> res = resource.resolve("tok");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("state", "OK").containsEntry("memberName", "Maya")
                .containsEntry("band", "TEEN").containsEntry("inviterFirstName", "Dione")
                .containsEntry("householdName", "The Lees").containsKey("expiresAt");
    }

    @Test
    void deadLinksResolveAs404Or410WithOnlyTheirState() {
        when(service.resolve("a")).thenReturn(new Preview(State.NOT_FOUND, null, null, null, null, null));
        when(service.resolve("b")).thenReturn(new Preview(State.CONSUMED, null, null, null, null, null));
        assertThat(resource.resolve("a").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map<String, Object>> gone = resource.resolve("b");
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(gone.getBody()).containsOnlyKeys("kind", "state").containsEntry("state", "CONSUMED");
    }

    @Test
    void acceptReturnsClaimed_orTheDeadStateWith410() {
        as("maya@x.com");
        when(service.accept("ok", "maya@x.com"))
                .thenReturn(new AcceptResult(State.OK, false, "hh", HouseholdBand.TEEN, "Maya", "hh", true));
        when(service.accept("old", "maya@x.com")).thenThrow(new ClaimStateException(State.EXPIRED));

        ResponseEntity<Map<String, Object>> ok = resource.accept("ok");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("state", "CLAIMED").containsEntry("householdId", "hh")
                .containsEntry("band", "TEEN").containsEntry("baseChanged", true).containsEntry("alreadyClaimed", false);

        ResponseEntity<Map<String, Object>> old = resource.accept("old");
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(old.getBody()).containsEntry("state", "EXPIRED");
    }

    private static void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
