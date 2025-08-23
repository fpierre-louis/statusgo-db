package io.sitprep.sitprepapi.websocket;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks connected sessions per user (multi-device/multi-tab safe). */
@Service
public class WebSocketPresenceService {

    // userEmail -> set of active sessionIds
    private final Map<String, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    public void addSession(String userEmail, String sessionId) {
        sessionsByUser
                .computeIfAbsent(userEmail, e -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
    }

    public void removeSession(String userEmail, String sessionId) {
        Set<String> set = sessionsByUser.get(userEmail);
        if (set == null) return;
        set.remove(sessionId);
        if (set.isEmpty()) {
            sessionsByUser.remove(userEmail);
        }
    }

    public boolean isUserOnline(String userEmail) {
        Set<String> set = sessionsByUser.get(userEmail);
        return set != null && !set.isEmpty();
    }

    public Set<String> getSessions(String userEmail) {
        return sessionsByUser.getOrDefault(userEmail, Set.of());
    }
}
