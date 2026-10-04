package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC-3A.1: the NWS + AirNow client.
 *
 * <p>The NWS fixtures are REAL responses captured 2026-10-02 for the Salt Lake
 * City grid (SLC 98,159; {@code /points} and the raw gridpoint forecast,
 * trimmed to the series the reading uses). They test the shape NWS actually
 * sends: SI units, ISO-8601 interval {@code validTime}s of uneven length, and
 * day/night max/min periods.</p>
 *
 * <p>The AirNow fixture is a REAL response from the 2026
 * {@code observation/current/ziplatLong} service, captured 2026-10-02 17:00 MDT
 * for (40.4, -111.9): one row per pollutant, {@code nowcastAQI}, and AirNow's
 * own 50-mile lookup boundary. (The 2020-era service it replaced was retired
 * 2026-10-01 and answers 410.)</p>
 */
class ConditionsServiceTest {

    private static final Instant AT = Instant.parse("2026-10-02T13:15:00Z");   // 07:15 MDT
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConditionsService svc = new ConditionsService(mapper, "test-key", Clock.fixed(AT, ZoneOffset.UTC));

    private static String fixture(String name) throws IOException {
        try (InputStream in = ConditionsServiceTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ConditionsReading slc(BriefSlot slot) throws IOException {
        return svc.parse(fixture("nws-gridpoint-2026-10-02.json"),
                fixture("airnow-ziplatlong-2026-10-02.json"), "America/Denver", 40.8, -111.9, slot);
    }

    @Test
    void pointsGiveTheGridUrlAndTheZone() throws IOException {
        ConditionsService.Point p = svc.parsePoint(fixture("nws-points-2026-10-02.json"));
        assertThat(p.gridDataUrl()).isEqualTo("https://api.weather.gov/gridpoints/SLC/98,159");
        assertThat(p.timezone()).isEqualTo("America/Denver");
    }

    @Test
    void theCurrentHourIsConvertedFromSiUnits() throws IOException {
        ConditionsReading r = slc(null);
        assertThat(r).isNotNull();
        assertThat(r.timezone()).isEqualTo("America/Denver");
        assertThat(r.observedAt()).isEqualTo(AT);

        ConditionsReading.Now now = r.now();
        assertThat(now.tempF()).isEqualTo(54);        // 12.22 °C
        assertThat(now.feelsF()).isEqualTo(54);
        assertThat(now.windMph()).isEqualTo(3);       // 5.556 km/h
        assertThat(now.windDirDeg()).isEqualTo(140);
        assertThat(now.windDir()).isEqualTo("SE");
        assertThat(now.gustMph()).isEqualTo(8);       // 12.964 km/h
        assertThat(now.storm()).isFalse();
        assertThat(now.rainOrSnow()).isFalse();
    }

    @Test
    void theAqiIsTheWorstPollutantWithItsEpaCategory() throws IOException {
        ConditionsReading.Now now = slc(null).now();
        assertThat(now.aqi()).isEqualTo(43);          // OZONE 43 beats PM2.5 28 and PM10 16
        assertThat(now.aqiCategory()).isEqualTo("Good");
    }

    @Test
    void theNextSixHoursAndTheDays() throws IOException {
        ConditionsReading r = slc(null);
        ConditionsReading.Next6h n = r.next6h();
        assertThat(n.precipChancePct()).isZero();
        assertThat(n.maxWindMph()).isEqualTo(7);
        assertThat(n.maxGustMph()).isEqualTo(13);
        assertThat(n.maxAqi()).as("no free US hourly AQI forecast; never invented").isNull();
        assertThat(n.maxFeelsF()).isEqualTo(73);
        assertThat(n.minFeelsF()).isEqualTo(53);

        // NWS daytime high starting today; the overnight low ENDING this morning.
        assertThat(r.today().highF()).isEqualTo(84);
        assertThat(r.today().lowF()).isEqualTo(52);
        assertThat(r.today().feelsHighF()).isEqualTo(81);
        // Tonight is the overnight period STARTING today (20:00 MDT).
        assertThat(r.tonight().lowF()).isEqualTo(57);
        assertThat(r.tonight().feelsLowF()).isEqualTo(57);
    }

    @Test
    void tonightIsTheOvernightEndingTomorrowNotTheTruncatedStubThisMorning() throws IOException {
        // 17:00 MDT on 2026-10-01. The capture's first low is a stub of the
        // overnight already under way when NWS issued it (12:00Z/PT4H, 06:00
        // local, 48°F). It STARTS today, but tonight is the 20:00 overnight
        // that ends tomorrow morning (02:00Z/PT14H, 52°F).
        ConditionsService evening = new ConditionsService(mapper, "test-key",
                Clock.fixed(Instant.parse("2026-10-01T23:00:00Z"), ZoneOffset.UTC));
        ConditionsReading r = evening.parse(fixture("nws-gridpoint-2026-10-02.json"),
                fixture("airnow-ziplatlong-2026-10-02.json"), "America/Denver", 40.8, -111.9, BriefSlot.EVENING);
        assertThat(r.today().lowF()).isEqualTo(48);
        assertThat(r.tonight().lowF()).isEqualTo(52);
    }

    @Test
    void aMildMorningIsFairAndCalm() throws IOException {
        ConditionsReading r = slc(BriefSlot.MORNING);
        assertThat(r.condition()).isEqualTo("FAIR");
        assertThat(r.tier()).isEqualTo("CALM");
    }

    @Test
    void noMonitorInRangeMeansNoReading() throws IOException {
        assertThat(svc.parse(fixture("nws-gridpoint-2026-10-02.json"), "[]", "America/Denver", 40.8, -111.9, null))
                .isNull();
    }

    @Test
    void airNowNoValueIsSkippedNotCountedAsZero() throws IOException {
        JsonNode obs = mapper.readTree("[{\"parameterName\":\"OZONE\",\"nowcastAQI\":-1},{\"parameterName\":\"PM2.5\",\"nowcastAQI\":12}]");
        assertThat(ConditionsService.airNowAqi(obs)).isEqualTo(12);
        assertThat(ConditionsService.airNowAqi(mapper.readTree("[{\"nowcastAQI\":-1}]"))).isNull();
        assertThat(ConditionsService.airNowAqi(mapper.readTree("{\"WebServiceError\":[{\"Message\":\"retired\"}]}"))).isNull();
    }

    @Test
    void aForecastThatHasEndedIsNoReading() throws IOException {
        ConditionsService later = new ConditionsService(mapper, "test-key",
                Clock.fixed(Instant.parse("2026-11-30T00:00:00Z"), ZoneOffset.UTC));
        assertThat(later.parse(fixture("nws-gridpoint-2026-10-02.json"),
                fixture("airnow-ziplatlong-2026-10-02.json"), "America/Denver", 40.8, -111.9, null))
                .isNull();
    }

    @Test
    void aChangedUnitIsNoReadingNotAWrongOne() throws IOException {
        String fahrenheit = fixture("nws-gridpoint-2026-10-02.json").replace("wmoUnit:degC", "wmoUnit:degF");
        assertThat(svc.parse(fahrenheit, fixture("airnow-ziplatlong-2026-10-02.json"),
                "America/Denver", 40.8, -111.9, null)).isNull();
    }

    @Test
    void aBlankAirNowKeyReadsNothingAndCallsNoOne() {
        ConditionsService keyless = new ConditionsService(mapper, "  ", Clock.fixed(AT, ZoneOffset.UTC));
        assertThat(keyless.readingFor(40.76, -111.89)).isNull();
    }

    @Test
    void thunderstormsBeyondASlightChanceAreAStorm() throws IOException {
        assertThat(ConditionsService.storm(weather("chance", "thunderstorms"))).isTrue();
        assertThat(ConditionsService.storm(weather("scattered", "thunderstorms"))).isTrue();
        assertThat(ConditionsService.storm(weather("slight_chance", "thunderstorms"))).isFalse();
        assertThat(ConditionsService.storm(weather("isolated", "thunderstorms"))).isFalse();
        assertThat(ConditionsService.storm(weather("likely", "rain"))).isFalse();
    }

    @Test
    void rainOrSnowOnlyWhenTheGridCallsItLikely() throws IOException {
        assertThat(ConditionsService.rainOrSnow(weather("likely", "rain_showers"))).isTrue();
        assertThat(ConditionsService.rainOrSnow(weather("definite", "snow"))).isTrue();
        // 30-50%: below the 60% line ConditionTiers draws for precipitation.
        assertThat(ConditionsService.rainOrSnow(weather("chance", "rain"))).isFalse();
        assertThat(ConditionsService.rainOrSnow(weather("likely", "fog"))).isFalse();
        // The captured grid's "nothing expected" entry is all nulls.
        assertThat(ConditionsService.rainOrSnow(weather(null, null))).isFalse();
    }

    @Test
    void intervalsReadNwsDurations() throws IOException {
        ConditionsService.Interval i = ConditionsService.interval(
                mapper.readTree("{\"validTime\":\"2026-10-01T12:00:00+00:00/P7DT13H\",\"value\":1}"));
        assertThat(i.start()).isEqualTo(Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(i.end()).isEqualTo(Instant.parse("2026-10-09T01:00:00Z"));
    }

    private List<JsonNode> weather(String coverage, String type) throws IOException {
        String c = coverage == null ? "null" : "\"" + coverage + "\"";
        String t = type == null ? "null" : "\"" + type + "\"";
        return List.of(mapper.readTree("{\"coverage\":" + c + ",\"weather\":" + t + ",\"intensity\":null}"));
    }

    /** The feed's read never calls upstream: a cold cache is an instant null. */
    @Test
    void cachedReadsNeverFetch() {
        long t0 = System.nanoTime();
        assertThat(svc.cachedTimezoneFor(40.39, -111.85)).isNull();
        assertThat(svc.cachedReadingFor(40.39, -111.85, BriefSlot.EVENING)).isNull();
        assertThat(System.nanoTime() - t0).isLessThan(200_000_000L);
    }
}
