package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import org.springframework.stereotype.Component;

/**
 * Writes a daily brief's text (EXEC-3B; gameplan §3.5).
 *
 * <p><b>The structure is strict, by owner rule:</b> temperature, then wind,
 * then air quality, and only then the nudge.</p>
 *
 * <pre>
 * {Slot} update: {temp}°F[ (feels like {feels}°F)], {dir} winds at {speed} mph[, gusting to {gust}],
 *   air quality is {category} (AQI {aqi}).[ {outlook}]
 *
 * {nudge}
 * </pre>
 *
 * <p>If any of the three readings is missing there is no brief: a sentence
 * that skips the wind, or says "AQI unknown", breaks the structure and
 * implies a completeness the reading does not have.</p>
 */
@Component
public class DailyBriefComposer {

    /** Feels-like is shown only when it differs from the temperature by at least this. */
    static final int FEELS_DIFF_F = 5;
    /** Gusts are shown only when they beat the sustained wind by at least this. */
    static final int GUST_DIFF_MPH = 10;
    /** Below this the wind reads "winds calm", still in the same position. */
    static final int CALM_WIND_MPH = 3;

    public record Brief(String weather, String outlook, String body) {}

    /** The weather line plus the nudge, or null when a required reading is missing. */
    public Brief compose(ConditionsReading r, BriefSlot slot, DailyNudgeCatalog.Nudge nudge) {
        String weather = weatherSentence(r, slot);
        if (weather == null || nudge == null) return null;
        String outlook = outlook(r, slot);
        String line = outlook == null ? weather : weather + " " + outlook;
        return new Brief(weather, outlook, line + "\n\n" + nudge.text());
    }

    static String slotLabel(BriefSlot slot) {
        return switch (slot) {
            case MORNING -> "Morning";
            case MIDDAY -> "Midday";
            case EVENING -> "Evening";
        };
    }

    String weatherSentence(ConditionsReading r, BriefSlot slot) {
        if (r == null || r.now() == null) return null;
        ConditionsReading.Now n = r.now();
        if (n.tempF() == null || n.windMph() == null || n.aqi() == null) return null;

        StringBuilder sb = new StringBuilder();
        // A null slot is the daily-brief card (EXEC-3C): its header already
        // says "Conditions near {place} · Updated {time}", so no slot prefix.
        if (slot != null) sb.append(slotLabel(slot)).append(" update: ");
        sb.append(n.tempF()).append("°F");
        if (n.feelsF() != null && Math.abs(n.feelsF() - n.tempF()) >= FEELS_DIFF_F) {
            sb.append(" (feels like ").append(n.feelsF()).append("°F)");
        }
        sb.append(", ");
        if (n.windMph() < CALM_WIND_MPH) {
            sb.append("winds calm");
        } else {
            if (n.windDir() != null) sb.append(n.windDir()).append(' ');
            sb.append("winds at ").append(n.windMph()).append(" mph");
            if (n.gustMph() != null && n.gustMph() - n.windMph() >= GUST_DIFF_MPH) {
                sb.append(", gusting to ").append(n.gustMph());
            }
        }
        String category = n.aqiCategory() != null ? n.aqiCategory() : ConditionTiers.aqiCategory(n.aqi());
        sb.append(", air quality is ").append(category).append(" (AQI ").append(n.aqi()).append(").");
        return sb.toString();
    }

    /**
     * One short clause, only when the condition is something COMING rather
     * than already in the numbers. A morning that is already 104°F does not
     * need "feels like 104 later".
     */
    String outlook(ConditionsReading r, BriefSlot slot) {
        if (r == null || r.condition() == null) return null;
        ConditionsReading.Now n = r.now();
        ConditionsReading.Next6h x = r.next6h();
        ConditionTiers.Condition c;
        try {
            c = ConditionTiers.Condition.valueOf(r.condition());
        } catch (IllegalArgumentException e) {
            return null;
        }
        switch (c) {
            case AIR -> {
                if (n != null && n.aqi() != null && n.aqi() < ConditionTiers.AQI_CAUTION
                        && x != null && x.maxAqi() != null) {
                    return "Air quality may reach AQI " + x.maxAqi() + " in the next few hours.";
                }
                return null;
            }
            case HEAT -> {
                boolean daytime = slot == BriefSlot.MORNING || slot == BriefSlot.MIDDAY;
                if (daytime && r.today() != null && r.today().feelsHighF() != null
                        && (n == null || n.feelsF() == null || n.feelsF() < ConditionTiers.HEAT_CAUTION_F)) {
                    return "Feels like " + r.today().feelsHighF() + "°F by mid-afternoon.";
                }
                return null;
            }
            case COLD -> {
                // Midday and evening look ahead to tonight; the morning's own
                // low has already happened.
                if (slot != BriefSlot.MORNING && r.tonight() != null && r.tonight().lowF() != null) {
                    return "Tonight's low is " + r.tonight().lowF() + "°F.";
                }
                return null;
            }
            case WIND -> {
                boolean nowWindy = n != null && ((n.windMph() != null && n.windMph() >= ConditionTiers.WIND_CAUTION_MPH)
                        || (n.gustMph() != null && n.gustMph() >= ConditionTiers.GUST_CAUTION_MPH));
                if (!nowWindy && x != null && x.maxGustMph() != null) {
                    return "Gusts up to " + x.maxGustMph() + " mph in the next few hours.";
                }
                return null;
            }
            case STORM -> {
                boolean stormNow = n != null && n.storm();
                return stormNow ? "Thunderstorms in the area now." : "Thunderstorms possible in the next few hours.";
            }
            case RAIN -> {
                boolean rainNow = n != null && n.rainOrSnow();
                return rainNow ? null : "Rain likely in the next few hours.";
            }
            default -> {
                return null;
            }
        }
    }
}
