package io.sitprep.sitprepapi.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Bounds contract for the central write-boundary coordinate guard
 * (docs/location/LOCATION_FE_BE_ALIGNMENT_MATRIX.md §4.1). Every
 * user-facing coordinate-accepting service funnels through
 * {@link GeoUtil#requireValidLatLng} — these tests pin its semantics.
 */
class GeoUtilBoundsTest {

    @Test
    void nullPairPasses_optionalLocationWrites() {
        assertDoesNotThrow(() -> GeoUtil.requireValidLatLng(null, null));
    }

    @Test
    void validCoordinatesPass_includingEdges() {
        assertDoesNotThrow(() -> GeoUtil.requireValidLatLng(33.749, -84.388));
        assertDoesNotThrow(() -> GeoUtil.requireValidLatLng(90.0, 180.0));
        assertDoesNotThrow(() -> GeoUtil.requireValidLatLng(-90.0, -180.0));
        assertDoesNotThrow(() -> GeoUtil.requireValidLatLng(0.0, 0.0));
    }

    @Test
    void halfNullPairRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(33.749, null));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(null, -84.388));
    }

    @Test
    void outOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(90.0001, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(-90.0001, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(0.0, 180.0001));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(0.0, -180.0001));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(9999.0, 9999.0));
    }

    @Test
    void nonFiniteRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(0.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> GeoUtil.requireValidLatLng(Double.NEGATIVE_INFINITY, 0.0));
    }
}
