package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;

@Entity
public class OriginLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String address;
    private Double lat;
    private Double lng;

    @Column(name = "user_email", nullable = false)
    private String ownerEmail;

    public OriginLocation() {}

    public OriginLocation(String address, Double lat, Double lng, String ownerEmail) {
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.ownerEmail = ownerEmail;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
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

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }
}
