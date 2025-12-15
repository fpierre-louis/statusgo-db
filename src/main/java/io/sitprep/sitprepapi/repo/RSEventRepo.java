package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RSEventRepo extends JpaRepository<RSEvent, String> {

    @Query("""
        select e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.groupId = :groupId
        order by e.startsAt asc
    """)
    List<RSEvent> findByGroupIdHydrated(String groupId);

    @Query("""
        select e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.groupId in :groupIds
        order by e.startsAt asc
    """)
    List<RSEvent> findByGroupIdInHydrated(List<String> groupIds);

    @Query("""
        select e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.startsAt > :after
        order by e.startsAt asc
    """)
    List<RSEvent> findByStartsAtAfterHydrated(Instant after);

    @Query("""
        select e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.id = :id
    """)
    Optional<RSEvent> findByIdHydrated(String id);
}