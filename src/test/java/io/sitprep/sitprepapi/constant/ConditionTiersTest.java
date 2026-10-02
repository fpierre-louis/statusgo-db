package io.sitprep.sitprepapi.constant;

import io.sitprep.sitprepapi.constant.ConditionTiers.Assessment;
import io.sitprep.sitprepapi.constant.ConditionTiers.Condition;
import io.sitprep.sitprepapi.constant.ConditionTiers.Tier;
import io.sitprep.sitprepapi.dto.ConditionsReading.Day;
import io.sitprep.sitprepapi.dto.ConditionsReading.Next6h;
import io.sitprep.sitprepapi.dto.ConditionsReading.Now;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC-3A: one test per row of the threshold table, plus the boundaries. The
 * values mirror ConditionsBar's caution/warning levels; if one of these moves,
 * the frontend strip's tier moves with it.
 */
class ConditionTiersTest {

    private static Now now(Integer feels, Integer wind, Integer gust, Integer code, Integer aqi) {
        return new Now(feels, feels, wind, 270, "W", gust, code, aqi, ConditionTiers.aqiCategory(aqi));
    }

    private static final Next6h CALM_NEXT = new Next6h(0, 5, 8, 30, 70, 60, false, false);
    private static final Day MILD = new Day(75, 55, 75, 55);

    private static Assessment classify(Now now) {
        return ConditionTiers.classify(now, CALM_NEXT, MILD, MILD, null);
    }

    @Test
    void aqiCategoriesAreTheEpaNamesInFull() {
        assertThat(ConditionTiers.aqiCategory(50)).isEqualTo("Good");
        assertThat(ConditionTiers.aqiCategory(51)).isEqualTo("Moderate");
        assertThat(ConditionTiers.aqiCategory(101)).isEqualTo("Unhealthy for Sensitive Groups");
        assertThat(ConditionTiers.aqiCategory(151)).isEqualTo("Unhealthy");
        assertThat(ConditionTiers.aqiCategory(201)).isEqualTo("Very Unhealthy");
        assertThat(ConditionTiers.aqiCategory(301)).isEqualTo("Hazardous");
        assertThat(ConditionTiers.aqiCategory(null)).isNull();
    }

    @Test
    void airCautionAt101AndWarningAt151() {
        assertThat(classify(now(70, 5, 8, 0, 100))).isEqualTo(new Assessment(Condition.FAIR, Tier.CALM));
        assertThat(classify(now(70, 5, 8, 0, 101))).isEqualTo(new Assessment(Condition.AIR, Tier.CAUTION));
        assertThat(classify(now(70, 5, 8, 0, 151))).isEqualTo(new Assessment(Condition.AIR, Tier.WARNING));
    }

    @Test
    void airCountsTheForecastNotJustNow() {
        Next6h smokeComing = new Next6h(0, 5, 8, 128, 70, 60, false, false);
        assertThat(ConditionTiers.classify(now(70, 5, 8, 0, 40), smokeComing, MILD, MILD, null).condition())
                .isEqualTo(Condition.AIR);
    }

    @Test
    void heatCautionAt100AndWarningAt105() {
        assertThat(classify(now(99, 5, 8, 0, 30)).condition()).isEqualTo(Condition.FAIR);
        assertThat(classify(now(100, 5, 8, 0, 30))).isEqualTo(new Assessment(Condition.HEAT, Tier.CAUTION));
        assertThat(classify(now(105, 5, 8, 0, 30))).isEqualTo(new Assessment(Condition.HEAT, Tier.WARNING));
    }

    @Test
    void morningLooksAtTodaysHighButEveningDoesNot() {
        Day scorcher = new Day(104, 80, 106, 82);
        Now cool = now(84, 5, 8, 0, 40);
        assertThat(ConditionTiers.classify(cool, CALM_NEXT, scorcher, MILD, BriefSlot.MORNING))
                .isEqualTo(new Assessment(Condition.HEAT, Tier.WARNING));
        assertThat(ConditionTiers.classify(cool, CALM_NEXT, scorcher, MILD, BriefSlot.EVENING).condition())
                .isEqualTo(Condition.FAIR);
    }

    @Test
    void coldCautionAt10AndWarningAtMinus15() {
        Next6h cold = new Next6h(0, 5, 8, 30, 11, 11, false, false);
        assertThat(ConditionTiers.classify(now(11, 5, 8, 0, 30), cold, MILD, MILD, null).condition())
                .isEqualTo(Condition.FAIR);
        assertThat(ConditionTiers.classify(now(10, 5, 8, 0, 30), cold, MILD, MILD, null))
                .isEqualTo(new Assessment(Condition.COLD, Tier.CAUTION));
        assertThat(ConditionTiers.classify(now(-15, 5, 8, 0, 30), cold, MILD, MILD, null))
                .isEqualTo(new Assessment(Condition.COLD, Tier.WARNING));
    }

    @Test
    void eveningLooksAtTonightsLow() {
        Day bitterNight = new Day(null, -2, null, -15);
        Next6h mild = new Next6h(0, 5, 8, 30, 30, 20, false, false);
        assertThat(ConditionTiers.classify(now(25, 5, 8, 0, 30), mild, MILD, bitterNight, BriefSlot.EVENING))
                .isEqualTo(new Assessment(Condition.COLD, Tier.WARNING));
        assertThat(ConditionTiers.classify(now(25, 5, 8, 0, 30), mild, MILD, bitterNight, BriefSlot.MORNING)
                .condition()).isEqualTo(Condition.FAIR);
    }

    @Test
    void windAtTheNwsAdvisoryAndWarningLevels() {
        assertThat(classify(now(70, 30, 45, 0, 30)).condition()).isEqualTo(Condition.FAIR);
        assertThat(classify(now(70, 31, 20, 0, 30))).isEqualTo(new Assessment(Condition.WIND, Tier.CAUTION));
        assertThat(classify(now(70, 10, 46, 0, 30))).isEqualTo(new Assessment(Condition.WIND, Tier.CAUTION));
        assertThat(classify(now(70, 40, 20, 0, 30))).isEqualTo(new Assessment(Condition.WIND, Tier.WARNING));
        assertThat(classify(now(70, 10, 58, 0, 30))).isEqualTo(new Assessment(Condition.WIND, Tier.WARNING));
    }

    @Test
    void stormFromThunderstormCodes() {
        assertThat(classify(now(70, 5, 8, 95, 30))).isEqualTo(new Assessment(Condition.STORM, Tier.CAUTION));
        Next6h stormLater = new Next6h(40, 5, 8, 30, 70, 60, true, true);
        assertThat(ConditionTiers.classify(now(70, 5, 8, 0, 30), stormLater, MILD, MILD, null).condition())
                .isEqualTo(Condition.STORM);
    }

    @Test
    void rainFromACodeOrA60PercentChance() {
        assertThat(classify(now(60, 5, 8, 61, 30))).isEqualTo(new Assessment(Condition.RAIN, Tier.CALM));
        Next6h likely = new Next6h(60, 5, 8, 30, 70, 60, false, false);
        Next6h unlikely = new Next6h(59, 5, 8, 30, 70, 60, false, false);
        assertThat(ConditionTiers.classify(now(60, 5, 8, 0, 30), likely, MILD, MILD, null).condition())
                .isEqualTo(Condition.RAIN);
        assertThat(ConditionTiers.classify(now(60, 5, 8, 0, 30), unlikely, MILD, MILD, null).condition())
                .isEqualTo(Condition.FAIR);
    }

    @Test
    void healthOutranksSafetyOutranksComfort() {
        // Smoky, windy and raining at once: air wins.
        assertThat(classify(now(60, 35, 50, 61, 160)).condition()).isEqualTo(Condition.AIR);
        // Windy and raining: wind wins.
        assertThat(classify(now(60, 35, 50, 61, 30)).condition()).isEqualTo(Condition.WIND);
    }

    @Test
    void missingDataIsFairNotAnAlarm() {
        assertThat(ConditionTiers.classify(null, null, null, null, null))
                .isEqualTo(new Assessment(Condition.FAIR, Tier.CALM));
    }
}
