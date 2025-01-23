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
    public ResponseEntity<MeetingPlace> saveMeetingPlace(@RequestBody MeetingPlace meetingPlace) {
        return ResponseEntity.ok(meetingPlaceService.saveMeetingPlace(meetingPlace));
    }

    @GetMapping
    public ResponseEntity<List<MeetingPlace>> getMeetingPlacesByOwnerEmail(@RequestParam String ownerEmail) {
        return ResponseEntity.ok(meetingPlaceService.getMeetingPlacesByOwnerEmail(ownerEmail));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMeetingPlace(@PathVariable Long id) {
        meetingPlaceService.deleteMeetingPlace(id);
        return ResponseEntity.noContent().build();
    }
}
