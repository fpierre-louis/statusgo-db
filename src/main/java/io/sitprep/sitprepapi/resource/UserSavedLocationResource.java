package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.service.UserSavedLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for the user's named places (Home, Work, etc.). Server-side
 * reverse-geocoding fills in city/region/state/country on save.
 *
 * Auth gate is a TODO until Phase E wires the Firebase ID token filter —
 * for now, ownerEmail is a query param and the resource trusts the caller.
 */
@RestController
@RequestMapping("/api/users/locations")
public class UserSavedLocationResource {

    private final UserSavedLocationService service;

    public UserSavedLocationResource(UserSavedLocationService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserSavedLocation> list(@RequestParam("ownerEmail") String ownerEmail) {
        return service.listFor(ownerEmail);
    }

    @GetMapping("/home")
    public ResponseEntity<UserSavedLocation> home(@RequestParam("ownerEmail") String ownerEmail) {
        Optional<UserSavedLocation> home = service.homeFor(ownerEmail);
        return home.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserSavedLocation> create(@RequestBody UserSavedLocation incoming) {
        UserSavedLocation saved = service.create(incoming);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserSavedLocation> update(
            @PathVariable Long id,
            @RequestBody UserSavedLocation incoming
    ) {
        UserSavedLocation saved = service.update(id, incoming);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
