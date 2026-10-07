package io.sitprep.sitprepapi.constant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one band rule (V100 backfill, manual-member writes, composition reads). */
class HouseholdBandTest {

    @Test
    void deriveMatchesTheBackfillAndTheFormerFeRule() {
        assertThat(HouseholdBand.derive(true, 5)).isEqualTo(HouseholdBand.ADULT);   // adult flag wins
        assertThat(HouseholdBand.derive(true, null)).isEqualTo(HouseholdBand.ADULT);
        assertThat(HouseholdBand.derive(false, 1)).isEqualTo(HouseholdBand.INFANT);
        assertThat(HouseholdBand.derive(false, 0)).isEqualTo(HouseholdBand.KID);    // 0 = unknown, not newborn
        assertThat(HouseholdBand.derive(false, 2)).isEqualTo(HouseholdBand.KID);
        assertThat(HouseholdBand.derive(false, 12)).isEqualTo(HouseholdBand.KID);
        assertThat(HouseholdBand.derive(false, 13)).isEqualTo(HouseholdBand.TEEN);
        assertThat(HouseholdBand.derive(false, 40)).isEqualTo(HouseholdBand.TEEN);  // not adult-flagged → never ADULT
        assertThat(HouseholdBand.derive(null, null)).isEqualTo(HouseholdBand.KID);  // age-less minor
    }

    @Test
    void parseAcceptsEnumNamesAndFeFigureKeys() {
        assertThat(HouseholdBand.parse("teen")).isEqualTo(HouseholdBand.TEEN);
        assertThat(HouseholdBand.parse(" Child ")).isEqualTo(HouseholdBand.KID);
        assertThat(HouseholdBand.parse("kid")).isEqualTo(HouseholdBand.KID);
        assertThat(HouseholdBand.parse("INFANT")).isEqualTo(HouseholdBand.INFANT);
        assertThat(HouseholdBand.parse("adult")).isEqualTo(HouseholdBand.ADULT);
        assertThat(HouseholdBand.parse(null)).isNull();
        assertThat(HouseholdBand.parse("  ")).isNull();
        assertThatThrownBy(() -> HouseholdBand.parse("elder")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void speciesMapsToTheThreeDemographicColumns() {
        assertThat(PetSpecies.of("Dog")).isEqualTo(PetSpecies.DOG);
        assertThat(PetSpecies.of(" cat ")).isEqualTo(PetSpecies.CAT);
        assertThat(PetSpecies.of("other")).isEqualTo(PetSpecies.OTHER);
        assertThat(PetSpecies.of("parrot")).isEqualTo(PetSpecies.OTHER);
        assertThat(PetSpecies.of(null)).isEqualTo(PetSpecies.OTHER);
    }
}
