// src/main/java/io/sitprep/sitprepapi/dto/PublicPlanResponse.java
package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PublicPlanResponse {

    private UserSummary user;
    private List<MeetingPlaceDTO> meetingPlaces = new ArrayList<>();
    private List<EvacuationPlanDTO> evacuationPlans = new ArrayList<>();
    private List<OriginLocationDTO> originLocations = new ArrayList<>();
    private List<EmergencyContactGroupDTO> emergencyContactGroups = new ArrayList<>();

    public UserSummary getUser() { return user; }
    public void setUser(UserSummary user) { this.user = user; }

    public List<MeetingPlaceDTO> getMeetingPlaces() { return meetingPlaces; }
    public void setMeetingPlaces(List<MeetingPlaceDTO> meetingPlaces) { this.meetingPlaces = meetingPlaces; }

    public List<EvacuationPlanDTO> getEvacuationPlans() { return evacuationPlans; }
    public void setEvacuationPlans(List<EvacuationPlanDTO> evacuationPlans) { this.evacuationPlans = evacuationPlans; }

    public List<OriginLocationDTO> getOriginLocations() { return originLocations; }
    public void setOriginLocations(List<OriginLocationDTO> originLocations) { this.originLocations = originLocations; }

    public List<EmergencyContactGroupDTO> getEmergencyContactGroups() { return emergencyContactGroups; }
    public void setEmergencyContactGroups(List<EmergencyContactGroupDTO> emergencyContactGroups) { this.emergencyContactGroups = emergencyContactGroups; }

    // ---- nested DTOs ----
    public static class UserSummary {
        private String firstName;
        private String lastName;
        private String phone;
        private Instant lastUpdated;

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public Instant getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    }

    public static class MeetingPlaceDTO {
        private Long id;
        private String name;
        private String address;
        private String phoneNumber;
        private String additionalInfo;
        private Double lat;
        private Double lng;
        private boolean deploy; // ✅ include deploy

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public String getAdditionalInfo() { return additionalInfo; }
        public void setAdditionalInfo(String additionalInfo) { this.additionalInfo = additionalInfo; }
        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }
        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
        public boolean isDeploy() { return deploy; }
        public void setDeploy(boolean deploy) { this.deploy = deploy; }
    }

    public static class EvacuationPlanDTO {
        private Long id;
        private String shelterName;
        private String shelterAddress;
        private String shelterPhoneNumber;
        private Double lat;
        private Double lng;
        private String travelMode;
        private String shelterInfo;
        private boolean deploy; // ✅ include deploy

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getShelterName() { return shelterName; }
        public void setShelterName(String shelterName) { this.shelterName = shelterName; }
        public String getShelterAddress() { return shelterAddress; }
        public void setShelterAddress(String shelterAddress) { this.shelterAddress = shelterAddress; }
        public String getShelterPhoneNumber() { return shelterPhoneNumber; }
        public void setShelterPhoneNumber(String shelterPhoneNumber) { this.shelterPhoneNumber = shelterPhoneNumber; }
        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }
        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
        public String getTravelMode() { return travelMode; }
        public void setTravelMode(String travelMode) { this.travelMode = travelMode; }
        public String getShelterInfo() { return shelterInfo; }
        public void setShelterInfo(String shelterInfo) { this.shelterInfo = shelterInfo; }
        public boolean isDeploy() { return deploy; }
        public void setDeploy(boolean deploy) { this.deploy = deploy; }
    }

    public static class OriginLocationDTO {
        private Long id;
        private String name;
        private String address;
        private Double lat;
        private Double lng;

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
    }

    public static class EmergencyContactDTO {
        private Long id;
        private String name;
        private String role;
        private String phone;
        private String email;
        private String address;
        private String radioChannel;
        private String medicalInfo;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getRadioChannel() { return radioChannel; }
        public void setRadioChannel(String radioChannel) { this.radioChannel = radioChannel; }
        public String getMedicalInfo() { return medicalInfo; }
        public void setMedicalInfo(String medicalInfo) { this.medicalInfo = medicalInfo; }
    }

    public static class EmergencyContactGroupDTO {
        private Long id;
        private String name;
        private List<EmergencyContactDTO> contacts = new ArrayList<>();

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<EmergencyContactDTO> getContacts() { return contacts; }
        public void setContacts(List<EmergencyContactDTO> contacts) { this.contacts = contacts; }
    }
}
