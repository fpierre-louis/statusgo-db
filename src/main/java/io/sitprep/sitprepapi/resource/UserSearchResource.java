package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.UserSearchDto;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typeahead user search for the InviteSheet.
 *
 * <p>Privacy contract (docs/HOME_HOUSEHOLD_MERGE.md §5):</p>
 * <ul>
 *   <li>Discovery is opt-in. {@code UserInfo.searchable} defaults to
 *       {@code false}; only users who flipped it surface in name/prefix
 *       results.</li>
 *   <li>Exact-email lookup works regardless of {@code searchable} — but
 *       returns a confirmation-only response shape (no name, no avatar)
 *       so a non-searchable user's identity isn't leaked by trial-and-
 *       error email guessing.</li>
 *   <li>Rate limit: 30 q/min per caller, in-memory sliding window.</li>
 *   <li>Result DTO is sanitized (firstName/lastName/email/photoUrl only).
 *       No phone, address, lat/lng, FCM, group memberships.</li>
 *   <li>Minimum query length 3 chars (apart from exact-email shape).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/userinfo/search")
public class UserSearchResource {

    private static final Logger log = LoggerFactory.getLogger(UserSearchResource.class);

    private static final int MIN_QUERY_LEN = 3;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 20;
    private static final int RATE_LIMIT_PER_MIN = 30;
    private static final long RATE_WINDOW_MS = 60_000L;

    private final UserInfoRepo userInfoRepo;

    // Per-caller sliding window of recent search timestamps. Lazy-pruned
    // on each call so the map doesn't grow without bound. Concurrent so
    // two concurrent requests for the same caller don't race the prune.
    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    public UserSearchResource(UserInfoRepo userInfoRepo) {
        this.userInfoRepo = userInfoRepo;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @RequestParam("q") String rawQuery,
            @RequestParam(value = "limit", required = false) Integer limit) {

        String viewer = AuthUtils.requireAuthenticatedEmail();
        if (!allow(viewer)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Search rate limit exceeded — try again in a minute.");
        }

        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.isEmpty()) {
            return ResponseEntity.ok(SearchResponse.empty());
        }

        // Exact-email lookup short-circuit. Bypasses the searchable flag
        // because typing the exact email is a strong intent signal — the
        // caller already knows the address. We still return a confirmation
        // shape only (no profile preview) so this can't be used to scrape
        // names by guessing emails.
        if (looksLikeExactEmail(q)) {
            Optional<UserInfo> match = userInfoRepo.findByUserEmailIgnoreCase(q);
            if (match.isPresent() && !q.equalsIgnoreCase(viewer)) {
                return ResponseEntity.ok(SearchResponse.exactMatch());
            }
            return ResponseEntity.ok(SearchResponse.empty());
        }

        if (q.length() < MIN_QUERY_LEN) {
            return ResponseEntity.ok(SearchResponse.empty());
        }

        int cap = Math.min(MAX_LIMIT, limit == null || limit <= 0 ? DEFAULT_LIMIT : limit);
        List<UserInfo> rows = userInfoRepo.searchUsers(q, viewer, PageRequest.of(0, cap));
        List<UserSearchDto> results = new ArrayList<>(rows.size());
        for (UserInfo u : rows) {
            results.add(new UserSearchDto(
                    u.getUserFirstName(),
                    u.getUserLastName(),
                    u.getUserEmail(),
                    u.getProfileImageURL()
            ));
        }
        return ResponseEntity.ok(new SearchResponse(results, false));
    }

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * Prune timestamps older than the window, then admit/deny based on
     * the count + record. Atomic per-caller via the deque sync block.
     */
    private boolean allow(String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return false;
        String key = viewerEmail.trim().toLowerCase();
        long now = System.currentTimeMillis();
        Deque<Long> deque = rate.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && (now - deque.peekFirst()) > RATE_WINDOW_MS) {
                deque.pollFirst();
            }
            if (deque.size() >= RATE_LIMIT_PER_MIN) return false;
            deque.addLast(now);
            return true;
        }
    }

    private static boolean looksLikeExactEmail(String s) {
        // Cheap shape check — not full RFC validation. The downstream
        // findByUserEmailIgnoreCase decides whether the address actually
        // resolves to a user.
        if (s == null) return false;
        int at = s.indexOf('@');
        if (at <= 0 || at == s.length() - 1) return false;
        return s.indexOf('.', at) > at;
    }

    /**
     * Wire shape. The {@code matched} field is set when an exact-email
     * lookup found a user but we don't want to leak their profile
     * preview — the FE renders "If this is the right person, they'll
     * be added pending confirmation." The {@code results} field is the
     * normal name-prefix path.
     */
    public record SearchResponse(List<UserSearchDto> results, boolean matched) {
        public static SearchResponse empty() {
            return new SearchResponse(List.of(), false);
        }
        public static SearchResponse exactMatch() {
            return new SearchResponse(List.of(), true);
        }
    }
}
