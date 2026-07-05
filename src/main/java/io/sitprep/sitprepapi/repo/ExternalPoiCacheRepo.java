package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.ExternalPoiCache;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * L2 external-POI cache (Community map Phase 2). Keyed by the tile-snapped
 * bounding-box cache key. Lookups are PK hits ({@code findById}).
 */
public interface ExternalPoiCacheRepo extends JpaRepository<ExternalPoiCache, String> {
}
