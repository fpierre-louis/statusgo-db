package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.DmMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DmMessageRepo extends JpaRepository<DmMessage, Long> {

    /** Full thread, oldest → newest (no pagination v1 — same posture as comment threads). */
    List<DmMessage> findByThreadIdOrderByCreatedAtAsc(Long threadId);

    /** Newest message — inbox row preview. */
    Optional<DmMessage> findFirstByThreadIdOrderByCreatedAtDesc(Long threadId);

    /** Unread = peer-sent messages newer than the viewer's watermark. */
    long countByThreadIdAndSenderEmailNotAndCreatedAtAfter(
            Long threadId, String senderEmail, Instant after);

    /** Unread with no watermark yet — every peer-sent message counts. */
    long countByThreadIdAndSenderEmailNot(Long threadId, String senderEmail);
}
