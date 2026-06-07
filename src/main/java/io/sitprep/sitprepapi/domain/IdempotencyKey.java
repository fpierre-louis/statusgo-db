package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Short-TTL cache of the last successful response for a (caller, endpoint,
 * client-supplied key) tuple — audit P1-10. Lets the FE retry a POST after
 * a flaky network without creating a duplicate post / duplicate FCM fanout:
 * the second request hits the cache and returns the original 2xx body.
 *
 * <p>Composite PK keeps the lookup index-only and avoids a surrogate id
 * column that would never be referenced. The {@code createdAt} index backs
 * the sweeper that prunes rows past the TTL (default 24h).</p>
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@IdClass(IdempotencyKey.PK.class)
@Table(
        name = "idempotency_keys",
        indexes = @Index(name = "idx_idem_created_at", columnList = "created_at")
)
public class IdempotencyKey {

    @Id
    @Column(name = "caller_email", nullable = false, length = 320)
    private String callerEmail;

    @Id
    @Column(name = "endpoint", nullable = false, length = 200)
    private String endpoint;

    @Id
    @Column(name = "idem_key", nullable = false, length = 200)
    private String key;

    @Column(name = "response_status_code", nullable = false)
    private int responseStatusCode;

    /** JSON-serialized response envelope. */
    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Composite-PK helper required by {@code @IdClass}. */
    public static class PK implements Serializable {
        private String callerEmail;
        private String endpoint;
        private String key;

        public PK() {}

        public PK(String callerEmail, String endpoint, String key) {
            this.callerEmail = callerEmail;
            this.endpoint = endpoint;
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(callerEmail, pk.callerEmail)
                    && Objects.equals(endpoint, pk.endpoint)
                    && Objects.equals(key, pk.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(callerEmail, endpoint, key);
        }
    }
}
