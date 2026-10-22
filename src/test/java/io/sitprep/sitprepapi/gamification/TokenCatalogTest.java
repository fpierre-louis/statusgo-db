package io.sitprep.sitprepapi.gamification;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T1: the catalog is complete, well-formed and stable. */
class TokenCatalogTest {

    @Test
    void everyLiveKeyHasExactlyOneDefinition() {
        // Retired 2026-10-22 (Practice has no token economy): kept as keys, out of the catalog.
        Set<TokenKey> retired = EnumSet.of(TokenKey.SCENARIO_REHEARSAL, TokenKey.DRILL_CREW, TokenKey.PRACTICE_CADENCE);
        assertThat(TokenCatalog.all()).hasSize(TokenKey.values().length - retired.size()).hasSize(13);
        Set<TokenKey> seen = EnumSet.noneOf(TokenKey.class);
        TokenCatalog.all().forEach(d -> assertThat(seen.add(d.key())).as("duplicate %s", d.key()).isTrue());
        for (TokenKey k : TokenKey.values()) {
            if (retired.contains(k)) assertThat(TokenCatalog.get(k)).isNull();
            else assertThat(TokenCatalog.get(k)).isNotNull();
        }
    }

    @Test
    void everyDefinitionCarriesItsCopyAndIcon() {
        Set<String> icons = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (TokenDefinition d : TokenCatalog.all()) {
            assertThat(d.name()).isNotBlank();
            assertThat(d.description()).isNotBlank();
            assertThat(d.lockedHint()).isNotBlank();
            assertThat(d.iconKey()).isNotBlank();
            assertThat(icons.add(d.iconKey())).as("icon reused: %s", d.iconKey()).isTrue();
            assertThat(orders.add(d.order())).as("order reused: %s", d.order()).isTrue();
            if (d.nextStepRoute() != null) assertThat(d.nextStepRoute()).startsWith("/");
        }
    }

    @Test
    void approvedEverydayNamesAreTheCatalogDisplayNames() {
        Map<TokenKey, String> names = Map.ofEntries(
                Map.entry(TokenKey.PLAN_ARCHITECT, "Plan in Place"),
                Map.entry(TokenKey.MEETING_POINT, "Meeting Spot"),
                Map.entry(TokenKey.CONTACT_CIRCLE, "People to Call"),
                Map.entry(TokenKey.STOCKPILE_STEWARD, "Supplies Taking Shape"),
                Map.entry(TokenKey.HOUSEHOLD_READY, "Ready Together"),
                Map.entry(TokenKey.FIRST_NEIGHBOR_SIGNAL, "Neighbor Hello"),
                Map.entry(TokenKey.LOCAL_HAZARD_REPORTER, "Local Heads-Up"),
                Map.entry(TokenKey.GROUND_TRUTH, "Nearby Check"),
                Map.entry(TokenKey.HELPFUL_QUESTION, "First Question"),
                Map.entry(TokenKey.PREP_TIP_SHARER, "Shared a Tip"),
                Map.entry(TokenKey.ANSWERED_THE_CALL, "Neighbor Answer"),
                Map.entry(TokenKey.TRUSTED_ANSWER, "Trusted Answer"),
                Map.entry(TokenKey.HELPING_HAND, "Helping Hand")
        );

        assertThat(TokenCatalog.all())
                .extracting(TokenDefinition::key, TokenDefinition::name)
                .containsExactlyInAnyOrderElementsOf(names.entrySet().stream()
                        .map(entry -> org.assertj.core.groups.Tuple.tuple(entry.getKey(), entry.getValue()))
                        .toList());
    }

    @Test
    void rehearsedTogetherIsRetiredButOldRowsStillParse() {
        assertThat(TokenCatalog.get(TokenKey.SCENARIO_REHEARSAL)).isNull();
        assertThat(TokenCatalog.forStoredKey("SCENARIO_REHEARSAL")).isNull();
    }

    @Test
    void practiceTokensAreRetiredButOldRowsStillParse() {
        for (TokenKey k : new TokenKey[]{TokenKey.DRILL_CREW, TokenKey.PRACTICE_CADENCE}) {
            assertThat(TokenCatalog.get(k)).isNull();
            assertThat(TokenCatalog.forStoredKey(k.name())).isNull();
        }
    }

    @Test
    void readyTogetherNoLongerPromisesPractice() {
        TokenDefinition ready = TokenCatalog.get(TokenKey.HOUSEHOLD_READY);
        assertThat(ready.description()).isEqualTo("Your household has a plan and supplies in place.");
        assertThat(ready.description() + ready.lockedHint()).doesNotContainIgnoringCase("practice");
    }

    @Test
    void householdTokensAreHouseholdScoped() {
        assertThat(TokenCatalog.all().stream().filter(d -> d.scope() == TokenScope.HOUSEHOLD).map(TokenDefinition::key))
                .containsExactlyInAnyOrder(TokenKey.PLAN_ARCHITECT, TokenKey.MEETING_POINT, TokenKey.CONTACT_CIRCLE,
                        TokenKey.STOCKPILE_STEWARD, TokenKey.HOUSEHOLD_READY);
    }

    @Test
    void tokensThatMustNotInviteStatusSeekingHaveNoButton() {
        // A "report a hazard to earn this" button is the incentive the audit warns
        // against; the other two depend on someone else.
        assertThat(TokenCatalog.get(TokenKey.LOCAL_HAZARD_REPORTER).nextStepRoute()).isNull();
        assertThat(TokenCatalog.get(TokenKey.TRUSTED_ANSWER).nextStepRoute()).isNull();
        assertThat(TokenCatalog.get(TokenKey.HELPING_HAND).nextStepRoute()).isNull();
    }

    @Test
    void aRetiredStoredKeyResolvesToNullRatherThanThrowing() {
        assertThat(TokenCatalog.forStoredKey("PLAN_KEPT_FRESH")).isNull();
        assertThat(TokenCatalog.forStoredKey(null)).isNull();
        assertThat(TokenCatalog.forStoredKey("MEETING_POINT").key()).isEqualTo(TokenKey.MEETING_POINT);
    }
}
