package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.util.OwnershipValidator;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OriginLocationService {

    private final OriginLocationRepo repo;

    public OriginLocationService(OriginLocationRepo repo) {
        this.repo = repo;
    }

    public List<OriginLocation> getCurrentUserOrigins() {
        String email = AuthUtils.getCurrentUserEmail();
        return repo.findByOwnerEmailIgnoreCase(email);
    }

    public OriginLocation save(OriginLocation origin) {
        String email = AuthUtils.getCurrentUserEmail();
        origin.setOwnerEmail(email);
        return repo.save(origin);
    }

    public OriginLocation update(Long id, OriginLocation origin) {
        OriginLocation existing = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Origin not found"));

        OwnershipValidator.requireOwnerEmailMatch(existing.getOwnerEmail());

        origin.setId(id);
        origin.setOwnerEmail(existing.getOwnerEmail());
        return repo.save(origin);
    }

    public void delete(Long id) {
        OriginLocation existing = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("Origin not found"));

        OwnershipValidator.requireOwnerEmailMatch(existing.getOwnerEmail());
        repo.deleteById(id);
    }

    public List<OriginLocation> saveAllForCurrentUser(List<OriginLocation> origins) {
        String email = AuthUtils.getCurrentUserEmail();

        // Clear all previous origins
        repo.deleteAll(repo.findByOwnerEmailIgnoreCase(email));

        // Assign ownership to each new origin
        origins.forEach(origin -> origin.setOwnerEmail(email));
        return repo.saveAll(origins);
    }

    public List<OriginLocation> saveAll(String ownerEmail, List<OriginLocation> origins) {
        repo.deleteAll(repo.findByOwnerEmailIgnoreCase(ownerEmail));
        origins.forEach(origin -> origin.setOwnerEmail(ownerEmail));
        return repo.saveAll(origins);
    }

    public List<OriginLocation> getByOwnerEmail(String ownerEmail) {
        return repo.findByOwnerEmailIgnoreCase(ownerEmail);
    }

}
