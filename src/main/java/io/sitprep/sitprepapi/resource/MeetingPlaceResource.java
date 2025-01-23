package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.service.MeetingPlaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/meeting-places")
@CrossOrigin(origins = "http://localhost:3000")
public class MeetingPlaceResource {

    private final MeetingPlaceService meetingPlaceService;

    public MeetingPlaceResource(MeetingPlaceService meetingPlaceService) {
        this.meetingPlaceService = meetingPlaceService;
    }

    @PostMapping
    public ResponseEntity<MeetingPlace> createMeetingPlace(@RequestBody MeetingPlace meetingPlace) {
        MeetingPlace savedPlace = meetingPlaceService.saveMeetingPlace(meetingPlace);
        return ResponseEntity.ok(savedPlace);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MeetingPlace> updateMeetingPlace(
            @PathVariable Long id,
            @RequestBody MeetingPlace meetingPlace
    ) {
        MeetingPlace updatedPlace = meetingPlaceService.updateMeetingPlace(id, meetingPlace);
        return ResponseEntity.ok(updatedPlace);
    }

    @GetMapping
    public ResponseEntity<List<MeetingPlace>> getMeetingPlacesByOwnerEmail(@RequestParam String ownerEmail) {
        return ResponseEntity.ok(meetingPlaceService.getMeetingPlacesByOwnerEmail(ownerEmail));
    }
}
