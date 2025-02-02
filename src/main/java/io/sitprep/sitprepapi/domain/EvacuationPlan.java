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

    @Column(length = 2048)
    private String shelterDetails; // Stored as JSON string

    @Column(length = 4096)
    private String directions; // Store route details as JSON

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOwnerEmail() { return ownerEmail; }
    public void setOwnerEmail(String ownerEmail) { this.ownerEmail = ownerEmail; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public boolean isDeploy() { return deploy; }
    public void setDeploy(boolean deploy) { this.deploy = deploy; }

    public String getShelterDetails() { return shelterDetails; }
    public void setShelterDetails(String shelterDetails) { this.shelterDetails = shelterDetails; }

    public String getDirections() { return directions; }
    public void setDirections(String directions) { this.directions = directions; }
}
