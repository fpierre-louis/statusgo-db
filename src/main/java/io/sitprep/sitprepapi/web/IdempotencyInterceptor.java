package io.sitprep.sitprepapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.IdempotencyKey;
import io.sitprep.sitprepapi.dto.ApiResponse;
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
 *   <li>Otherwise, claim the key first ({@link IdempotencyKeyRepo#reserve},
 *       an insert the primary key makes atomic), then execute the handler.
 *       If the response is 2xx, store the status + body on the claim.
 *       Non-2xx responses and exceptions release the claim — clients should
 *       be free to retry past a transient failure without being locked into it.</li>
 *   <li>A request that finds the key claimed but not yet answered (a
 *       concurrent first attempt, e.g. an outbox replay while the original
 *       is still running) gets 409 {@code IDEMPOTENCY_IN_PROGRESS} with
 *       {@code Retry-After}; it never runs the handler a second time. The FE
 *       outbox retries that code instead of dropping the job.</li>
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

    /** Error code for a key whose first request is still running. */
    public static final String IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";

    /**
     * How long an unanswered claim holds the key. Longer than any request
     * runs (Heroku's router gives up at 30 s); after it, the claim is treated
     * as a request that died and the key can be claimed again.
     */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

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

        Instant now = Instant.now();
        if (hit.isPresent()) {
            IdempotencyKey row = hit.get();
            boolean answered = row.getResponseStatusCode() != 0;
            if (answered && row.getCreatedAt().isAfter(now.minus(Duration.ofHours(ttlHours)))) {
                // Cache hit — short-circuit. Write the original body verbatim.
                writeBody(response, row.getResponseStatusCode(),
                        row.getResponseBody().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!answered && row.getCreatedAt().isAfter(now.minus(CLAIM_LEASE))) {
                writeInProgress(response);
                return;
            }
            // An answer past the TTL, or a claim whose request died: clear it
            // and claim the key afresh below.
            try {
                repo.deleteExpired(caller, endpoint, key, row.getCreatedAt());
            } catch (Exception e) {
                log.warn("Idempotency expired-row delete failed: {}", e.getMessage());
            }
        }

        int claimed;
        try {
            claimed = repo.reserve(caller, endpoint, key, now);
        } catch (Exception e) {
            log.warn("Idempotency claim failed; proceeding without cache: {}", e.getMessage());
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
            chain.doFilter(request, response);
            return;
        }
        if (claimed == 0) {
            // A concurrent request with the same key claimed it first.
            writeInProgress(response);
            return;
        }

        // Run the handler, capture the response, and answer the claim.
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            release(caller, endpoint, key);
            throw e;
        }

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
                // they already got a 2xx. Release the claim; worst case a
                // future replay re-runs the work.
                log.warn("Idempotency persist failed: {}", e.getMessage());
                try { Sentry.captureException(e); } catch (Throwable ignored) {}
                release(caller, endpoint, key);
            }
        } else {
            release(caller, endpoint, key);
        }
    }

    private void release(String caller, String endpoint, String key) {
        try {
            repo.release(caller, endpoint, key);
        } catch (Exception e) {
            // The claim then expires after CLAIM_LEASE.
            log.warn("Idempotency claim release failed: {}", e.getMessage());
        }
    }

    private void writeInProgress(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "2");
        writeBody(response, HttpServletResponse.SC_CONFLICT, objectMapper.writeValueAsBytes(
                ApiResponse.error(IN_PROGRESS, "This request is still being processed. Retry shortly.")));
    }

    private static void writeBody(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
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

    // No @Transactional here — adding it triggers Spring CGLib proxying of
    // this OncePerRequestFilter subclass. CGLib can't proxy the filter's
    // final init() method, so the generated subclass is instantiated without
    // GenericFilterBean's logger field being set, and the first init() call
    // NPEs ("Cannot invoke ... isDebugEnabled() because this.logger is null").
    // The repo's claim/complete/release methods are @Transactional themselves,
    // so each opens its own short tx — exactly what we want for the
    // idempotency cache (independent of the controller's tx).
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
        repo.complete(caller, endpoint, key, status, json);
    }
}
