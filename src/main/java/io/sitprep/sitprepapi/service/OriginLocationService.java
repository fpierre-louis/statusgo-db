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

    public List<OriginLocation> getByUserEmail(String userEmail) {
        return repo.findByUserEmail(userEmail);
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

    public List<OriginLocation> saveAll(String userEmail, List<OriginLocation> origins) {
        repo.deleteAll(repo.findByUserEmail(userEmail));
        origins.forEach(origin -> origin.setUserEmail(userEmail));
        return repo.saveAll(origins);
    }
}
