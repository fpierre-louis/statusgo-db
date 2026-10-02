package io.sitprep.sitprepapi.constant;

import io.sitprep.sitprepapi.dto.ConditionsReading;

/**
 * THE one table of weather and air-quality thresholds
 * (docs/epics/daily_summary_gameplan.md §3.4).
 *
 * <p>Before this, the thresholds lived only in the frontend, in three copies
 * that disagreed: ConditionsBar, HazardsPage and ActiveNearbyStrip. A backend
 * job that needed them would have been a fourth. Values here mirror
 * ConditionsBar's caution and warning levels exactly, so the frontend can move
 * onto this table without any reading changing tier.</p>
 *
 * <ul>
 *   <li>AQI: EPA bands. Caution at 101 (Unhealthy for Sensitive Groups),
 *       warning at 151 (Unhealthy).</li>
 *   <li>Heat: feels-like 100°F caution, 105°F warning.</li>
 *   <li>Cold: feels-like 10°F caution, -15°F warning.</li>
 *   <li>Wind: NWS wind advisory, 31 mph sustained or 46 mph gusts; warning at
 *       40 mph or 58 mph gusts.</li>
 *   <li>Storm: WMO weather codes 95-99. Rain or snow: codes 51-67, 71-77,
 *       80-86, or a 60% precipitation chance.</li>
 * </ul>
 *
 * <p>One condition per reading, first match wins: health first (air, heat,
 * cold), then safety (wind, storm), then comfort (rain).</p>
 */
public final class ConditionTiers {

    public enum Condition { AIR, HEAT, COLD, WIND, STORM, RAIN, FAIR }

    public enum Tier { CALM, CAUTION, WARNING }

    public record Assessment(Condition condition, Tier tier) {}

    public static final int AQI_CAUTION = 101;
    public static final int AQI_WARNING = 151;
    public static final int HEAT_CAUTION_F = 100;
    public static final int HEAT_WARNING_F = 105;
    public static final int COLD_CAUTION_F = 10;
    public static final int COLD_WARNING_F = -15;
    public static final int WIND_CAUTION_MPH = 31;
    public static final int GUST_CAUTION_MPH = 46;
    public static final int WIND_WARNING_MPH = 40;
    public static final int GUST_WARNING_MPH = 58;
    public static final int PRECIP_LIKELY_PCT = 60;

    private ConditionTiers() {}

    /** EPA category name for a US AQI value, in full. Null when there is no reading. */
    public static String aqiCategory(Integer aqi) {
        if (aqi == null) return null;
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for Sensitive Groups";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very Unhealthy";
        return "Hazardous";
    }

    public static boolean isStormCode(Integer code) {
        return code != null && code >= 95 && code <= 99;
    }

    public static boolean isRainOrSnowCode(Integer code) {
        if (code == null) return false;
        return (code >= 51 && code <= 67) || (code >= 71 && code <= 77) || (code >= 80 && code <= 86);
    }

    /**
     * Classify a reading. {@code slot} decides how far ahead the heat and cold
     * rules look: morning and midday include today's feels-like high, evening
     * includes tonight's feels-like low. A null slot (the conditions endpoint)
     * looks at now and the next six hours only.
     */
    public static Assessment classify(ConditionsReading.Now now, ConditionsReading.Next6h next,
                                      ConditionsReading.Day today, ConditionsReading.Day tonight,
                                      BriefSlot slot) {
        Integer aqi = max(now == null ? null : now.aqi(), next == null ? null : next.maxAqi());
        if (aqi != null && aqi >= AQI_CAUTION) {
            return new Assessment(Condition.AIR, aqi >= AQI_WARNING ? Tier.WARNING : Tier.CAUTION);
        }

        boolean daytime = slot == BriefSlot.MORNING || slot == BriefSlot.MIDDAY;
        Integer hottest = max(max(now == null ? null : now.feelsF(), next == null ? null : next.maxFeelsF()),
                daytime && today != null ? today.feelsHighF() : null);
        if (hottest != null && hottest >= HEAT_CAUTION_F) {
            return new Assessment(Condition.HEAT, hottest >= HEAT_WARNING_F ? Tier.WARNING : Tier.CAUTION);
        }

        Integer coldest = min(min(now == null ? null : now.feelsF(), next == null ? null : next.minFeelsF()),
                slot == BriefSlot.EVENING && tonight != null ? tonight.feelsLowF() : null);
        if (coldest != null && coldest <= COLD_CAUTION_F) {
            return new Assessment(Condition.COLD, coldest <= COLD_WARNING_F ? Tier.WARNING : Tier.CAUTION);
        }

        Integer sustained = max(now == null ? null : now.windMph(), next == null ? null : next.maxWindMph());
        Integer gust = max(now == null ? null : now.gustMph(), next == null ? null : next.maxGustMph());
        boolean windCaution = atLeast(sustained, WIND_CAUTION_MPH) || atLeast(gust, GUST_CAUTION_MPH);
        if (windCaution) {
            boolean warning = atLeast(sustained, WIND_WARNING_MPH) || atLeast(gust, GUST_WARNING_MPH);
            return new Assessment(Condition.WIND, warning ? Tier.WARNING : Tier.CAUTION);
        }

        if ((now != null && isStormCode(now.weatherCode())) || (next != null && next.storm())) {
            return new Assessment(Condition.STORM, Tier.CAUTION);
        }

        boolean rainNow = now != null && isRainOrSnowCode(now.weatherCode());
        boolean rainSoon = next != null && (next.rainOrSnow() || atLeast(next.precipChancePct(), PRECIP_LIKELY_PCT));
        if (rainNow || rainSoon) return new Assessment(Condition.RAIN, Tier.CALM);

        return new Assessment(Condition.FAIR, Tier.CALM);
    }

    private static boolean atLeast(Integer v, int threshold) {
        return v != null && v >= threshold;
    }

    private static Integer max(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    private static Integer min(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }
}
