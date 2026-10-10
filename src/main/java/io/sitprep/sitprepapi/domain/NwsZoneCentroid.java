package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One resolved NWS zone centre (V105). A durable copy of NwsZoneService's
 * in-memory cache, so a restart does not leave zone-only alerts without a
 * place to post for the hour it takes to re-warm.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "nws_zone_centroid")
public class NwsZoneCentroid {

    /** The UGC zone code, upper case — e.g. {@code UTZ106}. */
    @Id
    @Column(name = "ugc", length = 16, nullable = false)
    private String ugc;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    public NwsZoneCentroid(String ugc, double latitude, double longitude, Instant resolvedAt) {
        this.ugc = ugc;
        this.latitude = latitude;
        this.longitude = longitude;
        this.resolvedAt = resolvedAt;
    }
}
