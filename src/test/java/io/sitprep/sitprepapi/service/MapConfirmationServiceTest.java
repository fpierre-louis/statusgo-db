package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MapConfirmation;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmResponse;
import io.sitprep.sitprepapi.repo.MapConfirmationRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.ResourceListingRepo;
import io.sitprep.sitprepapi.resource.MapConfirmationResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-7 (V85): "Still here?" — target validation, the 10-minute cooldown (429),
 * one row per person, and the wire codes.
 */
class MapConfirmationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T15:00:00Z");

    private MapConfirmationRepo repo;
    private ResourceListingRepo resources;
    private PostRepo posts;
    private MapConfirmationService service;

    @BeforeEach
    void setUp() {
        repo = mock(MapConfirmationRepo.class);
        resources = mock(ResourceListingRepo.class);
        posts = mock(PostRepo.class);
        service = new MapConfirmationService(repo, resources, posts);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(resources.findByIdAndStatus(42L, ResourceListing.Status.APPROVED))
                .thenReturn(Optional.of(new ResourceListing()));
        when(repo.summarize(anyString(), anyCollection(), any())).thenReturn(summary("42", 3, NOW));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static List<Object[]> summary(String id, long count, Instant last) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] { id, count, last });
        return rows;
    }

    private static HttpStatus status(Throwable t) {
        return HttpStatus.valueOf(((ResponseStatusException) t).getStatusCode().value());
    }

    @Test
    void aFirstConfirmIsRecordedAndReturnsTheCount() {
        MapConfirmationService.Outcome o = service.confirm("resource", "42", "Ann@X.com", NOW);
        assertThat(o.accepted()).isTrue();
        assertThat(o.count()).isEqualTo(3);
        assertThat(o.lastAt()).isEqualTo(NOW);
        verify(repo).save(org.mockito.ArgumentMatchers.argThat(c ->
                "resource".equals(c.getTargetType()) && "42".equals(c.getTargetId())
                        && "ann@x.com".equals(c.getUserEmail()) && NOW.equals(c.getConfirmedAt())));
    }

    @Test
    void aReconfirmInsideTenMinutesIsRefused() {
        MapConfirmation mine = new MapConfirmation();
        mine.setConfirmedAt(NOW.minusSeconds(4 * 60));
        when(repo.findByTargetTypeAndTargetIdAndUserEmail("resource", "42", "ann@x.com"))
                .thenReturn(Optional.of(mine));

        MapConfirmationService.Outcome o = service.confirm("resource", "42", "ann@x.com", NOW);
        assertThat(o.accepted()).isFalse();
        assertThat(o.retryAfterSeconds()).isEqualTo(6 * 60);
        assertThat(o.count()).as("the current count still comes back").isEqualTo(3);
        verify(repo, never()).save(any());
    }

    @Test
    void aReconfirmAfterTheCooldownMovesTheSameRow() {
        MapConfirmation mine = new MapConfirmation();
        mine.setId(5L);
        mine.setTargetType("resource");
        mine.setTargetId("42");
        mine.setUserEmail("ann@x.com");
        mine.setConfirmedAt(NOW.minusSeconds(11 * 60));
        when(repo.findByTargetTypeAndTargetIdAndUserEmail("resource", "42", "ann@x.com"))
                .thenReturn(Optional.of(mine));

        assertThat(service.confirm("resource", "42", "ann@x.com", NOW).accepted()).isTrue();
        assertThat(mine.getConfirmedAt()).isEqualTo(NOW);
        verify(repo).save(mine); // updated, not a second row
    }

    @Test
    void onlyPlacesTheMapShowsCanBeConfirmed() {
        assertThatThrownBy(() -> service.confirm("group", "1", "a@x.com", NOW))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.confirm("resource", "abc", "a@x.com", NOW))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.confirm("osm", "node/1; drop", "a@x.com", NOW))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.confirm("resource", "43", "a@x.com", NOW)) // not approved / missing
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.NOT_FOUND));

        Post groupPost = new Post();
        groupPost.setGroupId("hh-1");
        when(posts.findById(7L)).thenReturn(Optional.of(groupPost));
        assertThatThrownBy(() -> service.confirm("post", "7", "a@x.com", NOW))
                .as("a group post is not a map place, and its existence is not disclosed")
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.NOT_FOUND));

        Post community = new Post();
        when(posts.findById(8L)).thenReturn(Optional.of(community));
        assertThat(service.confirm("post", "8", "a@x.com", NOW).accepted()).isTrue();
        assertThat(service.confirm("osm", "way/123", "a@x.com", NOW).accepted()).isTrue();
    }

    @Test
    void summariesOmitTargetsNobodyConfirmed() {
        when(repo.summarize(eq("post"), anyCollection(), any())).thenReturn(summary("8", 2, NOW));
        var byId = service.summaries("post", List.of("8", "9"), NOW);
        assertThat(byId).containsOnlyKeys("8");
        assertThat(byId.get("8").count()).isEqualTo(2);
    }

    // ── wire ───────────────────────────────────────────────────────────────

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ann@x.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void theEndpointAnswers200Then429WithRetryAfter() {
        signIn();
        MapConfirmationService svc = mock(MapConfirmationService.class);
        MapConfirmationResource resource = new MapConfirmationResource(svc);
        var req = new io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmRequest("resource", "42");

        when(svc.confirm(eq("resource"), eq("42"), eq("ann@x.com"), any()))
                .thenReturn(new MapConfirmationService.Outcome(true, 3, NOW, 0));
        ResponseEntity<ConfirmResponse> ok = resource.confirm(req);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).isEqualTo(new ConfirmResponse(3, NOW, true, null));

        when(svc.confirm(eq("resource"), eq("42"), eq("ann@x.com"), any()))
                .thenReturn(new MapConfirmationService.Outcome(false, 3, NOW, 360));
        ResponseEntity<ConfirmResponse> slow = resource.confirm(req);
        assertThat(slow.getStatusCode().value()).isEqualTo(429);
        assertThat(slow.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("360");
        assertThat(slow.getBody().retryAfterSeconds()).isEqualTo(360L);
    }

    @Test
    void theEndpointRequiresSignIn() {
        MapConfirmationResource resource = new MapConfirmationResource(mock(MapConfirmationService.class));
        assertThatThrownBy(() -> resource.confirm(
                new io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmRequest("resource", "42")))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
