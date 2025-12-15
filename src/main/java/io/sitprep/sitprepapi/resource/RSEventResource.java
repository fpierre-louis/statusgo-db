package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.RSEvent;
import io.sitprep.sitprepapi.dto.RSEventDto;
import io.sitprep.sitprepapi.service.RSEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rs/events")
@CrossOrigin(origins = "http://localhost:3000")
public class RSEventResource {

    private final RSEventService service;

    public RSEventResource(RSEventService service) {
        this.service = service;
    }

    @GetMapping("/group/{groupId}")
    public List<RSEventDto> byGroup(@PathVariable String groupId) {
        return service.getEventsForGroup(groupId);
    }

    // Uses auth email if present; otherwise supports ?email= for MVP testing
    @GetMapping("/feed")
    public List<RSEventDto> feed(@RequestParam(value = "email", required = false) String email) {
        return service.getFeed(email);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RSEventDto> getById(@PathVariable String id) {
        return service.getById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<RSEventDto> create(@RequestBody RSEvent incoming,
                                             @RequestParam(value = "email", required = false) String email) {
        return ResponseEntity.ok(service.createEvent(incoming, email));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RSEventDto> update(@PathVariable String id,
                                             @RequestBody RSEvent incoming,
                                             @RequestParam(value = "email", required = false) String email) {
        return ResponseEntity.ok(service.updateEvent(id, incoming, email));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestParam(value = "email", required = false) String email) {
        service.deleteEvent(id, email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/attend/toggle")
    public ResponseEntity<RSEventDto> toggleAttend(@PathVariable String id,
                                                   @RequestParam(value = "email", required = false) String email,
                                                   @RequestParam(value = "attendeeEmail", required = false) String attendeeEmail) {
        return ResponseEntity.ok(service.toggleAttend(id, email, attendeeEmail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}