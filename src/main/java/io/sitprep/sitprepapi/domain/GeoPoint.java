package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard latitude/longitude coordinate, shared across the backend so every
 * geolocated concept stores a point the same way (Database Phase of
 * docs/MAP_REBUILD_PLAN.md).
 *
 * <p>The default column names are {@code latitude}/{@code longitude}, which
 * match the columns on {@code Group} and {@code UserInfo} — those entities can
 * embed this directly. A host whose columns are named differently (e.g. the
 * place entities' {@code lat}/{@code lng}) overrides them with
 * {@code @AttributeOverrides} at the embed site.</p>
 *
 * <p>Kept intentionally minimal ({@code lat}/{@code lng}) for this phase. A
 * follow-up may add cached reverse-geocode fields ({@code zipBucket},
 * {@code shortLabel}) here so every point carries its own Nextdoor-style
 * label — see the reverse-geocode normalization item in the plan.</p>
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeoPoint {

    @Column(name = "latitude")
    private Double lat;

    @Column(name = "longitude")
    private Double lng;

    /** Null when both coordinates are absent, so an all-null embed reads as "no point". */
    public static GeoPoint of(Double lat, Double lng) {
        if (lat == null && lng == null) return null;
        return new GeoPoint(lat, lng);
    }

    public boolean hasPoint() {
        return lat != null && lng != null;
    }
}
