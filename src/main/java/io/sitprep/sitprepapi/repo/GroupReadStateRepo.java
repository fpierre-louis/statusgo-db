package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupReadState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupReadStateRepo extends JpaRepository<GroupReadState, Long> {

    /**
     * All read-pointers for a user — used by MeService to batch-load
     * one query then look up per-group thresholds in memory while
     * building circle summaries.
     */
    List<GroupReadState> findByUserEmailIgnoreCase(String userEmail);

    /**
     * Single read-pointer for the (user, group) pair — used by the
     * mark-read endpoint to upsert.
     */
    Optional<GroupReadState> findByUserEmailIgnoreCaseAndGroupId(String userEmail, String groupId);

    /**
     * Group-wide read pointers used to hydrate per-message read receipts
     * in the chat feed without one query per post.
     */
    List<GroupReadState> findByGroupId(String groupId);
}
