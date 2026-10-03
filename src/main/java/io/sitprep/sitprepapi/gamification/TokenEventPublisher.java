package io.sitprep.sitprepapi.gamification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private final TokenEvaluationService evaluator;

    public TokenEventPublisher(TokenEvaluationService evaluator) {
        this.evaluator = evaluator;
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
            evaluator.evaluateAsync(event);
        } catch (Exception e) {
            log.warn("token event not dispatched ({}): {}", event.type(), e.toString());
        }
    }
}
