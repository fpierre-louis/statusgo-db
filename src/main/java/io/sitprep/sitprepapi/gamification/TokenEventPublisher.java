package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one call a hook site makes: {@code tokenEvents.publishAfterCommit(event)}.
 *
 * <p>Inside a transaction, evaluation is scheduled for {@code afterCommit} — an
 * action that rolls back earns nothing. Outside one, it is dispatched at once.
 * Either way it hands off to the token executor and returns.</p>
 *
 * <p><b>Never throws.</b> An exception from an {@code afterCommit} callback
 * propagates to the request after its data has already committed — the user
 * would see a 500 for a save that succeeded. Every path here is caught.</p>
 */
@Component
public class TokenEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TokenEventPublisher.class);

    /**
     * Resolved lazily. The services that publish (go bags, stockpile, posts…) are
     * also what the criteria read, so a direct constructor dependency would be a
     * cycle: GoBagService → publisher → evaluator → criteria → GoBagService.
     */
    private final Supplier<TokenEvaluationService> evaluator;

    @Autowired
    public TokenEventPublisher(ObjectProvider<TokenEvaluationService> evaluator) {
        this.evaluator = evaluator::getObject;
    }

    /** Test seam. */
    TokenEventPublisher(TokenEvaluationService evaluator) {
        this.evaluator = () -> evaluator;
    }

    /**
     * A household's plan records changed. The actor is read from the security
     * context HERE, on the request thread — the token executor's thread has none.
     */
    public void household(TokenEventType type, String householdId, Object sourceId) {
        if (householdId == null || householdId.isBlank()) return;
        try {
            publishAfterCommit(TokenEvent.household(type, AuthUtils.getCurrentUserEmail(), householdId,
                    sourceId == null ? null : String.valueOf(sourceId)));
        } catch (Exception e) {
            log.warn("token event not built ({}): {}", type, e.toString());
        }
    }

    /** {@link #household} once per distinct household among saved rows. */
    public <T> void householdsOf(TokenEventType type, Iterable<T> rows, Function<T, String> householdId) {
        if (rows == null) return;
        try {
            Set<String> ids = new LinkedHashSet<>();
            for (T row : rows) {
                String id = row == null ? null : householdId.apply(row);
                if (id != null && !id.isBlank()) ids.add(id);
            }
            ids.forEach(id -> household(type, id, null));
        } catch (Exception e) {
            log.warn("token event not built ({}): {}", type, e.toString());
        }
    }

    /** An individual action; the actor is the signed-in caller. */
    public void user(TokenEventType type, Object sourceId) {
        try {
            publishAfterCommit(TokenEvent.user(type, AuthUtils.getCurrentUserEmail(), sourceId));
        } catch (Exception e) {
            log.warn("token event not built ({}): {}", type, e.toString());
        }
    }

    public void publishAfterCommit(TokenEvent event) {
        if (event == null || event.type() == null) return;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch(event);
                    }
                });
            } else {
                dispatch(event);
            }
        } catch (Exception e) {
            log.warn("token event not scheduled ({}): {}", event.type(), e.toString());
        }
    }

    private void dispatch(TokenEvent event) {
        try {
            evaluator.get().evaluateAsync(event);
        } catch (Exception e) {
            log.warn("token event not dispatched ({}): {}", event.type(), e.toString());
        }
    }
}
