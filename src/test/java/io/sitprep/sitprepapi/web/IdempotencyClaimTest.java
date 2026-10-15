package io.sitprep.sitprepapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.IdempotencyKey;
import io.sitprep.sitprepapi.repo.IdempotencyKeyRepo;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key is claimed before the handler runs (build-10 backlog: the old
 * check-then-insert let two concurrent first attempts both create). The
 * outbox replays a write after a timeout, possibly while the first attempt is
 * still running; the replay must not run the handler again.
 */
class IdempotencyClaimTest {

    static class Target {
        @Idempotent
        public void create() { /* handler marker only */ }
    }

    private final Map<IdempotencyKey.PK, IdempotencyKey> store = new ConcurrentHashMap<>();
    private final IdempotencyKeyRepo repo = FakeIdempotencyRepo.create(store);
    private final IdempotencyInterceptor filter = filter(repo);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static IdempotencyInterceptor filter(IdempotencyKeyRepo repo) {
        HandlerMapping mapping = req -> {
            try {
                return new HandlerExecutionChain(new HandlerMethod(new Target(), "create"));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        };
        IdempotencyInterceptor f = new IdempotencyInterceptor(repo, new ObjectMapper().findAndRegisterModules(), List.of(mapping));
        ReflectionTestUtils.setField(f, "ttlHours", 24L);
        return f;
    }

    private static MockHttpServletResponse post(IdempotencyInterceptor filter, String key, FilterChain chain)
            throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@x.com", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/things");
        req.addHeader(IdempotencyInterceptor.HEADER, key);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    private static FilterChain answering(int status, String body, AtomicInteger runs) {
        return (req, res) -> {
            runs.incrementAndGet();
            var http = (jakarta.servlet.http.HttpServletResponse) res;
            http.setStatus(status);
            http.setContentType("application/json");
            http.getWriter().write(body);
        };
    }

    @Test
    void twoConcurrentFirstAttempts_runTheHandlerOnce_andTheSecondIsToldToRetry() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch inHandler = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain slow = (req, res) -> {
            runs.incrementAndGet();
            inHandler.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            var http = (jakarta.servlet.http.HttpServletResponse) res;
            http.setStatus(200);
            http.setContentType("application/json");
            http.getWriter().write("{\"id\":\"first\"}");
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MockHttpServletResponse> first = pool.submit(() -> post(filter, "k-1", slow));
            assertThat(inHandler.await(5, TimeUnit.SECONDS)).isTrue();

            // The replay arrives while the first is still inside the handler.
            MockHttpServletResponse replay = post(filter, "k-1", answering(200, "{\"id\":\"second\"}", runs));
            assertThat(replay.getStatus()).isEqualTo(409);
            assertThat(replay.getHeader("Retry-After")).isEqualTo("2");
            assertThat(new ObjectMapper().readTree(replay.getContentAsString()).at("/error/code").asText())
                    .isEqualTo(IdempotencyInterceptor.IN_PROGRESS);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(runs.get()).isEqualTo(1);

        // Once answered, a retry gets the first answer back without running the handler.
        MockHttpServletResponse later = post(filter, "k-1", answering(200, "{\"id\":\"third\"}", runs));
        assertThat(later.getStatus()).isEqualTo(200);
        assertThat(later.getContentAsString()).contains("first");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void aFailedAttemptFreesTheKey_soTheRetryRuns() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        assertThat(post(filter, "k-2", answering(503, "{\"error\":\"busy\"}", runs)).getStatus()).isEqualTo(503);
        assertThat(store).isEmpty();

        assertThat(post(filter, "k-2", answering(200, "{\"id\":\"ok\"}", runs)).getStatus()).isEqualTo(200);
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void aThrowingHandlerFreesTheKey() {
        FilterChain boom = (req, res) -> { throw new IllegalStateException("boom"); };
        try {
            post(filter, "k-3", boom);
        } catch (Exception expected) {
            // rethrown to the servlet container as before
        }
        assertThat(store).isEmpty();
    }

    @Test
    void aClaimWhoseRequestDied_canBeClaimedAgainAfterTheLease() throws Exception {
        IdempotencyKey dead = new IdempotencyKey();
        dead.setCallerEmail("owner@x.com");
        dead.setEndpoint("/api/things");
        dead.setKey("k-4");
        dead.setResponseStatusCode(0);
        dead.setResponseBody("");
        dead.setCreatedAt(Instant.now().minus(IdempotencyInterceptor.CLAIM_LEASE).minusSeconds(1));
        store.put(new IdempotencyKey.PK("owner@x.com", "/api/things", "k-4"), dead);

        AtomicInteger runs = new AtomicInteger();
        MockHttpServletResponse res = post(filter, "k-4", answering(200, "{\"id\":\"retried\"}", runs));
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(runs.get()).isEqualTo(1);
        assertThat(store.values()).singleElement()
                .satisfies(row -> assertThat(row.getResponseStatusCode()).isEqualTo(200));
    }
}
