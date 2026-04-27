package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A named place a user has registered (Home, Work, Grandma's, etc.).
 * Powers the "save / edit my locations" flow on the frontend and feeds
 * into community-discover queries (the user's chosen reference point).
 *
 * <p>Exactly one row per user can have {@code isHome=true}. Other rows
 * are auxiliary places. The reverse-geocoded {@code city/region/state/
 * country/zipBucket} fields are populated by NominatimGeocodeService
 * after a write so subsequent reads don't re-resolve.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "user_saved_location",
        indexes = {
                @Index(name = "idx_usl_owner", columnList = "owner_email"),
                @Index(name = "idx_usl_owner_home", columnList = "owner_email,is_home")
        }
)
public class UserSavedLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false)
    private String ownerEmail;

    /** Display label: "Home", "Work", "Grandma's", etc. */
    @Column(nullable = false)
    private String name;

    /** Free-form address text, optional — useful for printing / sharing. */
    private String address;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** True for the user's primary "Home" location. At most one per user. */
    @Column(name = "is_home", nullable = false)
    private boolean isHome;

    /** Cached reverse-geocode results — written by NominatimGeocodeService. */
    private String city;
    private String region;
    private String state;
    private String country;

    /** First 3 chars of postcode — used as a bounding-box pre-filter on /community/discover. */
    @Column(name = "zip_bucket")
    private String zipBucket;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
