// src/main/java/io/sitprep/sitprepapi/websocket/WebSocketPresenceService.java
package io.sitprep.sitprepapi.websocket;

import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WebSocketPresenceService {

    // A thread-safe Set to store the emails of connected users.
    private final Set<String> connectedUsers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void addUser(String userEmail) {
        connectedUsers.add(userEmail);
    }

    public void removeUser(String userEmail) {
        connectedUsers.remove(userEmail);
    }

    public boolean isUserOnline(String userEmail) {
        return connectedUsers.contains(userEmail);
    }

    public Set<String> getConnectedUsers() {
        return Collections.unmodifiableSet(connectedUsers);
    }
}