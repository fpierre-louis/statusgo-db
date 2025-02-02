package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
public class EvacuationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerEmail;
    private String name;
    private String origin;
    private String destination;
    private boolean deploy;

    // Shelter Details
    private String shelterName;
    private String shelterAddress;
    private String shelterPhoneNumber;

    // Coordinates
    @Column(nullable = true)
    private Double lat;

    @Column(nullable = true)
    private Double lng;

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
