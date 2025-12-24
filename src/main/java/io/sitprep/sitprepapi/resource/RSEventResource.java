// src/main/java/io/sitprep/sitprepapi/resource/RSEventResource.java
package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.RSEventDto;
import io.sitprep.sitprepapi.dto.RSEventUpsertRequest;
import io.sitprep.sitprepapi.service.RSEventService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rs/events")
public class RSEventResource {

    private final RSEventService service;

    public RSEventResource(RSEventService service) {
        this.service = service;
    }

    // --------------------------
    // READS
    // --------------------------

    @GetMapping("/group/{groupId}")
    public List<RSEventDto> byGroup(@PathVariable String groupId,
                                    @RequestParam(required = false) String email) {
        return service.getEventsForGroup(groupId, email);
    }

    @GetMapping("/feed")
    public List<RSEventDto> feed(@RequestParam(required = false) String email,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false)
                                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(required = false) Integer limit) {
        return service.getFeed(email, from, to, limit);
    }

    @GetMapping("/all")
    public List<RSEventDto> all(@RequestParam(required = false) Integer limit) {
        return service.getAll(limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RSEventDto> getById(@PathVariable String id,
                                              @RequestParam(required = false) String email) {
        return service.getById(id, email)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // --------------------------
    // WRITES
    // --------------------------

    @PostMapping
    public RSEventDto create(@RequestBody RSEventUpsertRequest req,
                             @RequestParam(required = false) String email) {
        return service.createEvent(req, email);
    }

    @PutMapping("/{id}")
    public RSEventDto update(@PathVariable String id,
                             @RequestBody RSEventUpsertRequest req,
                             @RequestParam(required = false) String email) {
        return service.updateEvent(id, req, email);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
                                       @RequestParam(required = false) String email) {
        service.deleteEvent(id, email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/attend/toggle")
    public RSEventDto toggleAttend(@PathVariable String id,
                                   @RequestParam(required = false) String email,
                                   @RequestParam(required = false) String attendeeEmail) {
        return service.toggleAttend(id, email, attendeeEmail);
    }

    // --------------------------
    // ✅ JOIN REQUEST MODERATION
    // --------------------------

    @PostMapping("/{id}/requests/approve")
    public RSEventDto approveJoin(@PathVariable String id,
                                  @RequestBody Map<String, String> body,
                                  @RequestParam(required = false) String email) {
        return service.approveJoinRequest(id, body.get("email"), email);
    }

    @PostMapping("/{id}/requests/reject")
    public RSEventDto rejectJoin(@PathVariable String id,
                                 @RequestBody Map<String, String> body,
                                 @RequestParam(required = false) String email) {
        return service.rejectJoinRequest(id, body.get("email"), email);
    }

    /**
     * Map our service-layer guard rails to proper HTTP status codes:
     * - "Unauthorized" / "Email required" -> 401
     * - "FORBIDDEN" / "PRIVATE_GROUP" -> 403
     * - everything else -> 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage();

        if ("FORBIDDEN".equalsIgnoreCase(msg) || "PRIVATE_GROUP".equalsIgnoreCase(msg)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(msg);
        }

        if ("UNAUTHORIZED".equalsIgnoreCase(msg)
                || "Unauthorized".equalsIgnoreCase(msg)
                || "Email required".equalsIgnoreCase(msg)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(msg);
        }

        return ResponseEntity.badRequest().body(msg);
    }
}