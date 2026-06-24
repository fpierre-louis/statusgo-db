package io.sitprep.sitprepapi.service;

import com.stripe.model.Event;
import io.sitprep.sitprepapi.domain.StripeWebhookEvent;
import io.sitprep.sitprepapi.dto.AdminBillingOperationsDto;
import io.sitprep.sitprepapi.dto.StripeWebhookEventDto;
import io.sitprep.sitprepapi.repo.StripeWebhookEventRepo;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
public class StripeWebhookEventService {

    public static final String RECEIVED = "RECEIVED";
    public static final String PROCESSING = "PROCESSING";
    public static final String PROCESSED = "PROCESSED";
    public static final String IGNORED = "IGNORED";
    public static final String FAILED = "FAILED";

    private static final Set<String> TERMINAL = Set.of(PROCESSED, IGNORED);

    private final StripeWebhookEventRepo repo;

    public StripeWebhookEventService(StripeWebhookEventRepo repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BeginResult begin(Event event) {
        Instant now = Instant.now();
        repo.insertIfAbsent(
                event.getId(),
                event.getType(),
                Boolean.TRUE.equals(event.getLivemode()),
                RECEIVED,
                now);
        StripeWebhookEvent row = repo.findForUpdateByStripeEventId(event.getId()).orElseThrow();
        boolean processingRecently = PROCESSING.equals(row.getStatus())
                && row.getProcessedAt() != null
                && row.getProcessedAt().isAfter(now.minusSeconds(300));
        if (TERMINAL.contains(row.getStatus()) || processingRecently) {
            return new BeginResult(row, true);
        }

        row.setEventType(event.getType());
        row.setLiveMode(Boolean.TRUE.equals(event.getLivemode()));
        row.setStatus(PROCESSING);
        row.setDetail(null);
        row.setProcessedAt(now);
        if (row.getReceivedAt() == null) row.setReceivedAt(now);
        return new BeginResult(repo.save(row), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String eventId, String status, String groupId, String detail) {
        StripeWebhookEvent row = repo.findByStripeEventId(eventId).orElseThrow();
        row.setStatus(status);
        row.setGroupId(trim(groupId, 64));
        row.setDetail(trim(detail, 1000));
        row.setProcessedAt(Instant.now());
        repo.save(row);
    }

    @Transactional(readOnly = true)
    public List<StripeWebhookEventDto> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return repo.findAllByOrderByReceivedAtDesc(PageRequest.of(0, bounded)).stream()
                .map(StripeWebhookEventDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminBillingOperationsDto.WebhookHealth health(boolean webhookConfigured) {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        var lastAny = repo.findAllByOrderByReceivedAtDesc(PageRequest.of(0, 1)).stream().findFirst();
        var lastSuccess = repo.findFirstByStatusInOrderByProcessedAtDesc(List.of(PROCESSED, IGNORED));
        var lastFailure = repo.findFirstByStatusOrderByProcessedAtDesc(FAILED);
        String status;
        if (!webhookConfigured) {
            status = "NOT_CONFIGURED";
        } else if (lastFailure.isPresent()
                && (lastSuccess.isEmpty()
                || lastFailure.get().getProcessedAt().isAfter(lastSuccess.get().getProcessedAt()))) {
            status = "FAILING";
        } else if (lastSuccess.isPresent()) {
            status = "HEALTHY";
        } else {
            status = "WAITING_FOR_EVENT";
        }
        return new AdminBillingOperationsDto.WebhookHealth(
                status,
                lastAny.map(StripeWebhookEvent::getReceivedAt).orElse(null),
                lastSuccess.map(StripeWebhookEvent::getProcessedAt).orElse(null),
                lastFailure.map(StripeWebhookEvent::getProcessedAt).orElse(null),
                repo.countByStatusAndReceivedAtAfter(PROCESSED, cutoff)
                        + repo.countByStatusAndReceivedAtAfter(IGNORED, cutoff),
                repo.countByStatusAndReceivedAtAfter(FAILED, cutoff)
        );
    }

    public record BeginResult(StripeWebhookEvent event, boolean duplicate) {}

    private static String trim(String raw, int max) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
