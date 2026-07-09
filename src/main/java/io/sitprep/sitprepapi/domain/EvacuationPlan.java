package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;

@Entity
public class EvacuationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerEmail;

    // Owning household (Group.groupId, groupType="Household"). Nullable
    // during the ownerEmail->household migration; backfilled on boot.
    private String householdId;

    private String name;
    private String origin;
    private String destination;
    private boolean deploy;

    private String shelterName;
    private String shelterAddress;
    private String shelterPhoneNumber;
    private Double lat;
    private Double lng;

    private String travelMode;  // ✅ Added
    private String shelterInfo; // ✅ Added

    // Evacuation route semantics (V35). primaryRouteNotes is a BASELINE readiness
    // signal; alternateRouteNotes + offlineMapSaved are ADVANCED (never lower the
    // baseline score). lastPracticedAt is plumbed for a future drill hook.
    private String primaryRouteNotes;
    private String alternateRouteNotes;
    private boolean offlineMapSaved;
    private Instant lastPracticedAt;

    // Getters and Setters
    public String getTravelMode() { return travelMode; }
    public void setTravelMode(String travelMode) { this.travelMode = travelMode; }

    public String getShelterInfo() { return shelterInfo; }
    public void setShelterInfo(String shelterInfo) { this.shelterInfo = shelterInfo; }

    public String getPrimaryRouteNotes() { return primaryRouteNotes; }
    public void setPrimaryRouteNotes(String primaryRouteNotes) { this.primaryRouteNotes = primaryRouteNotes; }

    public String getAlternateRouteNotes() { return alternateRouteNotes; }
    public void setAlternateRouteNotes(String alternateRouteNotes) { this.alternateRouteNotes = alternateRouteNotes; }

    public boolean isOfflineMapSaved() { return offlineMapSaved; }
    public void setOfflineMapSaved(boolean offlineMapSaved) { this.offlineMapSaved = offlineMapSaved; }

    public Instant getLastPracticedAt() { return lastPracticedAt; }
    public void setLastPracticedAt(Instant lastPracticedAt) { this.lastPracticedAt = lastPracticedAt; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public String getHouseholdId() {
        return householdId;
    }

    public void setHouseholdId(String householdId) {
        this.householdId = householdId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public boolean isDeploy() {
        return deploy;
    }

    public void setDeploy(boolean deploy) {
        this.deploy = deploy;
    }

    public String getShelterName() {
        return shelterName;
    }

    public void setShelterName(String shelterName) {
        this.shelterName = shelterName;
    }

    public String getShelterAddress() {
        return shelterAddress;
    }

    public void setShelterAddress(String shelterAddress) {
        this.shelterAddress = shelterAddress;
    }

    public String getShelterPhoneNumber() {
        return shelterPhoneNumber;
    }

    public void setShelterPhoneNumber(String shelterPhoneNumber) {
        this.shelterPhoneNumber = shelterPhoneNumber;
    }

    public Double getLat() {
        return lat;
    }

    public void setLat(Double lat) {
        this.lat = lat;
    }

    public Double getLng() {
        return lng;
    }

    public void setLng(Double lng) {
        this.lng = lng;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvacuationPlan)) return false;
        EvacuationPlan that = (EvacuationPlan) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
