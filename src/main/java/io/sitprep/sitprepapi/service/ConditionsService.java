package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import io.sitprep.sitprepapi.util.Compass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Weather and air quality for a point, from US government sources
 * (docs/epics/daily_summary_gameplan.md §3.3; EXEC-3A, re-sourced in
 * EXEC-3A.1 on 2026-10-02).
 *
 * <ul>
 *   <li><b>Weather: NOAA National Weather Service.</b> {@code /points/{lat},{lng}}
 *       resolves the forecast grid cell and its time zone, then the raw
 *       gridpoint forecast ({@code forecastGridData}) supplies temperature,
 *       feels-like, wind, gusts, precipitation chance and the weather grid as
 *       time series. Public domain, no key; NWS asks for an identifying
 *       User-Agent, the same one {@link NwsZoneService} sends.</li>
 *   <li><b>Air quality: EPA AirNow.</b> The 2026 {@code observation/current/ziplatLong}
 *       service: the closest monitor reading per pollutant within AirNow's own
 *       50-mile lookup boundary (there is no distance parameter). The reading's
 *       AQI is the highest NowCast AQI across the pollutants reported, which is
 *       how EPA states an overall AQI. The 2020-era
 *       {@code aq/observation/latLong/current} service was retired on
 *       2026-10-01 and answers 410. Free, keyed
 *       ({@code conditions.airnow.api-key}, env {@code AIRNOW_API_KEY}).</li>
 *   <li><b>Snapped to 0.1°.</b> Every reading is taken at the point rounded to
 *       a 0.1° grid (about 11 km): the daily-brief cell anchor, an effective
 *       cache key, and never one person's address.</li>
 *   <li><b>Cached.</b> The grid lookup for a point rarely changes, so it is
 *       kept a week. The forecast and the air reading are kept 20 minutes per
 *       snapped point, so the endpoint and the scheduler share one upstream
 *       call. Series values are picked against the clock on every read, so a
 *       cached forecast still answers "now".</li>
 *   <li><b>Soft fail, never guessed.</b> NWS or AirNow unreachable, a blank
 *       AirNow key, no monitor within AirNow's 50-mile boundary, or a forecast without a
 *       current temperature or wind: the reading is null and the brief is
 *       skipped. A missing value is reported as missing, never faked.</li>
 * </ul>
 *
 * <p><b>No hourly AQI forecast.</b> Neither free US source publishes one, so
 * {@code next6h.maxAqi} is always null. The previous source's modelled hourly
 * AQI is not replaced with an invented one.</p>
 */
@Service
public class ConditionsService {

    private static final Logger log = LoggerFactory.getLogger(ConditionsService.class);

    static final String NWS_POINTS = "https://api.weather.gov/points/%.4f,%.4f";
    static final String AIRNOW_CURRENT = "https://www.airnowapi.org/aq/observation/current/ziplatLong";

    static final Duration TTL = Duration.ofMinutes(20);
    /**
     * AirNow observations are hourly, so an hour's cache loses nothing and
     * triples the headroom under AirNow's 500 calls/hour per key — which now
     * matters, because the daily brief reads conditions as feeds load
     * (EXEC-3C, 2026-10-03). NWS stays at {@link #TTL}.
     */
    static final Duration AIR_TTL = Duration.ofMinutes(60);
    static final Duration POINTS_TTL = Duration.ofDays(7);
    private static final int CACHE_MAX = 5_000;
    private static final Duration HORIZON = Duration.ofHours(6);

    /** Same identification NWS asks for as {@code NwsZoneService} and {@code AlertIngestService}. */
    private static final String USER_AGENT = "(SitPrep/sitprep.app, contactus@sitprep.app)";

    private static final double KMH_TO_MPH = 0.621371;

    /**
     * NWS weather-grid coverages that mean "expect it", roughly 60% and up.
     * "chance" and "scattered" (30-50%) do not make it rain-or-snow; that
     * matches the 60% precipitation threshold in {@link ConditionTiers}.
     */
    static final Set<String> LIKELY_COVERAGE = Set.of(
            "likely", "definite", "numerous", "widespread", "occasional", "frequent", "periods", "intermittent");

    /** Coverages too thin to call a thunderstorm risk. */
    static final Set<String> SLIGHT_COVERAGE = Set.of("slight_chance", "isolated");

    static final Set<String> PRECIP_TYPES = Set.of(
            "rain", "rain_showers", "drizzle", "snow", "snow_showers", "freezing_rain", "freezing_drizzle",
            "sleet", "hail", "freezing_spray", "blowing_snow");

    private final ObjectMapper mapper;
    private final String airNowKey;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Point> points = lru();
    private final Map<String, Cached> cache = lru();
    private final Map<String, Cached> airCache = lru();

    /** The grid cell NWS assigns a point. */
    record Point(Instant fetchedAt, String gridDataUrl, String timezone) {}

    /** Raw upstream bodies per snapped point; the slot and the clock are applied on read. */
    private record Cached(Instant fetchedAt, String gridJson, String airJson) {}

    private static <V> Map<String, V> lru() {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_MAX;
            }
        });
    }

    // @Autowired is REQUIRED: with the test constructor below, Spring cannot
    // pick one by itself and the whole context fails to start (caught by the
    // suite before it reached Heroku, 2026-10-01).
    @Autowired
    public ConditionsService(ObjectMapper mapper,
                             @Value("${conditions.airnow.api-key:}") String airNowKey) {
        this(mapper, airNowKey, Clock.systemUTC());
    }

    ConditionsService(ObjectMapper mapper, String airNowKey, Clock clock) {
        this.mapper = mapper;
        this.airNowKey = airNowKey == null ? "" : airNowKey.trim();
        this.clock = clock;
        if (this.airNowKey.isEmpty()) {
            log.info("Conditions: AIRNOW_API_KEY is blank; readings are unavailable until it is set");
        }
    }

    /** Round a coordinate to the 0.1° grid every reading is taken on. */
    public static double snap(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String key(double sLat, double sLng) {
        return String.format(Locale.ROOT, "%.1f|%.1f", sLat, sLng);
    }

    /**
     * The IANA time zone NWS assigns the point, or null outside NWS coverage.
     * Costs at most one {@code /points} call a week per cell and no AirNow
     * call, so the scheduler can place a cell's slots without a reading.
     */
    public String timezoneFor(double lat, double lng) {
        Point p = point(snap(lat), snap(lng));
        return p == null ? null : p.timezone();
    }

    /** Reading for the conditions endpoint: now and the next six hours. */
    public ConditionsReading readingFor(double lat, double lng) {
        return readingFor(lat, lng, null);
    }

    /**
     * Reading for {@code slot}. The slot only changes the classification (how
     * far ahead heat and cold look), so one cached fetch serves every slot.
     * Returns null when either upstream cannot be read.
     */
    public ConditionsReading readingFor(double lat, double lng, BriefSlot slot) {
        if (airNowKey.isEmpty()) return null;
        double sLat = snap(lat);
        double sLng = snap(lng);
        String key = key(sLat, sLng);

        Point p = point(sLat, sLng);
        if (p == null) return null;

        Instant nowTs = clock.instant();
        Cached g = cache.get(key);
        if (g == null || g.fetchedAt().plus(TTL).isBefore(nowTs)) {
            String grid = get(p.gridDataUrl(), true, "NWS gridpoint");
            if (grid == null) return null;
            g = new Cached(nowTs, grid, null);
            cache.put(key, g);
        }
        Cached a = airCache.get(key);
        if (a == null || a.fetchedAt().plus(AIR_TTL).isBefore(nowTs)) {
            String air = get(airUrl(sLat, sLng), false, "AirNow");
            if (air == null) return null;
            a = new Cached(nowTs, null, air);
            airCache.put(key, a);
        }
        return parse(g.gridJson(), a.airJson(), p.timezone(), sLat, sLng, slot);
    }

    private Point point(double sLat, double sLng) {
        String key = key(sLat, sLng);
        Point p = points.get(key);
        if (p != null && !p.fetchedAt().plus(POINTS_TTL).isBefore(clock.instant())) return p;
        String body = get(String.format(Locale.ROOT, NWS_POINTS, sLat, sLng), true, "NWS points");
        if (body == null) return null;
        p = parsePoint(body);
        if (p != null) points.put(key, p);
        return p;
    }

    Point parsePoint(String json) {
        try {
            JsonNode props = mapper.readTree(json).path("properties");
            String grid = text(props.path("forecastGridData"));
            String tz = text(props.path("timeZone"));
            if (grid == null || tz == null) return null;
            ZoneId.of(tz);   // reject a zone Java cannot use before it is cached
            return new Point(clock.instant(), grid, tz);
        } catch (Exception e) {
            log.warn("NWS points parse failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    String airUrl(double lat, double lng) {
        return AIRNOW_CURRENT + "?format=application/json"
                + String.format(Locale.ROOT, "&latitude=%.1f&longitude=%.1f", lat, lng)
                + "&api_key=" + URLEncoder.encode(airNowKey, StandardCharsets.UTF_8);
    }

    private String get(String url, boolean nws, String what) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (nws) b.header("Accept", "application/geo+json");
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // No URL in the log: the AirNow URL carries the API key.
                log.warn("{} non-200 ({})", what, res.statusCode());
                return null;
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("{} fetch failed: {}", what, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Shape the two upstream bodies into a reading at the clock's "now".
     * Package-private so tests can run it against real captured responses.
     * Returns null, never throws, when a required value is missing.
     */
    ConditionsReading parse(String gridJson, String airJson, String timezone, double lat, double lng,
                            BriefSlot slot) {
        try {
            JsonNode g = mapper.readTree(gridJson).path("properties");
            ZoneId zone = ZoneId.of(timezone);
            Instant at = clock.instant();
            Instant hour = at.truncatedTo(ChronoUnit.HOURS);
            Instant horizon = hour.plus(HORIZON);

            // The conversions below assume NWS's SI units. A changed unit is a
            // missing reading, not a silently wrong one.
            if (!unit(g, "temperature", "degC") || !unit(g, "apparentTemperature", "degC")
                    || !unit(g, "windSpeed", "km_h-1") || !unit(g, "windGust", "km_h-1")) {
                log.warn("NWS gridpoint units changed; reading skipped");
                return null;
            }

            Integer aqi = airNowAqi(mapper.readTree(airJson));
            Integer tempF = toF(valueAt(g.path("temperature"), at));
            Integer windMph = toMph(valueAt(g.path("windSpeed"), at));
            if (aqi == null || tempF == null || windMph == null) return null;

            Integer windDeg = round(valueAt(g.path("windDirection"), at));
            List<JsonNode> weatherNow = weatherAt(g.path("weather"), at, at.plusSeconds(1));
            ConditionsReading.Now now = new ConditionsReading.Now(
                    tempF,
                    toF(valueAt(g.path("apparentTemperature"), at)),
                    windMph,
                    windDeg,
                    Compass.point(windDeg),
                    toMph(valueAt(g.path("windGust"), at)),
                    storm(weatherNow),
                    rainOrSnow(weatherNow),
                    aqi,
                    ConditionTiers.aqiCategory(aqi));

            List<JsonNode> weatherSoon = weatherAt(g.path("weather"), hour, horizon);
            ConditionsReading.Next6h next = new ConditionsReading.Next6h(
                    round(extreme(g.path("probabilityOfPrecipitation"), hour, horizon, true)),
                    toMph(extreme(g.path("windSpeed"), hour, horizon, true)),
                    toMph(extreme(g.path("windGust"), hour, horizon, true)),
                    null,
                    toF(extreme(g.path("apparentTemperature"), hour, horizon, true)),
                    toF(extreme(g.path("apparentTemperature"), hour, horizon, false)),
                    storm(weatherSoon),
                    rainOrSnow(weatherSoon));

            LocalDate today = at.atZone(zone).toLocalDate();
            Instant dayStart = today.atStartOfDay(zone).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
            // NWS publishes highs for the daytime period and lows for the
            // overnight period. Today's high starts today. Lows are matched by
            // the morning they END: today's low ends this morning, tonight's
            // ends tomorrow morning. Matching tonight by START date is wrong:
            // NWS truncates the overnight in progress at issue time to a stub
            // that starts this morning (e.g. 12:00Z/PT4H), which would pass
            // for "tonight" (seen live on prod 2026-10-02: 52 instead of 58).
            Interval high = period(g.path("maxTemperature"), zone, today, true);
            Interval lowEndingToday = period(g.path("minTemperature"), zone, today, false);
            Interval tonightLow = period(g.path("minTemperature"), zone, today.plusDays(1), false);

            ConditionsReading.Day dayReading = new ConditionsReading.Day(
                    high == null ? null : toF(high.value()),
                    lowEndingToday == null ? null : toF(lowEndingToday.value()),
                    toF(extreme(g.path("apparentTemperature"), dayStart, dayEnd, true)),
                    toF(extreme(g.path("apparentTemperature"), dayStart, dayEnd, false)));
            ConditionsReading.Day tonight = new ConditionsReading.Day(
                    null,
                    tonightLow == null ? null : toF(tonightLow.value()),
                    null,
                    tonightLow == null ? null
                            : toF(extreme(g.path("apparentTemperature"), tonightLow.start(), tonightLow.end(), false)));

            ConditionTiers.Assessment as = ConditionTiers.classify(now, next, dayReading, tonight, slot);
            return new ConditionsReading(lat, lng, timezone, at.truncatedTo(ChronoUnit.SECONDS), now, next,
                    dayReading, tonight, as.condition().name(), as.tier().name());
        } catch (Exception e) {
            log.warn("Conditions parse failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    // ---- AirNow ----

    /** Highest AQI across the reported pollutants; null when nothing usable was reported. */
    static Integer airNowAqi(JsonNode observations) {
        if (observations == null || !observations.isArray()) return null;
        Integer best = null;
        for (JsonNode o : observations) {
            JsonNode v = o.path("nowcastAQI");
            // AirNow uses -1 for "no value" on some feeds.
            if (!v.isNumber() || v.asInt() < 0) continue;
            if (best == null || v.asInt() > best) best = v.asInt();
        }
        return best;
    }

    // ---- NWS gridpoint time series ----

    /** One entry of a series: a value valid over [start, end). */
    record Interval(Instant start, Instant end, Double value, JsonNode raw) {}

    private static final Pattern DURATION = Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?)?");

    /** Parse NWS {@code validTime}, an ISO instant plus a duration: {@code 2026-10-02T14:00:00+00:00/PT13H}. */
    static Interval interval(JsonNode entry) {
        String vt = text(entry.path("validTime"));
        if (vt == null) return null;
        int slash = vt.indexOf('/');
        if (slash < 0) return null;
        Instant start = java.time.OffsetDateTime.parse(vt.substring(0, slash)).toInstant();
        Matcher m = DURATION.matcher(vt.substring(slash + 1));
        if (!m.matches()) return null;
        long minutes = (m.group(1) == null ? 0 : Long.parseLong(m.group(1))) * 1440
                + (m.group(2) == null ? 0 : Long.parseLong(m.group(2))) * 60
                + (m.group(3) == null ? 0 : Long.parseLong(m.group(3)));
        if (minutes <= 0) return null;
        JsonNode v = entry.path("value");
        return new Interval(start, start.plus(Duration.ofMinutes(minutes)), v.isNumber() ? v.asDouble() : null, v);
    }

    private static List<Interval> series(JsonNode node) {
        List<Interval> out = new ArrayList<>();
        for (JsonNode e : node.path("values")) {
            Interval i = interval(e);
            if (i != null) out.add(i);
        }
        return out;
    }

    /** The value whose interval contains {@code at}, or null. */
    static Double valueAt(JsonNode node, Instant at) {
        for (Interval i : series(node)) {
            if (!at.isBefore(i.start()) && at.isBefore(i.end())) return i.value();
        }
        return null;
    }

    /** Max (or min) over every value whose interval overlaps [from, to). */
    static Double extreme(JsonNode node, Instant from, Instant to, boolean max) {
        Double best = null;
        for (Interval i : series(node)) {
            if (i.value() == null || !i.start().isBefore(to) || !i.end().isAfter(from)) continue;
            if (best == null || (max ? i.value() > best : i.value() < best)) best = i.value();
        }
        return best;
    }

    /**
     * The daily period (NWS {@code maxTemperature}/{@code minTemperature}) that
     * starts ({@code byStart}) or ends on {@code day} in the local zone.
     */
    static Interval period(JsonNode node, ZoneId zone, LocalDate day, boolean byStart) {
        for (Interval i : series(node)) {
            if (i.value() == null) continue;
            Instant edge = byStart ? i.start() : i.end();
            if (edge.atZone(zone).toLocalDate().equals(day)) return i;
        }
        return null;
    }

    /** Weather-grid entries (coverage, weather, intensity) valid at any time in [from, to). */
    static List<JsonNode> weatherAt(JsonNode node, Instant from, Instant to) {
        List<JsonNode> out = new ArrayList<>();
        for (Interval i : series(node)) {
            if (!i.start().isBefore(to) || !i.end().isAfter(from)) continue;
            if (i.raw().isArray()) i.raw().forEach(out::add);
        }
        return out;
    }

    static boolean storm(List<JsonNode> weather) {
        for (JsonNode w : weather) {
            String coverage = text(w.path("coverage"));
            if ("thunderstorms".equals(text(w.path("weather")))
                    && coverage != null && !SLIGHT_COVERAGE.contains(coverage)) {
                return true;
            }
        }
        return false;
    }

    static boolean rainOrSnow(List<JsonNode> weather) {
        for (JsonNode w : weather) {
            String type = text(w.path("weather"));
            String coverage = text(w.path("coverage"));
            if (type != null && PRECIP_TYPES.contains(type) && coverage != null && LIKELY_COVERAGE.contains(coverage)) {
                return true;
            }
        }
        return false;
    }

    // ---- units ----

    /** True when the series is absent (nothing to convert) or in {@code expected} units. */
    private static boolean unit(JsonNode grid, String field, String expected) {
        JsonNode n = grid.path(field);
        if (n.isMissingNode()) return true;
        String uom = text(n.path("uom"));
        return uom != null && uom.endsWith(":" + expected);
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static Integer round(Double v) {
        return v == null ? null : (int) Math.round(v);
    }

    /** NWS temperatures are degrees Celsius. */
    static Integer toF(Double c) {
        return c == null ? null : (int) Math.round(c * 9.0 / 5.0 + 32.0);
    }

    /** NWS speeds are km/h. */
    static Integer toMph(Double kmh) {
        return kmh == null ? null : (int) Math.round(kmh * KMH_TO_MPH);
    }
}
