package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.NwsZoneCentroid;
import io.sitprep.sitprepapi.repo.NwsZoneCentroidRepo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Zone centres survive a restart (V105, 2026-10-09): the cache refills from the
 * table at startup, so a deploy no longer leaves zone-only alerts unplaceable
 * (and unposted) for the hour the warm takes.
 */
class NwsZoneCentroidStoreTest {

    @Test
    void storedCentresAreAvailableRightAfterStartup() {
        NwsZoneCentroidRepo repo = mock(NwsZoneCentroidRepo.class);
        when(repo.findAll()).thenReturn(List.of(new NwsZoneCentroid("utz106", 40.07, -111.98, Instant.EPOCH)));
        NwsZoneService zones = new NwsZoneService();
        zones.setStore(repo);

        assertTrue(zones.centroidForZone("UTZ106").isEmpty(), "nothing before the load");
        zones.loadStoredCentroids();

        double[] c = zones.centroidForZone("UTZ106").orElseThrow();
        assertEquals(40.07, c[0], 1e-9);   // [lat, lng], as the dispatcher reads it
        assertEquals(-111.98, c[1], 1e-9);
    }

    @Test
    void noStoreOrABrokenStoreNeverBreaksTheService() {
        NwsZoneService bare = new NwsZoneService();
        bare.loadStoredCentroids(); // no repo: a no-op
        assertTrue(bare.centroidForZone("UTZ106").isEmpty());

        NwsZoneCentroidRepo broken = mock(NwsZoneCentroidRepo.class);
        when(broken.findAll()).thenThrow(new RuntimeException("db down"));
        NwsZoneService zones = new NwsZoneService();
        zones.setStore(broken);
        assertDoesNotThrow(zones::loadStoredCentroids);
    }
}
