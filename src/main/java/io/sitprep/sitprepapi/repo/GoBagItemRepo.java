package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GoBagItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface GoBagItemRepo extends JpaRepository<GoBagItem, String> {

    List<GoBagItem> findByBagIdOrderByPriorityAscCreatedAtAsc(String bagId);

    List<GoBagItem> findByBagIdIn(Collection<String> bagIds);

    /**
     * Items due for a rotation reminder: dated, inside the warning
     * horizon, not yet reminded. Oldest-expiring first so a catch-up
     * backlog drains in urgency order. Hits the partial index
     * {@code idx_go_bag_item_expiry}.
     */
    @Query("""
            SELECT i FROM GoBagItem i
            WHERE i.expiresOn IS NOT NULL
              AND i.expiresOn <= :horizon
              AND i.reminderSentAt IS NULL
            ORDER BY i.expiresOn ASC
            """)
    List<GoBagItem> findDueForExpiryReminder(@Param("horizon") LocalDate horizon, Pageable page);

    @Modifying
    @Query("DELETE FROM GoBagItem i WHERE i.bagId = :bagId")
    void deleteByBagId(@Param("bagId") String bagId);
}
