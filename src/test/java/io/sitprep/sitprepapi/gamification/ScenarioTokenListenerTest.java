package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.practice.ScenarioCompleted;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** EXEC-I4: a finished scenario becomes a household token event. */
class ScenarioTokenListenerTest {

    @Test
    void handsAFinishedScenarioToTheTokenSystem() {
        TokenEventPublisher publisher = mock(TokenEventPublisher.class);
        ScenarioTokenListener listener = new ScenarioTokenListener(publisher);
        listener.on(new ScenarioCompleted(7L, "hh-1", "earthquake-first-five-minutes"));
        verify(publisher).household(TokenEventType.SCENARIO_COMPLETED, "hh-1", 7L);
    }

    @Test
    void ignoresNothing() {
        TokenEventPublisher publisher = mock(TokenEventPublisher.class);
        new ScenarioTokenListener(publisher).on(null);
        verifyNoInteractions(publisher);
    }
}
