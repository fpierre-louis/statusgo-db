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

    // Setter-injected so tests that build this service by hand stay unchanged.
    private HouseholdResolver householdResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHouseholdResolver(HouseholdResolver householdResolver) {
        this.householdResolver = householdResolver;
    }

    /**
     * A saved-plan write happened (meeting spots, destinations, origins,
     * contacts). Two signals, both after commit:
     *
     * <ul>
     *   <li>{@code /topic/households/{hid}/plan} — every member's app refetches
     *       its plan data (plan data consistency, 2026-10-21). Before this no
     *       household channel carried plan edits, so another member's phone
     *       showed the old places until a reload.</li>
     *   <li>{@code /topic/activations/{id}/plan} for the household's LIVE plans —
     *       found by HOUSEHOLD as well as by the editor's email. Keyed by email
     *       alone, an admin's edit to a plan someone else started reached no
     *       live screen. The live plan's places do not change (V116 snapshot);
     *       the frame lets the page show "your saved place changed".</li>
     * </ul>
     */
    public void broadcastOwnerPlanChangedAfterCommit(String ownerEmail, String resourceKind) {
        String email = normalize(ownerEmail);
        String kind = normalizeKind(resourceKind);
        if (email == null || kind == null) return;

        Instant updatedAt = Instant.now();
        String householdId = householdFor(email);
        java.util.Map<String, PlanActivation> live = new java.util.LinkedHashMap<>();
        activationRepo.findActiveByOwnerEmail(email, updatedAt).forEach(a -> live.put(a.getId(), a));
        if (householdId != null) {
            activationRepo.findLiveByHouseholdId(householdId, updatedAt).forEach(a -> live.put(a.getId(), a));
        }
        List<ActivationPlanUpdatedFrame> frames = live.values().stream()
                .map(a -> toFrame(a, kind, updatedAt))
                .toList();
        java.util.Map<String, Object> householdFrame = householdId == null ? null : java.util.Map.of(
                "type", "household-plan-updated",
                "householdId", householdId,
                "resourceKind", kind,
                "version", updatedAt.toEpochMilli());

        Runnable send = () -> {
            for (ActivationPlanUpdatedFrame frame : frames) {
                try {
                    ws.sendActivationPlanUpdate(frame.activationId(), frame);
                } catch (Exception e) {
                    log.error("WS broadcast failed for activation plan update activationId={} kind={}",
                            frame.activationId(), kind, e);
                }
            }
            if (householdFrame != null) {
                try {
                    ws.sendHouseholdPlan(householdId, householdFrame);
                } catch (Exception e) {
                    log.error("WS broadcast failed for household plan update household={} kind={}", householdId, kind, e);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            // Non-transactional writers (origin locations) have already committed.
            send.run();
        }
    }

    /** The household the edit landed in: the admin's target (X-Household-Id), else the base household. */
    private String householdFor(String email) {
        if (householdResolver == null) return null;
        try {
            String target = householdResolver.writableTargetHousehold(email);
            return target != null ? target : householdResolver.baseHouseholdIdFor(email);
        } catch (Exception e) {
            return null;
        }
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
