package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;

@Entity
public class OriginLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // <-- 🔹 NEW: Label like "Home" or "Office"
    private String address;
    private Double lat;
    private Double lng;

    @Column(name = "user_email", nullable = false)
    private String ownerEmail;

    // Owning household (Group.groupId, groupType="Household"). Nullable
    // during the ownerEmail->household migration; backfilled on boot.
    private String householdId;

    /**
     * home | work | school | other, or null (typed freehand) — V92. A {@code home}
     * starting point takes its location from the household on read; see
     * {@link #normalizeKind}.
     */
    private String kind;

    public OriginLocation() {}

    /** The four kinds a starting point can be; anything else is null. */
    public static String normalizeKind(Object raw) {
        if (raw == null) return null;
        String k = raw.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (k) {
            case "home", "work", "school", "other" -> k;
            default -> null;
        };
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = normalizeKind(kind); }

    public boolean isHome() { return "home".equals(kind); }

    public OriginLocation(String name, String address, Double lat, Double lng, String ownerEmail) {
        this.name = name;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.ownerEmail = ownerEmail;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    public String getHouseholdId() { return householdId; }
    public void setHouseholdId(String householdId) { this.householdId = householdId; }
}