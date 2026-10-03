package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.PlanRowReconciler;
import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OriginLocationService {

    private final OriginLocationRepo repo;
    private final HouseholdResolver householdResolver;

    public OriginLocationService(OriginLocationRepo repo, HouseholdResolver householdResolver) {
        this.repo = repo;
        this.householdResolver = householdResolver;
    }

    // Get all origins for a specific user (explicit ownerEmail)
    public List<OriginLocation> getByOwnerEmail(String ownerEmail) {
        // Household-first, author-email fallback (open-items plan 3.1): read by
        // the author's email, a household member who didn't write the plan saw
        // none of it — the writes stamp householdId.
        String hid = householdResolver.baseHouseholdIdFor(ownerEmail);
        List<OriginLocation> mine = hid == null ? List.of() : repo.findByHouseholdId(hid);
        return mine.isEmpty() ? repo.findByOwnerEmailIgnoreCase(ownerEmail) : mine;
    }

    // Save a single origin for a specific user
    public OriginLocation save(String ownerEmail, OriginLocation origin) {
        GeoUtil.requireValidLatLng(origin.getLat(), origin.getLng());
        origin.setOwnerEmail(ownerEmail);
        if (origin.getHouseholdId() == null) {
            origin.setHouseholdId(householdResolver.baseHouseholdIdFor(ownerEmail));
        }
        return repo.save(origin);
    }

    // Update an existing origin for a user
    public OriginLocation update(Long id, String ownerEmail, OriginLocation origin) {
        OriginLocation existing = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Origin not found"));

        // enforce that the origin belongs to the specified user
        if (!existing.getOwnerEmail().equalsIgnoreCase(ownerEmail)) {
            throw new RuntimeException("Unauthorized: Owner email mismatch");
        }

        GeoUtil.requireValidLatLng(origin.getLat(), origin.getLng());
        // A PUT that doesn't say what the starting point is keeps what it was.
        if (origin.getKind() == null) origin.setKind(existing.getKind());
        origin.setId(id);
        origin.setOwnerEmail(ownerEmail);
        // Incoming payload has no householdId; preserve the existing row's
        // (or derive from base) so an update never nulls it out.
        origin.setHouseholdId(existing.getHouseholdId() != null
                ? existing.getHouseholdId()
                : householdResolver.baseHouseholdIdFor(ownerEmail));
        return repo.save(origin);
    }

    // Delete an origin for a user
    public void delete(Long id, String ownerEmail) {
        OriginLocation existing = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Origin not found"));

        if (!existing.getOwnerEmail().equalsIgnoreCase(ownerEmail)) {
            throw new RuntimeException("Unauthorized: Owner email mismatch");
        }

        repo.deleteById(id);
    }

    // Save all origins for a given user (replaces all)
    public List<OriginLocation> saveAll(String ownerEmail, List<OriginLocation> origins) {
        origins.forEach(o -> GeoUtil.requireValidLatLng(o.getLat(), o.getLng()));
        // Cross-household edit (X-Household-Id, admin of that household):
        // replace THAT household's origins + stamp it. Else unchanged.
        String target = householdResolver.writableTargetHousehold(ownerEmail);
        if (target != null) {
            repo.deleteAll(PlanRowReconciler.reconcile(
                    repo.findByHouseholdId(target), origins, OriginLocation::getId, OriginLocation::setId));
            origins.forEach(origin -> {
                origin.setOwnerEmail(ownerEmail);
                origin.setHouseholdId(target);
            });
            return repo.saveAll(origins);
        }
        // Against the HOUSEHOLD's rows (what the reads show), else the caller's.
        String householdId = householdResolver.baseHouseholdIdFor(ownerEmail);
        List<OriginLocation> current = householdId == null ? List.of() : repo.findByHouseholdId(householdId);
        if (current.isEmpty()) current = repo.findByOwnerEmailIgnoreCase(ownerEmail);
        repo.deleteAll(PlanRowReconciler.reconcile(
                current, origins, OriginLocation::getId, OriginLocation::setId));
        origins.forEach(origin -> {
            origin.setOwnerEmail(ownerEmail);
            if (origin.getHouseholdId() == null) origin.setHouseholdId(householdId);
        });
        return repo.saveAll(origins);
    }
}
