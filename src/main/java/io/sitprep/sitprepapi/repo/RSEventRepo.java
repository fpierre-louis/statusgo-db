package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.RSEvent;
import io.sitprep.sitprepapi.domain.RSEventVisibility;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RSEventRepo extends JpaRepository<RSEvent, String> {

    @Query("""
        select e
        from RSEvent e
        order by coalesce(e.startsAt, e.createdAt) desc
    """)
    List<RSEvent> findAllLatest(Pageable pageable);

    @Query("""
        select distinct e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.groupId = :groupId
        order by e.startsAt asc
    """)
    List<RSEvent> findByGroupIdHydrated(@Param("groupId") String groupId);

    @Query("""
        select distinct e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.id = :id
    """)
    Optional<RSEvent> findByIdHydrated(@Param("id") String id);

    @Query("""
        select distinct e
        from RSEvent e
        left join fetch e.createdByUserInfo
        where e.id in :ids
    """)
    List<RSEvent> findByIdInHydrated(@Param("ids") Collection<String> ids);

    @Query("""
        select e
        from RSEvent e
        where e.visibility = :visibility
          and e.startsAt >= :from
          and e.startsAt <= :to
        order by e.startsAt asc
    """)
    List<RSEvent> findLightByVisibilityBetween(@Param("visibility") RSEventVisibility visibility,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to,
                                               Pageable pageable);

    @Query("""
        select e
        from RSEvent e
        where e.groupId in :groupIds
          and e.startsAt >= :from
          and e.startsAt <= :to
        order by e.startsAt asc
    """)
    List<RSEvent> findLightByGroupIdsBetween(@Param("groupIds") List<String> groupIds,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to,
                                             Pageable pageable);

    // ✅ NEW: include events created by the viewer (standalone + group)
    @Query("""
        select e
        from RSEvent e
        where lower(e.createdByEmail) = lower(:email)
          and e.startsAt >= :from
          and e.startsAt <= :to
        order by e.startsAt asc
    """)
    List<RSEvent> findLightByCreatorBetween(@Param("email") String email,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            Pageable pageable);
}