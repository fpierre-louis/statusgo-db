package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.service.MeetingPlaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/meeting-places")
@CrossOrigin(origins = "http://localhost:3000")
public class MeetingPlaceResource {

    private final MeetingPlaceService meetingPlaceService;

    public MeetingPlaceResource(MeetingPlaceService meetingPlaceService) {
        this.meetingPlaceService = meetingPlaceService;
    }

    /**
     * Bulk save or update meeting places.
     * Deletes all existing meeting places for the owner and replaces them with the provided list.
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<MeetingPlace>> saveAllMeetingPlaces(@RequestBody Map<String, Object> requestData) {
        String ownerEmail = (String) requestData.get("ownerEmail");
        List<Map<String, Object>> placesData = (List<Map<String, Object>>) requestData.get("meetingPlaces");

        if (ownerEmail == null || placesData == null || placesData.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // Convert request data into MeetingPlace objects
        List<MeetingPlace> meetingPlaces = placesData.stream().map(data -> {
            MeetingPlace place = new MeetingPlace();
            place.setOwnerEmail(ownerEmail);
            place.setName((String) data.get("name"));
            place.setLocation((String) data.get("location"));
            place.setAddress((String) data.get("address"));
            place.setPhoneNumber((String) data.get("phoneNumber"));
            place.setAdditionalInfo((String) data.get("additionalInfo"));
            place.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
            place.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
            place.setDeploy(Boolean.TRUE.equals(data.get("deploy"))); // Safe Boolean conversion
            return place;
        }).collect(Collectors.toList());

        List<MeetingPlace> savedPlaces = meetingPlaceService.saveAllMeetingPlaces(ownerEmail, meetingPlaces);
        return ResponseEntity.ok(savedPlaces);
    }

    /**
     * Update a specific meeting place by ID.
     */
    @PutMapping("/{id}")
    public ResponseEntity<MeetingPlace> updateMeetingPlace(
            @PathVariable Long id,
            @RequestBody MeetingPlace meetingPlace
    ) {
        MeetingPlace updatedPlace = meetingPlaceService.updateMeetingPlace(id, meetingPlace);
        return ResponseEntity.ok(updatedPlace);
    }

    /**
     * Retrieve all meeting places for a specific owner.
     */
    @GetMapping
    public ResponseEntity<List<MeetingPlace>> getMeetingPlacesByOwnerEmail(@RequestParam String ownerEmail) {
        List<MeetingPlace> meetingPlaces = meetingPlaceService.getMeetingPlacesByOwnerEmail(ownerEmail);
        return ResponseEntity.ok(meetingPlaces);
    }
}
