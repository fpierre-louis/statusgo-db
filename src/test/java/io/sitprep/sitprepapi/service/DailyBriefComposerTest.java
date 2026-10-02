package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import io.sitprep.sitprepapi.dto.ConditionsReading.Day;
import io.sitprep.sitprepapi.dto.ConditionsReading.Next6h;
import io.sitprep.sitprepapi.dto.ConditionsReading.Now;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC-3B: the fixed sentence (gameplan §3.5) and golden rows of the matrix
 * (§5). The structure is an owner rule: temperature, wind, air, then nudge.
 */
class DailyBriefComposerTest {

    private final DailyBriefComposer composer = new DailyBriefComposer();
    private final DailyNudgeCatalog catalog = new DailyNudgeCatalog();

    private static ConditionsReading reading(Now now, Next6h next, Day today, Day tonight, BriefSlot slot) {
        ConditionTiers.Assessment a = ConditionTiers.classify(now, next, today, tonight, slot);
        return new ConditionsReading(40.4, -111.9, "America/Denver", Instant.EPOCH, now, next, today, tonight,
                a.condition().name(), a.tier().name());
    }

    private static Now now(int temp, int feels, int wind, int deg, String dir, int gust, int aqi) {
        return new Now(temp, feels, wind, deg, dir, gust, false, false, aqi, ConditionTiers.aqiCategory(aqi));
    }

    private static final Next6h QUIET = new Next6h(0, 9, 12, 32, 70, 60, false, false);
    private static final Day MILD = new Day(75, 55, 75, 55);

    private String body(ConditionsReading r, BriefSlot slot) {
        DailyNudgeCatalog.Nudge n = catalog.matrixRow(ConditionTiers.Condition.valueOf(r.condition()), slot);
        return composer.compose(r, slot, n).body();
    }

    @Test
    void morningFairIsTheMatrixRow() {
        ConditionsReading r = reading(now(68, 68, 8, 247, "WSW", 12, 32), QUIET, MILD, MILD, BriefSlot.MORNING);
        assertThat(body(r, BriefSlot.MORNING)).isEqualTo(
                "Morning update: 68°F, WSW winds at 8 mph, air quality is Good (AQI 32).\n\n"
                        + "Clear skies this morning. If you have two minutes, walk past your family's outdoor "
                        + "meeting spot and make sure everyone would know where it is.");
    }

    @Test
    void middayHeatShowsFeelsLikeAndTheNeighbourOffer() {
        Next6h hot = new Next6h(0, 9, 14, 88, 106, 99, false, false);
        ConditionsReading r = reading(now(101, 106, 9, 225, "SW", 14, 88), hot, MILD, MILD, BriefSlot.MIDDAY);
        assertThat(r.condition()).isEqualTo("HEAT");
        assertThat(body(r, BriefSlot.MIDDAY)).startsWith(
                "Midday update: 101°F (feels like 106°F), SW winds at 9 mph, air quality is Moderate (AQI 88).\n\n"
                        + "Peak heat right now.");
    }

    @Test
    void windGustsShowWhenTheyBeatSustainedByTen() {
        Next6h gusty = new Next6h(0, 33, 51, 27, 63, 58, false, false);
        ConditionsReading r = reading(now(63, 63, 33, 270, "W", 51, 27), gusty, MILD, MILD, BriefSlot.MIDDAY);
        assertThat(composer.weatherSentence(r, BriefSlot.MIDDAY))
                .isEqualTo("Midday update: 63°F, W winds at 33 mph, gusting to 51, air quality is Good (AQI 27).");
    }

    @Test
    void calmWindStillHoldsItsPlace() {
        ConditionsReading r = reading(now(61, 61, 2, 90, "E", 3, 24), QUIET, MILD, MILD, BriefSlot.EVENING);
        assertThat(composer.weatherSentence(r, BriefSlot.EVENING))
                .isEqualTo("Evening update: 61°F, winds calm, air quality is Good (AQI 24).");
    }

    @Test
    void feelsLikeOnlyWhenItDiffersByFive() {
        ConditionsReading close = reading(now(60, 56, 8, 0, "N", 9, 20), QUIET, MILD, MILD, BriefSlot.MORNING);
        ConditionsReading far = reading(now(60, 55, 8, 0, "N", 9, 20), QUIET, MILD, MILD, BriefSlot.MORNING);
        assertThat(composer.weatherSentence(close, BriefSlot.MORNING)).doesNotContain("feels like");
        assertThat(composer.weatherSentence(far, BriefSlot.MORNING)).contains("(feels like 55°F)");
    }

    @Test
    void eveningColdAddsTonightsLow() {
        Next6h cold = new Next6h(0, 10, 14, 19, 2, -15, false, false);
        Day tonight = new Day(null, -2, null, -15);
        ConditionsReading r = reading(now(-2, -15, 10, 0, "N", 14, 19), cold, MILD, tonight, BriefSlot.EVENING);
        assertThat(r.condition()).isEqualTo("COLD");
        assertThat(body(r, BriefSlot.EVENING)).startsWith(
                "Evening update: -2°F (feels like -15°F), N winds at 10 mph, air quality is Good (AQI 19). "
                        + "Tonight's low is -2°F.\n\nVery cold night.");
    }

    @Test
    void airUsesTheFullEpaCategory() {
        ConditionsReading r = reading(now(63, 63, 4, 90, "E", 6, 128), QUIET, MILD, MILD, BriefSlot.MORNING);
        assertThat(composer.weatherSentence(r, BriefSlot.MORNING))
                .endsWith("air quality is Unhealthy for Sensitive Groups (AQI 128).");
    }

    @Test
    void rainComingGetsAnOutlook() {
        Next6h wet = new Next6h(80, 12, 18, 18, 54, 50, false, true);
        ConditionsReading r = reading(now(54, 54, 12, 158, "SSE", 18, 18), wet, MILD, MILD, BriefSlot.MORNING);
        assertThat(r.condition()).isEqualTo("RAIN");
        assertThat(body(r, BriefSlot.MORNING)).contains("(AQI 18). Rain likely in the next few hours.\n\nRain on the way.");
    }

    @Test
    void aMissingReadingMeansNoBrief() {
        Now noAqi = new Now(60, 60, 8, 0, "N", 9, false, false, null, null);
        ConditionsReading r = new ConditionsReading(40.4, -111.9, "UTC", Instant.EPOCH, noAqi, QUIET, MILD, MILD,
                "FAIR", "CALM");
        assertThat(composer.compose(r, BriefSlot.MORNING, catalog.matrixRow(ConditionTiers.Condition.FAIR,
                BriefSlot.MORNING))).isNull();
    }
}
