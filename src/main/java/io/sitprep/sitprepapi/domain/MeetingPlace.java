package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
public class MeetingPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerEmail;

    // Owning household (Group.groupId where groupType="Household").
    // Nullable during the ownerEmail->household migration; backfilled on
    // boot by HouseholdBackfillRunner. Plan resolution flips to this in
    // Phase 2 (see docs/WIP_HOUSEHOLD_PLANS.md).
    private String householdId;

    private String name;
    private String location;
    private String address;
    private String phoneNumber;

    // UI display key for the meeting-place slot. The FEMA/Red Cross doctrine
    // type that readiness evaluates lives in meetingTier below.
    @Column(nullable = true)
    private String tierKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "meeting_tier", nullable = false, length = 32)
    private MeetingPlaceTier meetingTier = MeetingPlaceTier.OTHER;

    // Replace @Lob with a standard String
    @Column(length = 2048) // Optional: Set max length to a reasonable value
    private String additionalInfo;

    @Column(nullable = true)
    private Double lat;

    @Column(nullable = true)
    private Double lng;

    private boolean deploy;



    // Getters and Setters
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

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getTierKey() {
        return tierKey;
    }

    public void setTierKey(String tierKey) {
        this.tierKey = tierKey;
    }

    public MeetingPlaceTier getMeetingTier() {
        return meetingTier;
    }

    public void setMeetingTier(MeetingPlaceTier meetingTier) {
        this.meetingTier = meetingTier == null ? MeetingPlaceTier.OTHER : meetingTier;
    }

    public String getAdditionalInfo() {
        return additionalInfo;
    }

    public void setAdditionalInfo(String additionalInfo) {
        this.additionalInfo = additionalInfo;
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

    public boolean isDeploy() {
        return deploy;
    }

    public void setDeploy(boolean deploy) {
        this.deploy = deploy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeetingPlace)) return false;
        MeetingPlace that = (MeetingPlace) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
