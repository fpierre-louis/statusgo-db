package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.MealPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code setSuppliesGathered} flips ONE flag on exactly the row {@code upsert}
 * would write, and leaves the menus and shopping-list picks alone.
 *
 * <p>The gather toggle used to re-send the whole plan, and both whole-record
 * paths clear and re-insert every menu and overwrite {@code selectedItemsJson}.
 * The first test is the point of the endpoint; the rest pin the write rule it
 * shares with {@code upsert}: the {@code X-Household-Id} household only when the
 * caller may write it, else the caller's base household, else the legacy
 * by-owner row.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MealPlanSuppliesGatheredTest {

    @Mock MealPlanDataRepo repo;
    @Mock HouseholdResolver resolver;

    private MealPlanDataService service;

    @BeforeEach
    void setUp() {
        service = new MealPlanDataService(repo, resolver);
        when(repo.save(any(MealPlanData.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findFirstByHouseholdId(any())).thenReturn(Optional.empty());
        when(repo.findFirstByOwnerEmailIgnoreCase(any())).thenReturn(Optional.empty());
    }

    private MealPlanData plan(long id, String owner, String hh) {
        MealPlan menu = new MealPlan();
        menu.setMeals(Map.of("breakfast", "Oatmeal"));
        MealPlanData m = new MealPlanData();
        m.setId(id);
        m.setOwnerEmail(owner);
        m.setHouseholdId(hh);
        m.setNumberOfMenuOptions(1);
        m.setSelectedItemsJson("[\"rice\"]");
        m.setMealPlan(List.of(menu));
        return m;
    }

    @Test
    void flipsOnlyTheFlag_menusAndPicksSurvive() {
        MealPlanData base = plan(7L, "head@probe.app", "hh-base");
        MealPlan menu = base.getMealPlan().get(0);
        when(resolver.baseHouseholdIdFor("member@probe.app")).thenReturn("hh-base");
        when(repo.findFirstByHouseholdId("hh-base")).thenReturn(Optional.of(base));

        Optional<MealPlanData> out = service.setSuppliesGathered("Member@Probe.app", true);

        assertThat(out).isPresent();
        assertThat(out.get().getSuppliesGathered()).isTrue();
        // The same menu instance, not a cleared-and-re-added copy.
        assertThat(out.get().getMealPlan()).containsExactly(menu);
        assertThat(out.get().getSelectedItemsJson()).isEqualTo("[\"rice\"]");
        assertThat(out.get().getOwnerEmail()).as("original author kept").isEqualTo("head@probe.app");
    }

    @Test
    void allowed_adminTargetingAnotherHousehold_writesThatHousehold() {
        MealPlanData other = plan(9L, "grandma@probe.app", "hh-grandma");
        MealPlanData base = plan(7L, "admin@probe.app", "hh-base");
        when(resolver.writableTargetHousehold("admin@probe.app")).thenReturn("hh-grandma");
        when(resolver.baseHouseholdIdFor("admin@probe.app")).thenReturn("hh-base");
        when(repo.findFirstByHouseholdId("hh-grandma")).thenReturn(Optional.of(other));
        when(repo.findFirstByHouseholdId("hh-base")).thenReturn(Optional.of(base));

        service.setSuppliesGathered("admin@probe.app", true);

        assertThat(other.getSuppliesGathered()).isTrue();
        assertThat(base.getSuppliesGathered()).as("base untouched").isNull();
    }

    @Test
    void forbidden_householdTheCallerCantWrite_isNeverTouched() {
        // The resolver refuses the header (caller isn't owner/admin there), so
        // exactly as in upsert the write falls back to the caller's own base.
        MealPlanData foreign = plan(9L, "stranger@probe.app", "hh-foreign");
        MealPlanData base = plan(7L, "member@probe.app", "hh-base");
        when(resolver.writableTargetHousehold("member@probe.app")).thenReturn(null);
        when(resolver.baseHouseholdIdFor("member@probe.app")).thenReturn("hh-base");
        when(repo.findFirstByHouseholdId("hh-foreign")).thenReturn(Optional.of(foreign));
        when(repo.findFirstByHouseholdId("hh-base")).thenReturn(Optional.of(base));

        service.setSuppliesGathered("member@probe.app", true);

        assertThat(foreign.getSuppliesGathered()).isNull();
        assertThat(base.getSuppliesGathered()).isTrue();
    }

    @Test
    void legacyByOwnerRow_isTheFallback() {
        MealPlanData legacy = plan(3L, "solo@probe.app", null);
        when(resolver.baseHouseholdIdFor("solo@probe.app")).thenReturn(null);
        when(repo.findFirstByOwnerEmailIgnoreCase("solo@probe.app")).thenReturn(Optional.of(legacy));

        assertThat(service.setSuppliesGathered("solo@probe.app", true)).contains(legacy);
        assertThat(legacy.getSuppliesGathered()).isTrue();
    }

    @Test
    void noPlan_isEmpty_andNothingIsCreated() {
        when(resolver.baseHouseholdIdFor("new@probe.app")).thenReturn("hh-new");

        assertThat(service.setSuppliesGathered("new@probe.app", true)).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void idempotent_andReversible() {
        MealPlanData base = plan(7L, "head@probe.app", "hh-base");
        when(resolver.baseHouseholdIdFor("head@probe.app")).thenReturn("hh-base");
        when(repo.findFirstByHouseholdId("hh-base")).thenReturn(Optional.of(base));

        service.setSuppliesGathered("head@probe.app", true);
        service.setSuppliesGathered("head@probe.app", true);
        assertThat(base.getSuppliesGathered()).isTrue();

        service.setSuppliesGathered("head@probe.app", false);
        assertThat(base.getSuppliesGathered()).isFalse();
        assertThat(base.getMealPlan()).hasSize(1);
    }
}
