package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NwsZoneCentroid;
import org.springframework.data.jpa.repository.JpaRepository;

/** Durable NWS zone centres (V105), loaded by NwsZoneService at startup. */
public interface NwsZoneCentroidRepo extends JpaRepository<NwsZoneCentroid, String> {
}
