package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * L2 cache row for external POIs (Overpass etc.) — Community map Phase 2
 * (docs/COMMUNITY_API_GAMEPLAN.md §3.3). One row per tile-snapped bounding box;
 * {@code payload} is the normalized {@code MapPoiDto[]} JSON for that tile.
 *
 * <p>{@code payload} is stored as TEXT (LONGVARCHAR) not jsonb — it is an opaque
 * blob deserialized whole on read, never queried into, matching the codebase's
 * JSON-blob convention and keeping ddl-auto=validate happy.</p>
 */
@Entity
@Table(name = "external_poi_cache")
@Getter
@Setter
@NoArgsConstructor
public class ExternalPoiCache {

    @Id
    @Column(name = "cache_key", length = 160)
    private String cacheKey;

    @Column(name = "source", length = 32, nullable = false)
    private String source;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public ExternalPoiCache(String cacheKey, String source, String payload,
                            Instant fetchedAt, Instant expiresAt) {
        this.cacheKey = cacheKey;
        this.source = source;
        this.payload = payload;
        this.fetchedAt = fetchedAt;
        this.expiresAt = expiresAt;
    }
}
