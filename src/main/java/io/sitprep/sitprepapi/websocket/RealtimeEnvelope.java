package io.sitprep.sitprepapi.websocket;

import java.time.Instant;

/**
 * Versioned frame for new realtime streams. Existing topics keep their legacy
 * payloads until their namespace migration; newly migrated streams use this
 * shape so event identity and reconciliation time are transport-level fields.
 */
public record RealtimeEnvelope<T>(
        int v,
        String type,
        Instant at,
        T data
) {
    public static final int CURRENT_VERSION = 1;

    public static <T> RealtimeEnvelope<T> v1(String type, T data) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type is required");
        }
        if (data == null) {
            throw new IllegalArgumentException("event data is required");
        }
        return new RealtimeEnvelope<>(CURRENT_VERSION, type.trim(), Instant.now(), data);
    }
}
