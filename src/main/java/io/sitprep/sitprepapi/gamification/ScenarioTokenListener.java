package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.practice.ScenarioCompleted;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Practice announces a finished scenario; this hands it to the token system
 * (EXEC-I4). Runs on the request thread inside the completing transaction, so
 * {@link TokenEventPublisher#household} reads the actor here and evaluates only
 * after commit — a completion that rolls back earns nothing.
 */
@Component
public class ScenarioTokenListener {

    private final TokenEventPublisher tokenEvents;

    public ScenarioTokenListener(TokenEventPublisher tokenEvents) {
        this.tokenEvents = tokenEvents;
    }

    @EventListener
    public void on(ScenarioCompleted event) {
        if (event == null) return;
        tokenEvents.household(TokenEventType.SCENARIO_COMPLETED, event.householdId(), event.runId());
    }
}
