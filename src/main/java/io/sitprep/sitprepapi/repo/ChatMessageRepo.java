package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ChatMessageRepo extends JpaRepository<ChatMessage, Long> {

    /** Newest-first page. Use with {@code PageRequest.of(0, limit)} for the first page. */
    List<ChatMessage> findByGroupIdOrderByCreatedAtDescIdDesc(String groupId, Pageable pageable);

    /** Older-than cursor: fetch messages created strictly before {@code before}. */
    List<ChatMessage> findByGroupIdAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(
            String groupId, Instant before, Pageable pageable);

    /**
     * {@code since} backfill: anything modified after this instant, oldest-first
     * so the client can append in chronological order. Uses {@code updatedAt}
     * so edits surface too.
     */
    List<ChatMessage> findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(
            String groupId, Instant since);
}
