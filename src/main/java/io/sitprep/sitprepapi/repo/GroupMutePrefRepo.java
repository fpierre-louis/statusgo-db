package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.GroupMutePref;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupMutePrefRepo extends JpaRepository<GroupMutePref, Long> {

    /**
     * All mute prefs for a user — used by MeService to batch-load
     * one query then look up per-group state in memory while
     * building circle summaries (mirrors the GroupReadState fetch).
     */
    List<GroupMutePref> findByUserEmailIgnoreCase(String userEmail);

    /**
     * Single mute pref for the (user, group) pair — used by the
     * mute upsert endpoint AND by the notification-dispatch
     * enforcement check.
     */
    Optional<GroupMutePref> findByUserEmailIgnoreCaseAndGroupId(String userEmail, String groupId);
}
