package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * One weather and air-quality reading for a point, as the backend shapes it
 * (docs/epics/daily_summary_gameplan.md §3.3). The frontend displays this; it
 * does no thresholding of its own.
 *
 * <p>{@code lat}/{@code lng} are the point the reading was TAKEN at, snapped to
 * a 0.1° grid (about 11 km). That is the daily-brief cell anchor, and the
 * reason it is snapped is privacy: a brief's coordinates must never be one
 * person's home.</p>
 *
 * <p>Temperatures are °F, speeds mph, AQI is US EPA. Weather is NOAA NWS,
 * air quality is EPA AirNow. Every number is nullable:
 * a field the upstream did not send stays null rather than becoming zero.</p>
 */
public record ConditionsReading(
        double lat,
        double lng,
        String timezone,
        Instant observedAt,
        Now now,
        Next6h next6h,
        Day today,
        Day tonight,
        String condition,
        String tier
) {
    /**
     * Conditions at {@code observedAt}. {@code windDir} is where the wind blows from.
     * {@code storm} and {@code rainOrSnow} come from the NWS weather grid for the
     * current hour (thunderstorms beyond a slight chance; precipitation likely or
     * certain). There is deliberately no weather code: NWS does not publish WMO
     * codes, and inventing one would be a translation the upstream never made.
     */
    public record Now(Integer tempF, Integer feelsF, Integer windMph, Integer windDirDeg, String windDir,
                      Integer gustMph, boolean storm, boolean rainOrSnow, Integer aqi, String aqiCategory) {}

    /**
     * The next six hours, current hour included. {@code maxAqi} is always null
     * today: no free US source publishes an hourly AQI forecast (EXEC-3A.1).
     */
    public record Next6h(Integer precipChancePct, Integer maxWindMph, Integer maxGustMph, Integer maxAqi,
                         Integer maxFeelsF, Integer minFeelsF, boolean storm, boolean rainOrSnow) {}

    /** A calendar day in the reading's time zone. {@code tonight} carries only the lows. */
    public record Day(Integer highF, Integer lowF, Integer feelsHighF, Integer feelsLowF) {}
}
