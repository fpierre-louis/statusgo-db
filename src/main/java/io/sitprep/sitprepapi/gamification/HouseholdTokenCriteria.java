package io.sitprep.sitprepapi.gamification;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which household tokens a household's records satisfy right now. Filled in by
 * T3; until then nothing is met.
 */
@Component
public class HouseholdTokenCriteria {

    /** Household tokens whose criteria hold now, excluding the composite {@code HOUSEHOLD_READY}. */
    public Set<TokenKey> met(String householdId) {
        return EnumSet.noneOf(TokenKey.class);
    }
}
