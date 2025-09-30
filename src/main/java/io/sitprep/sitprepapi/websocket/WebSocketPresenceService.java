package io.sitprep.sitprepapi.websocket;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class WebSocketPresenceService {

    // ---- Tuning ----
    // If you decide to send periodic client heartbeats (/app/ping), you can enable pruning.
    private static final boolean ENABLE_STALE_PRUNE = false; // flip to true if you wire heartbeats
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    public static final class PresenceInfo {
        public final String sessionId;
        public final String email;       // optional, NOT trusted in MVP
        public final Instant connectedAt;
        private volatile Instant lastSeen; // heartbeat/bookkeeping

        public PresenceInfo(String sessionId, String email) {
            this.sessionId = sessionId;
            this.email = (email == null || email.isBlank()) ? null : email.trim();
            this.connectedAt = Instant.now();
            this.lastSeen = this.connectedAt;
        }

        public Instant getLastSeen() { return lastSeen; }
        private void touch() { this.lastSeen = Instant.now(); }
    }

    // sessionId -> presence
    private final Map<String, PresenceInfo> sessions = new ConcurrentHashMap<>();
    // email -> count of active sessions
    private final Map<String, Integer> emailCounts = new ConcurrentHashMap<>();

    public void addSession(String sessionId, String email) {
        PresenceInfo prev = sessions.put(sessionId, new PresenceInfo(sessionId, email));
        if (prev != null && prev.email != null) {
            decrement(prev.email);
        }
        if (email != null && !email.isBlank()) {
            increment(email.trim());
        }
        maybePruneStale();
    }

    public void removeSession(String sessionId) {
        PresenceInfo removed = sessions.remove(sessionId);
        if (removed != null && removed.email != null) {
            decrement(removed.email);
        }
    }

    /** Touch lastSeen for a connected session (e.g., from a /app/ping message). */
    public void heartbeat(String sessionId) {
        PresenceInfo info = sessions.get(sessionId);
        if (info != null) info.touch();
    }

    /** Used by NotificationService */
    public boolean isUserOnline(String email) {
        if (email == null || email.isBlank()) return false;
        return emailCounts.getOrDefault(email.trim(), 0) > 0;
    }

    /** Handy: how many sessions for this email are currently active. */
    public int getOnlineCount(String email) {
        if (email == null || email.isBlank()) return 0;
        return emailCounts.getOrDefault(email.trim(), 0);
    }

    /** Snapshot of all sessions. */
    public Map<String, PresenceInfo> snapshot() {
        return Map.copyOf(sessions);
    }

    /** Snapshot of emails considered "online" (count > 0). */
    public Set<String> getOnlineEmailsSnapshot() {
        return emailCounts.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Fetch presence by session if you need metadata (lastSeen, connectedAt). */
    public PresenceInfo getPresenceBySession(String sessionId) {
        return sessions.get(sessionId);
    }

    // ---- internals ----

    private void increment(String email) {
        emailCounts.merge(email, 1, Integer::sum);
    }

    private void decrement(String email) {
        emailCounts.compute(email, (e, n) -> {
            int next = (n == null ? 0 : n - 1);
            return next <= 0 ? null : next;
        });
    }

    private void maybePruneStale() {
        if (!ENABLE_STALE_PRUNE) return;

        final Instant cutoff = Instant.now().minus(STALE_AFTER);

        sessions.forEach((sid, info) -> {
            if (info.getLastSeen().isBefore(cutoff)) {
                // remove session and adjust counts atomically relative to sessions map
                if (sessions.remove(sid, info) && info.email != null) {
                    decrement(info.email);
                }
            }
        });
    }
}
