package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.LiveLocationPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LiveLocationPointRepo extends JpaRepository<LiveLocationPoint, Long> {

    Optional<LiveLocationPoint> findTopBySessionIdOrderByCapturedAtDesc(String sessionId);
}
