package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationLogRepo extends JpaRepository<NotificationLog, Long> {

    List<NotificationLog> findByRecipientEmailAndTimestampAfterOrderByTimestampAsc(
            String recipientEmail, Instant since
    );

    List<NotificationLog> findByRecipientEmailAndTypeAndTimestampAfterOrderByTimestampAsc(
            String recipientEmail, String type, Instant since
    );
}
