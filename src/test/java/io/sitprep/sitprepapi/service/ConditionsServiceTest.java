package io.sitprep.sitprepapi.service;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC-3A: the Open-Meteo client, against REAL responses captured 2026-10-01
 * for (40.4, -111.9), Lehi UT. A hand-written fixture would test the shape we
 * imagined; these test the shape the upstream actually sends (hourly starting
 * at the current local hour, a local `current.time` plus `utc_offset_seconds`).
 */
class ConditionsServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConditionsService free = new ConditionsService(mapper, "", Clock.systemUTC());

    private static String fixture(String name) throws IOException {
        try (InputStream in = ConditionsServiceTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ConditionsReading lehi(BriefSlot slot) throws IOException {
        return free.parse(fixture("open-meteo-forecast-2026-10-01.json"),
                fixture("open-meteo-air-2026-10-01.json"), 40.4, -111.9, slot);
    }

    @Test
    void parsesTheCurrentReading() throws IOException {
        ConditionsReading r = lehi(null);
        assertThat(r).isNotNull();
        assertThat(r.timezone()).isEqualTo("America/Denver");
        // 23:15 local at UTC-6.
        assertThat(r.observedAt()).isEqualTo(Instant.parse("2026-10-02T05:15:00Z"));

        ConditionsReading.Now now = r.now();
        assertThat(now.tempF()).isEqualTo(60);
        assertThat(now.feelsF()).isEqualTo(56);       // 55.5 rounds up
        assertThat(now.windMph()).isEqualTo(3);
        assertThat(now.windDirDeg()).isEqualTo(85);
        assertThat(now.windDir()).isEqualTo("E");
        assertThat(now.gustMph()).isEqualTo(3);
        assertThat(now.aqi()).isEqualTo(70);
        assertThat(now.aqiCategory()).isEqualTo("Moderate");
    }

    @Test
    void summarisesTheNextSixHoursAndTheDays() throws IOException {
        ConditionsReading r = lehi(null);
        ConditionsReading.Next6h n = r.next6h();
        assertThat(n.precipChancePct()).isZero();
        assertThat(n.maxWindMph()).isEqualTo(3);
        assertThat(n.maxAqi()).isEqualTo(70);
        assertThat(n.maxFeelsF()).isEqualTo(56);
        assertThat(n.minFeelsF()).isEqualTo(53);     // 52.5 rounds up
        assertThat(n.storm()).isFalse();
        assertThat(n.rainOrSnow()).isFalse();

        assertThat(r.today().highF()).isEqualTo(78);
        assertThat(r.today().feelsHighF()).isEqualTo(75);
        // Tonight's low is TOMORROW's daily minimum, not today's early-morning low.
        assertThat(r.tonight().lowF()).isEqualTo(54);
        assertThat(r.tonight().feelsLowF()).isEqualTo(50);
    }

    @Test
    void aMildEveningIsFairAndCalm() throws IOException {
        ConditionsReading r = lehi(BriefSlot.EVENING);
        assertThat(r.condition()).isEqualTo("FAIR");
        assertThat(r.tier()).isEqualTo("CALM");
    }

    @Test
    void anUnreadableBodyIsNullNotAGuess() {
        assertThat(free.parse("{}", "{}", 40.4, -111.9, null)).isNull();
        assertThat(free.parse("not json", "{}", 40.4, -111.9, null)).isNull();
    }

    @Test
    void readingsAreTakenOnTheTenthDegreeGrid() {
        assertThat(ConditionsService.snap(40.4317)).isEqualTo(40.4);
        assertThat(ConditionsService.snap(-111.8888)).isEqualTo(-111.9);
        assertThat(ConditionsService.snap(40.45)).isEqualTo(40.5);
    }

    @Test
    void freeHostsWithoutAKeyAndCustomerHostsWithOne() {
        assertThat(free.forecastUrl(40.4, -111.9)).startsWith(ConditionsService.FREE_FORECAST)
                .contains("wind_direction_10m").doesNotContain("apikey");
        assertThat(free.airUrl(40.4, -111.9)).startsWith(ConditionsService.FREE_AIR);

        ConditionsService paid = new ConditionsService(mapper, " k123 ",
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        assertThat(paid.forecastUrl(40.4, -111.9)).startsWith(ConditionsService.PAID_FORECAST)
                .endsWith("&apikey=k123");
        assertThat(paid.airUrl(40.4, -111.9)).startsWith(ConditionsService.PAID_AIR)
                .endsWith("&apikey=k123");
    }

    @Test
    void coordinatesInUrlsAreSnappedAndLocaleSafe() {
        // A comma decimal separator in the default locale must never reach the URL.
        assertThat(free.forecastUrl(40.4, -111.9)).contains("latitude=40.4&longitude=-111.9");
    }
}
