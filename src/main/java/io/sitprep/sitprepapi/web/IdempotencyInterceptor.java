package io.sitprep.sitprepapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.IdempotencyKey;
import io.sitprep.sitprepapi.repo.IdempotencyKeyRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Idempotency-Key cache enforcement — audit P1-10.
 *
 * <p>For controller methods marked {@link Idempotent}:</p>
 * <ol>
 *   <li>If the request has no {@code Idempotency-Key} header, pass through
 *       with no cache interaction.</li>
 *   <li>If the header is present and the (caller, endpoint, key) tuple has
 *       a fresh cached entry, write the cached status + body verbatim and
 *       skip the controller entirely.</li>
 *   <li>Otherwise, execute the handler. If the response is 2xx, persist
 *       the status + body before letting it propagate to the client.
 *       Non-2xx responses are not cached — clients should be free to retry
 *       past a transient failure without being locked into it.</li>
 * </ol>
 *
 * <p>Implemented as a {@link OncePerRequestFilter} so we can wrap the
 * response in a {@link ContentCachingResponseWrapper} and read the body
 * after the controller has written it. Annotation-vs-handler-method
 * matching happens via the {@link HandlerMapping} bean(s) — same path
 * Spring uses internally for {@code @PreAuthorize} etc.</p>
 */
@Component
public class IdempotencyInterceptor extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    /** Header name. Conventional spelling per Stripe / IETF draft. */
    public static final String HEADER = "Idempotency-Key";

    /** Cap on header value length to keep PK index pages small. */
    private static final int MAX_KEY_LEN = 200;

    private final IdempotencyKeyRepo repo;
    private final ObjectMapper objectMapper;
    private final List<HandlerMapping> handlerMappings;

    @Value("${app.idempotency.ttlHours:24}")
    private long ttlHours;

    public IdempotencyInterceptor(IdempotencyKeyRepo repo,
                                  ObjectMapper objectMapper,
                                  List<HandlerMapping> handlerMappings) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.handlerMappings = handlerMappings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Only POSTs matter — annotation lives on POST handlers and the
        // request body shape is what's at risk for double-submit.
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (header.length() > MAX_KEY_LEN) {
            // Reject overlong keys so we don't widen the PK index pages.
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Idempotency-Key exceeds " + MAX_KEY_LEN + " chars");
            return;
        }

        HandlerMethod handler = resolveHandler(request);
        if (handler == null || handler.getMethodAnnotation(Idempotent.class) == null) {
            // No @Idempotent on the target — header is meaningless here,
            // don't lock the caller into a no-op cache.
            chain.doFilter(request, response);
            return;
        }

        String caller = AuthUtils.getCurrentUserEmail();
        if (caller == null) {
            // Unauthenticated requests can't be uniquely keyed safely
            // (otherwise one attacker could replay another user's cached
            // response). Let downstream auth handle the rejection.
            chain.doFilter(request, response);
            return;
        }
        String endpoint = endpointKey(request);
        String key = header.trim();
        IdempotencyKey.PK pk = new IdempotencyKey.PK(caller, endpoint, key);

        Optional<IdempotencyKey> hit;
        try {
            hit = repo.findById(pk);
        } catch (Exception e) {
            log.warn("Idempotency lookup failed; proceeding without cache: {}", e.getMessage());
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
            chain.doFilter(request, response);
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofHours(ttlHours));
        if (hit.isPresent() && hit.get().getCreatedAt().isAfter(cutoff)) {
            // Cache hit — short-circuit. Write the original body verbatim.
            IdempotencyKey cached = hit.get();
            response.setStatus(cached.getResponseStatusCode());
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            byte[] body = cached.getResponseBody().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
            return;
        }

        // Cache miss (or stale entry). Run the handler, capture the
        // response, and persist on success.
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapped);

        int status = wrapped.getStatus();
        byte[] bodyBytes = wrapped.getContentAsByteArray();
        // copyBodyToResponse() is mandatory — without it the client gets
        // an empty body because ContentCachingResponseWrapper buffers.
        wrapped.copyBodyToResponse();

        if (status >= 200 && status < 300 && bodyBytes.length > 0) {
            try {
                persist(caller, endpoint, key, status, bodyBytes);
            } catch (Exception e) {
                // Persistence failure shouldn't fail the user's request —
                // they already got a 2xx. Worst case: a future replay
                // re-runs the work.
                log.warn("Idempotency persist failed: {}", e.getMessage());
                try { Sentry.captureException(e); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Stable endpoint identifier for the cache key. Prefers the Spring
     * URL pattern ({@code /api/posts/{id}}) over the literal path so two
     * different ids on the same handler don't share cache slots — but for
     * the annotated POSTs today the pattern equals the literal path.
     */
    private String endpointKey(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isBlank()) return s;
        return request.getRequestURI();
    }

    private HandlerMethod resolveHandler(HttpServletRequest request) {
        for (HandlerMapping mapping : handlerMappings) {
            try {
                HandlerExecutionChain chain = mapping.getHandler(request);
                if (chain != null && chain.getHandler() instanceof HandlerMethod hm) {
                    return hm;
                }
            } catch (Exception ignored) {
                // Mapping lookup can throw on misconfigured routes;
                // fall through to the next mapping.
            }
        }
        return null;
    }

    @Transactional
    protected void persist(String caller, String endpoint, String key,
                           int status, byte[] body) {
        // Normalize through Jackson when possible so the stored shape is
        // canonical JSON; fall back to the raw bytes for non-JSON 2xx
        // bodies (rare — controllers return DTOs).
        String json;
        try {
            Object tree = objectMapper.readTree(body);
            json = objectMapper.writeValueAsString(tree);
        } catch (Exception e) {
            json = new String(body, StandardCharsets.UTF_8);
        }
        IdempotencyKey row = new IdempotencyKey();
        row.setCallerEmail(caller);
        row.setEndpoint(endpoint);
        row.setKey(key);
        row.setResponseStatusCode(status);
        row.setResponseBody(json);
        row.setCreatedAt(Instant.now());
        repo.save(row);
    }
}
