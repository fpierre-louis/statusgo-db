package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import io.sitprep.sitprepapi.util.Compass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Weather and air quality for a point, from Open-Meteo
 * (docs/epics/daily_summary_gameplan.md §3.3; EXEC-3A).
 *
 * <p>The first backend weather client. Until now the only reading in the app
 * was fetched by the browser in ConditionsBar, so nothing server-side (a
 * scheduled brief, a native client, a script) could know the weather.</p>
 *
 * <ul>
 *   <li><b>Snapped to 0.1°.</b> Every reading is taken at the point rounded to
 *       a 0.1° grid (about 11 km). That is the daily-brief cell anchor, it
 *       makes the cache effective, and it means no reading's coordinates are
 *       ever one person's address.</li>
 *   <li><b>Cached 20 minutes</b> per snapped point, so the conditions endpoint
 *       and the scheduler share one reading and the upstream sees one call.</li>
 *   <li><b>Soft fail.</b> Any upstream error returns null. A missing reading is
 *       reported as missing, never guessed: the house rule ConditionsBar
 *       already states ("a missing value is reported, never faked").</li>
 *   <li><b>Commercial key.</b> With {@code conditions.open-meteo.api-key} set
 *       (env {@code OPEN_METEO_API_KEY}), calls go to Open-Meteo's
 *       {@code customer-} hosts with {@code apikey}, as the commercial plan
 *       requires. Blank uses the free hosts, which are for non-commercial use;
 *       the owner approved the commercial plan on 2026-10-01.</li>
 * </ul>
 */
@Service
public class ConditionsService {

    private static final Logger log = LoggerFactory.getLogger(ConditionsService.class);

    static final String FREE_FORECAST = "https://api.open-meteo.com/v1/forecast";
    static final String FREE_AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";
    static final String PAID_FORECAST = "https://customer-api.open-meteo.com/v1/forecast";
    static final String PAID_AIR = "https://customer-air-quality-api.open-meteo.com/v1/air-quality";

    static final Duration TTL = Duration.ofMinutes(20);
    private static final int CACHE_MAX = 5_000;
    private static final int HORIZON_HOURS = 6;
    private static final String USER_AGENT = "SitPrep/1.0 (+https://sitprep.app; conditions)";

    private final ObjectMapper mapper;
    private final String apiKey;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    /** Raw parsed upstream bodies per snapped point; the slot is applied on read. */
    private final Map<String, Cached> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_MAX;
                }
            });

    private record Cached(Instant fetchedAt, String forecastJson, String airJson) {}

    public ConditionsService(ObjectMapper mapper,
                             @Value("${conditions.open-meteo.api-key:}") String apiKey) {
        this(mapper, apiKey, Clock.systemUTC());
    }

    ConditionsService(ObjectMapper mapper, String apiKey, Clock clock) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.clock = clock;
    }

    /** Round a coordinate to the 0.1° grid every reading is taken on. */
    public static double snap(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Reading for the conditions endpoint: now and the next six hours. */
    public ConditionsReading readingFor(double lat, double lng) {
        return readingFor(lat, lng, null);
    }

    /**
     * Reading for {@code slot}. The slot only changes the classification (how
     * far ahead heat and cold look), so one cached fetch serves every slot.
     * Returns null when the upstream cannot be read.
     */
    public ConditionsReading readingFor(double lat, double lng, BriefSlot slot) {
        double sLat = snap(lat);
        double sLng = snap(lng);
        String key = String.format(Locale.ROOT, "%.1f|%.1f", sLat, sLng);

        Cached c = cache.get(key);
        if (c == null || c.fetchedAt().plus(TTL).isBefore(clock.instant())) {
            String forecast = get(forecastUrl(sLat, sLng));
            String air = forecast == null ? null : get(airUrl(sLat, sLng));
            if (forecast == null || air == null) return null;
            c = new Cached(clock.instant(), forecast, air);
            cache.put(key, c);
        }
        return parse(c.forecastJson(), c.airJson(), sLat, sLng, slot);
    }

    String forecastUrl(double lat, double lng) {
        return (apiKey.isEmpty() ? FREE_FORECAST : PAID_FORECAST)
                + String.format(Locale.ROOT, "?latitude=%.1f&longitude=%.1f", lat, lng)
                + "&current=temperature_2m,apparent_temperature,wind_speed_10m,wind_direction_10m,"
                + "wind_gusts_10m,weather_code"
                + "&hourly=precipitation_probability,weather_code,wind_speed_10m,wind_gusts_10m,"
                + "apparent_temperature"
                + "&daily=temperature_2m_max,temperature_2m_min,apparent_temperature_max,"
                + "apparent_temperature_min"
                + "&forecast_hours=12&forecast_days=2"
                + "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto"
                + keyParam();
    }

    String airUrl(double lat, double lng) {
        return (apiKey.isEmpty() ? FREE_AIR : PAID_AIR)
                + String.format(Locale.ROOT, "?latitude=%.1f&longitude=%.1f", lat, lng)
                + "&current=us_aqi&hourly=us_aqi&forecast_hours=" + HORIZON_HOURS + "&timezone=auto"
                + keyParam();
    }

    private String keyParam() {
        return apiKey.isEmpty() ? "" : "&apikey=" + apiKey;
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // No URL in the log: it carries the API key on the paid hosts.
                log.warn("Open-Meteo non-200 ({})", res.statusCode());
                return null;
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Open-Meteo fetch failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Shape the two upstream bodies into a reading. Package-private so tests
     * can run it against real captured responses. Returns null on a body that
     * cannot be read rather than throwing.
     */
    ConditionsReading parse(String forecastJson, String airJson, double lat, double lng, BriefSlot slot) {
        try {
            JsonNode f = mapper.readTree(forecastJson);
            JsonNode a = mapper.readTree(airJson);
            JsonNode cur = f.path("current");
            if (cur.isMissingNode() || !cur.has("time")) return null;

            String timezone = text(f.path("timezone"));
            int offset = f.path("utc_offset_seconds").asInt(0);
            Instant observedAt = LocalDateTime.parse(cur.path("time").asText())
                    .toInstant(ZoneOffset.ofTotalSeconds(offset));

            Integer aqiNow = num(a.path("current").path("us_aqi"));
            Integer windDeg = num(cur.path("wind_direction_10m"));
            ConditionsReading.Now now = new ConditionsReading.Now(
                    num(cur.path("temperature_2m")),
                    num(cur.path("apparent_temperature")),
                    num(cur.path("wind_speed_10m")),
                    windDeg,
                    Compass.point(windDeg),
                    num(cur.path("wind_gusts_10m")),
                    num(cur.path("weather_code")),
                    aqiNow,
                    ConditionTiers.aqiCategory(aqiNow));

            JsonNode h = f.path("hourly");
            ConditionsReading.Next6h next = new ConditionsReading.Next6h(
                    maxOf(h.path("precipitation_probability")),
                    maxOf(h.path("wind_speed_10m")),
                    maxOf(h.path("wind_gusts_10m")),
                    maxOf(a.path("hourly").path("us_aqi")),
                    maxOf(h.path("apparent_temperature")),
                    minOf(h.path("apparent_temperature")),
                    anyCode(h.path("weather_code"), true),
                    anyCode(h.path("weather_code"), false));

            JsonNode d = f.path("daily");
            ConditionsReading.Day today = new ConditionsReading.Day(
                    num(d.path("temperature_2m_max").path(0)),
                    num(d.path("temperature_2m_min").path(0)),
                    num(d.path("apparent_temperature_max").path(0)),
                    num(d.path("apparent_temperature_min").path(0)));
            // Tonight's low is tomorrow's daily minimum: today's minimum is the
            // early-morning low, which has already happened by evening.
            ConditionsReading.Day tonight = new ConditionsReading.Day(
                    null,
                    num(d.path("temperature_2m_min").path(1)),
                    null,
                    num(d.path("apparent_temperature_min").path(1)));

            ConditionTiers.Assessment as = ConditionTiers.classify(now, next, today, tonight, slot);
            return new ConditionsReading(lat, lng, timezone, observedAt, now, next, today, tonight,
                    as.condition().name(), as.tier().name());
        } catch (Exception e) {
            log.warn("Open-Meteo parse failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /** A number rounded to the nearest whole unit; null when absent. */
    private static Integer num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isNumber()) return null;
        return (int) Math.round(n.asDouble());
    }

    private static Integer maxOf(JsonNode arr) {
        Integer best = null;
        int i = 0;
        for (JsonNode n : arr) {
            if (i++ >= HORIZON_HOURS) break;
            Integer v = num(n);
            if (v != null && (best == null || v > best)) best = v;
        }
        return best;
    }

    private static Integer minOf(JsonNode arr) {
        Integer best = null;
        int i = 0;
        for (JsonNode n : arr) {
            if (i++ >= HORIZON_HOURS) break;
            Integer v = num(n);
            if (v != null && (best == null || v < best)) best = v;
        }
        return best;
    }

    private static boolean anyCode(JsonNode arr, boolean storm) {
        int i = 0;
        for (JsonNode n : arr) {
            if (i++ >= HORIZON_HOURS) break;
            Integer code = num(n);
            if (storm ? ConditionTiers.isStormCode(code) : ConditionTiers.isRainOrSnowCode(code)) return true;
        }
        return false;
    }
}
