package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MeetingPlaceRepo extends JpaRepository<MeetingPlace, Long> {
    List<MeetingPlace> findByOwnerEmail(String ownerEmail);
}
