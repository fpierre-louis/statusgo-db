package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.OriginLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OriginLocationRepo extends JpaRepository<OriginLocation, Long> {
    List<OriginLocation> findByOwnerEmail(String ownerEmail);
}
