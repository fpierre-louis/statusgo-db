package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class OriginLocationService {

    private final OriginLocationRepo repo;

    public OriginLocationService(OriginLocationRepo repo) {
        this.repo = repo;
    }

    public List<OriginLocation> getByOwnerEmail(String ownerEmail) {
        return repo.findByOwnerEmailIgnoreCase(ownerEmail);
    }


    public OriginLocation save(OriginLocation origin) {
        return repo.save(origin);
    }

    public OriginLocation update(Long id, OriginLocation origin) {
        origin.setId(id);
        return repo.save(origin);
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    public List<OriginLocation> saveAll(String ownerEmail, List<OriginLocation> origins) {
        repo.deleteAll(repo.findByOwnerEmailIgnoreCase(ownerEmail));
        origins.forEach(origin -> origin.setOwnerEmail(ownerEmail));
        return repo.saveAll(origins);
    }
}
