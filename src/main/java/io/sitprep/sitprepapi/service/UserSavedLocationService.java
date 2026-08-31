package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.UserSavedLocationWriteDto;
import io.sitprep.sitprepapi.util.GeoUtil;
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

    /**
     * Lookup a saved location by id. Used by the resource layer for
     * ownership checks before mutating: fetch, compare owner to the
     * verified caller email, then call {@link #update} / {@link #delete}.
     */
    @Transactional(readOnly = true)
    public Optional<UserSavedLocation> findById(Long id) {
        return id == null ? Optional.empty() : repo.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<UserSavedLocation> homeFor(String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) return Optional.empty();
        return repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(ownerEmail.trim().toLowerCase());
    }

    @Transactional
    public UserSavedLocation create(String ownerEmail, UserSavedLocationWriteDto in) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            throw new IllegalArgumentException("ownerEmail is required");
        }
        UserSavedLocation incoming = in.toNewEntity(ownerEmail.trim().toLowerCase());

        // Coords are NOT NULL columns on this entity — require a valid pair.
        if (!GeoUtil.validLatLng(incoming.getLatitude(), incoming.getLongitude())) {
            throw new IllegalArgumentException(
                    "latitude must be within [-90, 90] and longitude within [-180, 180]");
        }

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
    public UserSavedLocation update(Long id, UserSavedLocationWriteDto in) {
        UserSavedLocation existing = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Saved location not found: " + id));

        // Owner immutability is now STRUCTURAL rather than guarded: the write
        // DTO carries no owner field, so there is nothing to tamper with. The
        // resource still checks ownership before calling in.

        GeoUtil.requireValidLatLng(in.latitude(), in.longitude());
        boolean coordsChanged = in.latitude() != null
                && in.longitude() != null
                && (!in.latitude().equals(existing.getLatitude())
                    || !in.longitude().equals(existing.getLongitude()));

        if (in.name() != null) existing.setName(in.name());
        if (in.address() != null) existing.setAddress(in.address());
        if (in.latitude() != null) existing.setLatitude(in.latitude());
        if (in.longitude() != null) existing.setLongitude(in.longitude());

        // ── THE FLAG ONLY MOVES WHEN THE CALLER ASKED ─────────────────────
        //
        // Every field above is partial — absent means unchanged — and the home
        // flag has to obey the same contract, or renaming a location would
        // silently demote it. That is why the DTO's `isHome` is a nullable
        // Boolean and the entity's primitive could not be reused: a primitive
        // arrives as `false` when omitted, which reads as an explicit demotion.
        //
        // The old code had exactly that shape and never fired it, because the
        // flag never bound at all. Fixing the binding without this guard would
        // have turned a dead branch into a live bug.
        if (in.touchesHome()) {
            boolean wantsHome = Boolean.TRUE.equals(in.isHome());
            if (wantsHome && !existing.isHome()) {
                repo.findFirstByOwnerEmailIgnoreCaseAndIsHomeTrue(existing.getOwnerEmail())
                        .filter(prior -> !prior.getId().equals(existing.getId()))
                        .ifPresent(prior -> {
                            prior.setHome(false);
                            repo.save(prior);
                        });
                existing.setHome(true);
            } else if (!wantsHome && existing.isHome()) {
                // Demoting current home is allowed; user can have zero homes.
                existing.setHome(false);
            }
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
