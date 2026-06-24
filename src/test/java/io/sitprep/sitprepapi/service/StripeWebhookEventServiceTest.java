package io.sitprep.sitprepapi.service;

import com.stripe.model.Event;
import io.sitprep.sitprepapi.domain.StripeWebhookEvent;
import io.sitprep.sitprepapi.repo.StripeWebhookEventRepo;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripeWebhookEventServiceTest {

    @Test
    void terminalReceiptIsReturnedAsDuplicate() {
        StripeWebhookEvent existing = row("evt_123", StripeWebhookEventService.PROCESSED, Instant.now());
        StripeWebhookEventRepo repo = repo((method, args) -> {
            if (method.equals("insertIfAbsent")) return 0;
            if (method.equals("findForUpdateByStripeEventId")) return Optional.of(existing);
            if (method.equals("save")) throw new AssertionError("duplicate must not be saved");
            return null;
        });

        Event event = new Event();
        event.setId("evt_123");
        event.setType("customer.subscription.updated");
        event.setLivemode(true);

        var result = new StripeWebhookEventService(repo).begin(event);

        assertTrue(result.duplicate());
        assertEquals(existing, result.event());
    }

    @Test
    void newestFailureMakesWebhookHealthFailing() {
        Instant now = Instant.now();
        StripeWebhookEvent success = row("evt_ok", StripeWebhookEventService.PROCESSED,
                now.minusSeconds(120));
        StripeWebhookEvent failure = row("evt_fail", StripeWebhookEventService.FAILED, now);
        StripeWebhookEventRepo repo = repo((method, args) -> switch (method) {
            case "findAllByOrderByReceivedAtDesc" -> List.of(failure);
            case "findFirstByStatusInOrderByProcessedAtDesc" -> Optional.of(success);
            case "findFirstByStatusOrderByProcessedAtDesc" -> Optional.of(failure);
            case "countByStatusAndReceivedAtAfter" -> 0L;
            default -> null;
        });
        StripeWebhookEventService service = new StripeWebhookEventService(repo);

        var health = service.health(true);

        assertEquals("FAILING", health.status());
        assertEquals(now, health.lastFailedAt());
    }

    private static StripeWebhookEvent row(String id, String status, Instant at) {
        StripeWebhookEvent row = new StripeWebhookEvent();
        row.setStripeEventId(id);
        row.setEventType("test.event");
        row.setStatus(status);
        row.setReceivedAt(at);
        row.setProcessedAt(at);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static StripeWebhookEventRepo repo(BiFunction<String, Object[], Object> handler) {
        return (StripeWebhookEventRepo) Proxy.newProxyInstance(
                StripeWebhookEventRepo.class.getClassLoader(),
                new Class<?>[]{StripeWebhookEventRepo.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "StripeWebhookEventRepoTestProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    Object value = handler.apply(method.getName(), args);
                    if (value != null) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }
}
