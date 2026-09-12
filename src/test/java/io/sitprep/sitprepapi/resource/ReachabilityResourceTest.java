package io.sitprep.sitprepapi.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reachability probe's contract is its STATUS CODE and nothing else.
 *
 * <p>The client asks "did packets reach SitPrep", and treats any HTTP response
 * as yes. This endpoint exists so that question has a stable, cheap, meaningful
 * answer rather than relying on a 401 from a nonexistent route — which worked
 * by accident and would have gone on "working" while meaning nothing.
 */
class ReachabilityResourceTest {

    private final ReachabilityResource resource = new ReachabilityResource();

    @Test
    @DisplayName("answers 204 with no body")
    void answers204() {
        ResponseEntity<Void> r = resource.ping();
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(r.getBody()).isNull();
    }

    @Test
    @DisplayName("forbids caching — a cached 204 would report a service that is not there")
    void isNotCacheable() {
        String cacheControl = resource.ping().getHeaders().getFirst("Cache-Control");
        assertThat(cacheControl).isNotNull();
        assertThat(cacheControl).contains("no-store");
    }

    @Test
    @DisplayName("touches nothing — safe to call on resume and after every failure")
    void hasNoDependencies() {
        // Constructed with no collaborators above and still answers, which is
        // the property that makes it safe to poll: no database, no alert
        // snapshot, no auth lookup. If this ever needs a dependency, it has
        // stopped being a reachability probe and become a health check.
        assertThat(resource.ping().getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("lives under /api/public so it is answerable while signed out")
    void isPublic() {
        // Reachability must be answerable with no token and with an expired
        // one — those are exactly the states a user is in when they need to
        // know whether the app can talk to anything. /api/public/** is pinned
        // to permitAll in SecurityConfig.
        var mapping = ReachabilityResource.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/api/public");
    }
}
