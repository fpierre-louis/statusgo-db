package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MeetingPlace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MeetingPlaceRepo extends JpaRepository<MeetingPlace, Long> {
    List<MeetingPlace> findByOwnerEmail(String ownerEmail);

    @Transactional
    void deleteByOwnerEmail(String ownerEmail); // Delete all meeting places for a user
}
