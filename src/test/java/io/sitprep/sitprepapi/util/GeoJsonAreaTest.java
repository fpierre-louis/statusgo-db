package io.sitprep.sitprepapi.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeoJsonAreaTest {

    private static List<List<Double>> ring(double minLng, double minLat, double maxLng, double maxLat) {
        return List.of(List.of(minLng, minLat), List.of(maxLng, minLat), List.of(maxLng, maxLat),
                List.of(minLng, maxLat), List.of(minLng, minLat));
    }

    @Test
    void polygonWithAHole() {
        GeoJsonArea a = GeoJsonArea.parse(Map.of("type", "Polygon", "coordinates",
                List.of(ring(-111, 40, -110, 41), ring(-110.6, 40.4, -110.4, 40.6))));
        assertThat(a).isNotNull();
        assertThat(a.contains(40.2, -110.8)).isTrue();
        assertThat(a.contains(40.5, -110.5)).as("inside the hole").isFalse();
        assertThat(a.contains(41.5, -110.5)).isFalse();
    }

    @Test
    void multiPolygonAndANonConvexShape() {
        // An L-shape: the notch at (40.8, -110.2) is inside the bbox but outside the shape.
        List<List<Double>> l = List.of(List.of(-111.0, 40.0), List.of(-110.0, 40.0), List.of(-110.0, 40.5),
                List.of(-110.5, 40.5), List.of(-110.5, 41.0), List.of(-111.0, 41.0), List.of(-111.0, 40.0));
        GeoJsonArea a = GeoJsonArea.parse(Map.of("type", "MultiPolygon", "coordinates",
                List.of(List.of(l), List.of(ring(-100, 30, -99, 31)))));
        assertThat(a.contains(40.8, -110.8)).isTrue();
        assertThat(a.contains(40.8, -110.2)).isFalse();
        assertThat(a.contains(30.5, -99.5)).isTrue();
    }

    @Test
    void notAnArea() {
        assertThat(GeoJsonArea.parse(Map.of("type", "Point", "coordinates", List.of(-110.0, 40.0)))).isNull();
        assertThat(GeoJsonArea.parse(Map.of("type", "Polygon", "coordinates", List.of(List.of("x"))))).isNull();
        assertThat(GeoJsonArea.parse(null)).isNull();
        assertThat(GeoJsonArea.parse("POLYGON((...))")).isNull();
    }
}
