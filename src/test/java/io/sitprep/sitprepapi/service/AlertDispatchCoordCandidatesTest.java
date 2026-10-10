package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A zone-only alert is placed at the first targeted zone whose centre lands in
 * a zip bucket, not only the first zone (2026-10-09: the Wasatch Front Flood
 * Watch led with "Great Salt Lake Desert and Mountains", found no zip there,
 * and was never posted).
 */
class AlertDispatchCoordCandidatesTest {

    @Test
    void zoneCentresAreOfferedInOrderAsLonLat() {
        NwsZoneService zones = mock(NwsZoneService.class);
        when(zones.centroidForZone("UTZ101")).thenReturn(Optional.of(new double[] { 40.9, -113.4 })); // desert
        when(zones.centroidForZone("UTZ102")).thenReturn(Optional.of(new double[] { 40.5, -112.4 })); // Tooele
        when(zones.centroidForZone("UTZ103")).thenReturn(Optional.empty());
        AlertDispatchService d = new AlertDispatchService(null, null, null, null, null, null, zones, null);

        List<double[]> c = d.dispatchCoordCandidates(
                TestAlerts.nws("Flood Watch").ugc(List.of("UTZ101", "UTZ102", "UTZ103")).build());

        assertEquals(2, c.size());
        assertArrayEquals(new double[] { -113.4, 40.9 }, c.get(0));
        assertArrayEquals(new double[] { -112.4, 40.5 }, c.get(1));
    }

    @Test
    void candidatesAreCappedSoOneAlertCannotFloodTheGeocoder() {
        NwsZoneService zones = mock(NwsZoneService.class);
        when(zones.centroidForZone(anyString())).thenReturn(Optional.of(new double[] { 40.0, -111.0 }));
        AlertDispatchService d = new AlertDispatchService(null, null, null, null, null, null, zones, null);

        List<double[]> c = d.dispatchCoordCandidates(TestAlerts.nws("Flood Watch")
                .ugc(List.of("A", "B", "C", "D", "E", "F", "G", "H", "I")).build());

        assertEquals(AlertDispatchService.MAX_DISPATCH_COORD_CANDIDATES, c.size());
    }
}
