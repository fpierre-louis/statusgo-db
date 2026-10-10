package io.sitprep.sitprepapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The household ask-everyone 429 carries {@code Retry-After}; a cross-origin
 * page (sitprep.app, the iOS WebView) can read it only if CORS exposes it.
 */
class CorsExposedHeadersTest {

    @Test
    void retryAfterIsExposedToCrossOriginReaders() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/groups/g/check-in-request");
        req.addHeader("Origin", "https://sitprep.app");
        CorsConfiguration cfg = new SecurityConfig(null).corsConfigurationSource().getCorsConfiguration(req);
        assertThat(cfg).isNotNull();
        assertThat(cfg.getExposedHeaders()).containsExactly("Retry-After");
        assertThat(cfg.checkOrigin("https://sitprep.app")).isEqualTo("https://sitprep.app");
    }
}
