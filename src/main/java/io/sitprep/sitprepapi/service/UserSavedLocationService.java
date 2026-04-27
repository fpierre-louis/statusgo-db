package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import io.sitprep.sitprepapi.service.NominatimGeocodeService.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CRUD + geocoding for user-named places. Save is async-friendly: we
 * persist what the client sent first, then enrich with the reverse-
 * geocoded city/region/state/country/zipBucket.
 */
@Service
public class UserSavedLocationService {

    private static final Logger log = LoggerFactory.getLogger(UserSavedLocationService.class);

    private final UserSavedLocationRepo repo;
    private final NominatimGeocodeService geocode;

    public UserSavedLocationService(UserSavedLocationRepo repo, NominatimGeocodeService geocode) {
        this.repo = repo;
        this.geocode = geocode;
    }

    @Transactional(readOnly = true)
    public List<UserSavedLocation> listFor(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) return List.of();
        return repo.findByOwnerEmailIgnoreCaseOrderByIsHomeDescNameAsc(ownerEmail.trim().toLowerCase());
    }

    @Transactional(readOnly = true)
    public Optional<UserSavedLocation> homeFor(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) return Optional.empty();
        return repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(ownerEmail.trim().toLowerCase());
    }

    @Transactional
    public UserSavedLocation create(UserSavedLocation incoming) {
        if (incoming.getOwnerEmail() == null || incoming.getOwnerEmail().isBlank()) {
            throw new IllegalArgumentException("ownerEmail is required");
        }
        incoming.setOwnerEmail(incoming.getOwnerEmail().trim().toLowerCase());

        // Enforce single-home invariant: if this row claims home, demote any prior home.
        if (incoming.isHome()) {
            repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(incoming.getOwnerEmail())
                    .ifPresent(prior -> {
                        prior.setHome(false);
                        repo.save(prior);
                    });
        }

        applyReverseGeocode(incoming);
        return repo.save(incoming);
    }

    @Transactional
    public UserSavedLocation update(Long id, UserSavedLocation incoming) {
        UserSavedLocation existing = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Saved location not found: " + id));

        // Owner is immutable — guard against payload tampering.
        if (incoming.getOwnerEmail() != null
                && !incoming.getOwnerEmail().equalsIgnoreCase(existing.getOwnerEmail())) {
            throw new SecurityException("Cannot reassign saved location to a different owner.");
        }

        boolean coordsChanged = incoming.getLatitude() != null
                && incoming.getLongitude() != null
                && (!incoming.getLatitude().equals(existing.getLatitude())
                    || !incoming.getLongitude().equals(existing.getLongitude()));

        if (incoming.getName() != null) existing.setName(incoming.getName());
        if (incoming.getAddress() != null) existing.setAddress(incoming.getAddress());
        if (incoming.getLatitude() != null) existing.setLatitude(incoming.getLatitude());
        if (incoming.getLongitude() != null) existing.setLongitude(incoming.getLongitude());

        // Promotion to home → demote any prior home for this user.
        if (incoming.isHome() && !existing.isHome()) {
            repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(existing.getOwnerEmail())
                    .filter(prior -> !prior.getId().equals(existing.getId()))
                    .ifPresent(prior -> {
                        prior.setHome(false);
                        repo.save(prior);
                    });
            existing.setHome(true);
        } else if (!incoming.isHome() && existing.isHome()) {
            // Demoting current home is allowed; user can have zero homes.
            existing.setHome(false);
        }

        if (coordsChanged) {
            applyReverseGeocode(existing);
        }

        return repo.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    /**
     * Best-effort reverse-geocode. Failures don't block the save — we keep
     * whatever city/region/state was set (or null) on the row.
     */
    private void applyReverseGeocode(UserSavedLocation row) {
        if (row.getLatitude() == null || row.getLongitude() == null) return;
        try {
            Place place = geocode.reverse(row.getLatitude(), row.getLongitude());
            if (place == null) return;
            row.setCity(place.city());
            row.setRegion(place.region());
            row.setState(place.state());
            row.setCountry(place.country());
            row.setZipBucket(place.zipBucket());
        } catch (Exception e) {
            log.warn("Reverse-geocode failed for saved location id={}: {}", row.getId(), e.getMessage());
        }
    }
}
