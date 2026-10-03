package io.sitprep.sitprepapi.gamification;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns an individual event into the awards it earns, after re-reading the
 * record it names. Filled in by T4; until then it earns nothing.
 */
@Component
public class UserTokenCriteria {

    /** One award an event earns — {@code email} may be someone other than the actor (an accepted answer's author). */
    public record Candidate(String email, TokenKey key, String sourceType, String sourceId, Map<String, Object> metadata) {}

    public List<Candidate> candidates(TokenEvent event) {
        return List.of();
    }
}
