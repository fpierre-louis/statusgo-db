package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.MeetingPlaceDto;
import io.sitprep.sitprepapi.service.MeetingPlaceService;
import io.sitprep.sitprepapi.service.UserSavedLocationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/meeting-places")
@CrossOrigin(origins = "http://localhost:3000")
public class MeetingPlaceResource {

    private final MeetingPlaceService meetingPlaceService;
    private final UserSavedLocationService savedLocationService;

    public MeetingPlaceResource(MeetingPlaceService meetingPlaceService,
                                UserSavedLocationService savedLocationService) {
        this.meetingPlaceService = meetingPlaceService;
        this.savedLocationService = savedLocationService;
    }

    /**
     * Bulk save or update meeting places.
     * Deletes all existing meeting places for the authenticated user and replaces them with the provided list.
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<MeetingPlaceDto>> saveAllMeetingPlaces(@RequestBody Map<String, Object> requestData) {
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        List<Map<String, Object>> placesData = (List<Map<String, Object>>) requestData.get("meetingPlaces");

        if (placesData == null || placesData.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<MeetingPlace> meetingPlaces = placesData.stream().map(data -> {
            MeetingPlace place = new MeetingPlace();
            place.setOwnerEmail(ownerEmail);
            place.setName((String) data.get("name"));
            place.setLocation((String) data.get("location"));
            place.setAddress((String) data.get("address"));
            place.setPhoneNumber((String) data.get("phoneNumber"));
            place.setTierKey((String) data.get("tierKey"));
            place.setMeetingTier(parseMeetingTier(data.get("meetingTier"), place.getTierKey()));
            place.setAdditionalInfo((String) data.get("additionalInfo"));
            place.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
            place.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
            place.setDeploy(Boolean.TRUE.equals(data.get("deploy")));
            return place;
        }).collect(Collectors.toList());

        List<MeetingPlace> savedPlaces = meetingPlaceService.saveAllMeetingPlaces(ownerEmail, meetingPlaces);
        return ResponseEntity.ok(toDtos(savedPlaces, ownerEmail));
    }

    /**
     * Create a SINGLE meeting place without replacing the owner's others
     * (the bulk endpoint deletes-and-replaces). Used by the activation
     * surface's "add another spot".
     */
    @PostMapping("/one")
    public ResponseEntity<MeetingPlaceDto> addOneMeetingPlace(@RequestBody Map<String, Object> data) {
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        MeetingPlace place = new MeetingPlace();
        place.setOwnerEmail(ownerEmail);
        place.setName((String) data.get("name"));
        place.setLocation((String) data.get("location"));
        place.setAddress((String) data.get("address"));
        place.setPhoneNumber((String) data.get("phoneNumber"));
        place.setAdditionalInfo((String) data.get("additionalInfo"));
        place.setTierKey((String) data.get("tierKey"));
        place.setMeetingTier(parseMeetingTier(data.get("meetingTier"), place.getTierKey()));
        place.setLat(data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : null);
        place.setLng(data.get("lng") != null ? ((Number) data.get("lng")).doubleValue() : null);
        place.setDeploy(Boolean.TRUE.equals(data.get("deploy")));
        return ResponseEntity.ok(toDto(meetingPlaceService.addMeetingPlace(place), ownerEmail));
    }

    /**
     * Update a specific meeting place by ID.
     */
    @PutMapping("/{id}")
    public ResponseEntity<MeetingPlaceDto> updateMeetingPlace(
            @PathVariable Long id,
            @RequestBody MeetingPlace meetingPlace
    ) {
        // Owner is whoever signed the request; body's ownerEmail (if any) is overridden.
        String caller = AuthUtils.requireAuthenticatedEmail();
        meetingPlace.setOwnerEmail(caller);
        MeetingPlace updatedPlace = meetingPlaceService.updateMeetingPlace(id, meetingPlace);
        return ResponseEntity.ok(toDto(updatedPlace, caller));
    }

    /**
     * Retrieve all meeting places for the authenticated user.
     */
    @GetMapping
    public ResponseEntity<List<MeetingPlaceDto>> getMeetingPlacesByOwnerEmail() {
        String ownerEmail = AuthUtils.requireAuthenticatedEmail();
        List<MeetingPlace> meetingPlaces = meetingPlaceService.getMeetingPlacesByOwnerEmail(ownerEmail);
        return ResponseEntity.ok(toDtos(meetingPlaces, ownerEmail));
    }

    // --- DTO assembly -------------------------------------------------------
    // Resolve the caller's home saved-location ONCE per request so the
    // haversine distance / derivedTierKey can be computed for each place.

    private List<MeetingPlaceDto> toDtos(List<MeetingPlace> places, String ownerEmail) {
        double[] home = homeCoords(ownerEmail);
        return places.stream()
                .map(p -> MeetingPlaceDto.from(p, home == null ? null : home[0],
                                                  home == null ? null : home[1]))
                .toList();
    }

    private MeetingPlaceDto toDto(MeetingPlace place, String ownerEmail) {
        double[] home = homeCoords(ownerEmail);
        return MeetingPlaceDto.from(place, home == null ? null : home[0],
                                           home == null ? null : home[1]);
    }

    /** {lat, lng} of the caller's home saved-location, or null when unset. */
    private double[] homeCoords(String ownerEmail) {
        Optional<UserSavedLocation> home = savedLocationService.homeFor(ownerEmail);
        if (home.isEmpty()) return null;
        UserSavedLocation h = home.get();
        if (h.getLatitude() == null || h.getLongitude() == null) return null;
        return new double[]{h.getLatitude(), h.getLongitude()};
    }

    private static MeetingPlaceTier parseMeetingTier(Object raw, String tierKey) {
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return MeetingPlaceTier.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to legacy tierKey inference.
            }
        }
        return MeetingPlaceService.inferMeetingTier(tierKey);
    }
}
