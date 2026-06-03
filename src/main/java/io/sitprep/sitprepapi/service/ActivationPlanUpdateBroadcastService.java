package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationPlanUpdatedFrame;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class ActivationPlanUpdateBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(ActivationPlanUpdateBroadcastService.class);

    private final PlanActivationRepo activationRepo;
    private final WebSocketMessageSender ws;

    public ActivationPlanUpdateBroadcastService(PlanActivationRepo activationRepo,
                                                WebSocketMessageSender ws) {
        this.activationRepo = activationRepo;
        this.ws = ws;
    }

    public void broadcastOwnerPlanChangedAfterCommit(String ownerEmail, String resourceKind) {
        String email = normalize(ownerEmail);
        String kind = normalizeKind(resourceKind);
        if (email == null || kind == null) return;

        Instant updatedAt = Instant.now();
        List<ActivationPlanUpdatedFrame> frames = activationRepo
                .findActiveByOwnerEmail(email, updatedAt)
                .stream()
                .map(a -> toFrame(a, kind, updatedAt))
                .toList();
        if (frames.isEmpty()) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("Skipped activation plan WS broadcast outside transaction owner={} kind={}", email, kind);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (ActivationPlanUpdatedFrame frame : frames) {
                    try {
                        ws.sendActivationPlanUpdate(frame.activationId(), frame);
                    } catch (Exception e) {
                        log.error("WS broadcast failed for activation plan update activationId={} kind={}",
                                frame.activationId(), kind, e);
                    }
                }
            }
        });
    }

    private static ActivationPlanUpdatedFrame toFrame(
            PlanActivation activation,
            String resourceKind,
            Instant updatedAt
    ) {
        return new ActivationPlanUpdatedFrame(
                "plan-updated",
                activation.getId(),
                resourceKind,
                updatedAt.toEpochMilli(),
                updatedAt
        );
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String normalizeKind(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
