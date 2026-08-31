package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.UserSavedLocationWriteDto;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.UserSavedLocationDto;
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
    public List<UserSavedLocationDto> list() {
        // Verified-email only — query param not consulted. The frontend
        // doesn't need to pass ownerEmail anymore.
        String owner = AuthUtils.requireAuthenticatedEmail();
        return service.listFor(owner).stream().map(UserSavedLocationDto::from).toList();
    }

    @GetMapping("/home")
    public ResponseEntity<UserSavedLocationDto> home() {
        String owner = AuthUtils.requireAuthenticatedEmail();
        Optional<UserSavedLocation> h = service.homeFor(owner);
        return h.map(UserSavedLocationDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── BIND A DTO, NOT THE ENTITY ────────────────────────────────────────
    //
    // These used to take `@RequestBody UserSavedLocation`. Two problems, and
    // the first one shipped: the entity's Jackson property for its flag is
    // `home` (Lombok's `isHome()` getter), while the READ dto pins `isHome` —
    // so a client echoing back what it read had the flag silently dropped, and
    // NO row could ever become home. Second, binding an entity exposed `id`,
    // `ownerEmail`, `createdAt`, `updatedAt` and the server-derived geocode
    // columns to the request body; only `ownerEmail` was defended, by hand.
    @PostMapping
    public ResponseEntity<UserSavedLocationDto> create(@RequestBody UserSavedLocationWriteDto incoming) {
        // The owner is the verified caller and is not in the DTO at all, so
        // there is no longer a field to override — it cannot be spoofed.
        String owner = AuthUtils.requireAuthenticatedEmail();
        UserSavedLocation saved = service.create(owner, incoming);
        return ResponseEntity.status(201).body(UserSavedLocationDto.from(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserSavedLocationDto> update(
            @PathVariable Long id,
            @RequestBody UserSavedLocationWriteDto incoming
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        ensureOwns(id, caller);
        UserSavedLocation saved = service.update(id, incoming);
        return ResponseEntity.ok(UserSavedLocationDto.from(saved));
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
