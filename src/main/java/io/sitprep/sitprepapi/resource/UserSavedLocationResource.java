package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.service.UserSavedLocationService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * CRUD for the user's named places (Home, Work, etc.). Server-side
 * reverse-geocoding fills in city/region/state/country on save.
 *
 * <p>Phase E enforcement live: every endpoint requires a verified Firebase
 * token. The body/query {@code ownerEmail} is no longer trusted — we use
 * the token-derived email as the canonical owner. Update/delete additionally
 * verify the existing record's owner matches the caller, so a signed-in
 * user can't touch another user's saved locations even by guessing ids.</p>
 */
@RestController
@RequestMapping("/api/users/locations")
public class UserSavedLocationResource {

    private final UserSavedLocationService service;

    public UserSavedLocationResource(UserSavedLocationService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserSavedLocation> list() {
        // Verified-email only — query param not consulted. The frontend
        // doesn't need to pass ownerEmail anymore.
        String owner = AuthUtils.requireAuthenticatedEmail();
        return service.listFor(owner);
    }

    @GetMapping("/home")
    public ResponseEntity<UserSavedLocation> home() {
        String owner = AuthUtils.requireAuthenticatedEmail();
        Optional<UserSavedLocation> h = service.homeFor(owner);
        return h.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserSavedLocation> create(@RequestBody UserSavedLocation incoming) {
        String owner = AuthUtils.requireAuthenticatedEmail();
        // Force ownerEmail to the verified caller. Anything in the body is
        // overridden so a signed-in attacker can't create entries under
        // another email.
        incoming.setOwnerEmail(owner);
        UserSavedLocation saved = service.create(incoming);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserSavedLocation> update(
            @PathVariable Long id,
            @RequestBody UserSavedLocation incoming
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        // Override any owner field on the body so the service's
        // "owner is immutable" guard still passes when the body omits it.
        incoming.setOwnerEmail(caller);
        UserSavedLocation saved = service.update(id, incoming);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 404 when the record doesn't exist (don't leak which ids are taken),
     * 403 when it exists but belongs to someone else.
     */
    private void ensureOwns(Long id, String caller) {
        UserSavedLocation existing = service.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (existing.getOwnerEmail() == null
                || !existing.getOwnerEmail().equalsIgnoreCase(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Saved location belongs to a different user");
        }
    }
}
