package io.sitprep.sitprepapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Cross-cutting web concerns. Currently: ETag support so client refresh
 * patterns (MeContext.refresh, group view refetches) return 304 when
 * nothing changed instead of re-shipping the same JSON.
 *
 * <p>This is the "shallow" filter — Spring buffers the response, hashes
 * it, compares to {@code If-None-Match}. Saves bandwidth (304 with no
 * body) but doesn't skip the underlying DB work. For DB-skipping ETags
 * we'd compute from a content-derived signature in each resource;
 * deferred until we measure that the read path is actually the bottleneck.</p>
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> bean = new FilterRegistrationBean<>(
                new ShallowEtagHeaderFilter()
        );
        // Apply to all read endpoints. Multipart upload endpoints (POST
        // /api/posts, /api/images) skip the filter automatically — Spring's
        // ShallowEtag logic only ever buffers GET/HEAD responses anyway.
        bean.addUrlPatterns("/api/*");
        bean.setName("shallowEtagFilter");
        return bean;
    }
}
