package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls public emergency-alert sources on a fixed schedule and exposes a
 * consolidated, cached snapshot to the rest of the app via
 * {@link #getSnapshot()}.
 *
 * <p>Phase 1 of the alert backend (per docs/ALERTS_INTEGRATION.md build
 * order step 5). Today: NWS active alerts only, in-memory cache, single
 * server-wide snapshot. Phase 2 adds USGS, persistence (AlertSubscription
 * + AlertPost), geocell-scoped dedup, and STOMP-broadcast auto-posts.</p>
 *
 * <p><b>Why centralize:</b> the FE was hitting NWS once per page-load via
 * {@code fetchEmergencySnapshot} on the FemaWeatherMVP, MapView, and
 * CrisisBand surfaces. With ~25 beta testers across N pages each, that
 * was N×25 NWS calls per session. NWS is rate-limited per-IP — a single
 * server-side poll every 5min keeps us well under budget regardless of
 * frontend traffic, and the cache is shared across all users.</p>
 *
 * <p><b>Failure mode:</b> on poll error (network, NWS 5xx, JSON shape
 * change), we keep serving the previous snapshot. The {@link Snapshot}
 * carries {@code lastSuccessAt} so the FE can render a "last updated"
 * affordance and decide whether to fall back to its own direct NWS call
 * if the server-side data is stale.</p>
 */
@Service
public class AlertIngestService {

    private static final Logger log = LoggerFactory.getLogger(AlertIngestService.class);

    /** All active US alerts, single GET. Returns a GeoJSON FeatureCollection. */
    private static final String NWS_ACTIVE_URL = "https://api.weather.gov/alerts/active";

    /**
     * USGS recent significant quakes — M4.5+ in the last 24h, global.
     * Tractable size (typically 5-30 features). Lower magnitudes flood the
     * cache with non-actionable signals; consumers can still get more via
     * USGS direct query if they need it.
     */
    private static final String USGS_RECENT_URL =
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson";

    /**
     * FEMA OpenFEMA disaster declarations.
     *
     * <p><b>{@code incidentEndDate eq null} is not "active" (audit P1-2).</b>
     * Measured 2026-08-22: it returns <b>628 rows</b>, deduping to 299
     * disasters, of which <b>211 were declared 3+ years ago</b> — the oldest a
     * Kentucky fire complex from <b>November 2000</b>. 296 of the 299 are Fire
     * Management Assistance grants, which FEMA routinely never closes out. The
     * old comment on this constant claimed "typical active set is well under
     * 200 nationwide"; it was wrong by 3x and the filter was wrong in kind.</p>
     *
     * <p>The real recency signals are on the row and were being ignored:
     * {@code declarationDate}, {@code disasterCloseoutDate},
     * {@code iaProgramDeclared} / {@code ihProgramDeclared},
     * {@code lastIAFilingDate}. The frontend has filtered on all of them
     * correctly since it shipped ({@code emergencyApis.isActiveRecovery}); the
     * server never did. See {@link #isActiveRecovery}.</p>
     *
     * <p><b>The base query itself was wrong, not just under-filtered.</b>
     * Verified against the live API on 2026-08-22:</p>
     *
     * <pre>
     *   $filter=incidentEndDate eq null
     *           and (iaProgramDeclared eq true or ihProgramDeclared eq true)
     *   -> count: 0
     * </pre>
     *
     * <p>Those two conditions are mutually exclusive. FEMA leaves
     * {@code incidentEndDate} null mostly on Fire Management Assistance grants
     * — which reimburse a <i>state</i> for firefighting costs and offer a
     * household nothing — while the major disaster declarations that do offer
     * Individual Assistance get an end date. So the old query could not have
     * returned a single row a person could act on, no matter what we filtered
     * it down to afterwards. It returned 299 things nobody could use.</p>
     *
     * <p>Replaced with a <b>recency + assistance</b> query, filtered
     * server-side: 359 live rows, including real recent declarations
     * (DR-4922-MS severe storms, DR-4932-WV flooding). {@link #isActiveRecovery}
     * then applies the two conditions OData cannot express well — closeout and
     * the IA filing deadline.</p>
     *
     * <p>{@code $top} + {@code $skip} paginate — the old {@code $top=500} was a
     * silent truncation of 128 rows, and the set only grows.</p>
     */
    private static final int FEMA_PAGE_SIZE = 500;

    /** {@code %s} is the ISO date 18 months back; {@code %d} the skip offset. */
    private static final String FEMA_QUERY_FMT =
            "https://www.fema.gov/api/open/v2/DisasterDeclarationsSummaries"
                    + "?$filter=declarationDate%%20ge%%20%%27%s%%27"
                    + "%%20and%%20(iaProgramDeclared%%20eq%%20true"
                    + "%%20or%%20ihProgramDeclared%%20eq%%20true)"
                    + "&$orderby=declarationDate%%20desc"
                    + "&$top=" + FEMA_PAGE_SIZE + "&$skip=%d";

    /** Stop after this many pages — a guard against an unbounded upstream. */
    private static final int FEMA_MAX_PAGES = 6;

    /**
     * How recent a declaration must be to count as active recovery. Mirrors
     * the frontend's 18-month window so the two agree.
     */
    private static final Duration FEMA_RECENT_WINDOW = Duration.ofDays(18 * 30);

    /** NWS asks for a User-Agent identifying the consumer. */
    private static final String USER_AGENT =
            "(SitPrep/sitprep.app, contactus@sitprep.app)";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Resolves UGC zone codes for the 82% of alerts that ship no polygon
     * (audit P0-4). Read path uses it to answer "does this zone contain the
     * user"; the ingest tick warms centroids for the dispatch path.
     */
    private final NwsZoneService zoneService;

    @org.springframework.beans.factory.annotation.Value("${alerts.ingest.primeOnStartup:true}")
    private boolean primeOnStartupEnabled = true;

    public AlertIngestService(NwsZoneService zoneService) {
        this.zoneService = zoneService;
    }

    /**
     * Latest snapshot. AtomicReference so the scheduled writer and the
     * resource-thread readers don't need a lock — they swap whole
     * snapshot objects.
     */
    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(Snapshot.empty());

    /**
     * Force a poll on startup so the first request after boot has data to
     * serve. Without this, the cache is empty until the first scheduled tick.
     *
     * <p><b>Gated by {@code alerts.ingest.primeOnStartup}, false in the test
     * profile.</b> Every Spring-context test booted this, so a plain
     * {@code mvn package} fired real requests at api.weather.gov,
     * earthquake.usgs.gov and fema.gov from whatever machine ran it — a
     * laptop, CI, a Heroku build dyno. That predates the alert epic and was
     * ~3 requests; P0-4's zone warming briefly made it ~900 before P1-11
     * bounded it. Neither is something a build should do: the eventual
     * consequence is a rate-limit block, and a block on api.weather.gov takes
     * the whole alert pipeline down.</p>
     */
    @PostConstruct
    public void primeOnStartup() {
        if (!primeOnStartupEnabled) {
            log.info("AlertIngest: startup prime disabled (alerts.ingest.primeOnStartup=false)");
            return;
        }
        // Prime in a separate thread so a slow upstream doesn't block app
        // startup. If a prime fails, the @Scheduled tick will retry.
        new Thread(() -> {
            try { refreshNow(); } catch (Exception ignored) {}
        }, "alert-ingest-prime").start();
    }

    /**
     * Poll NWS + USGS + FEMA every {@code alerts.ingest.intervalMs} (default
     * 5 minutes = 300000 ms). {@code fixedDelay} (not {@code fixedRate}) so a
     * slow poll doesn't queue up another. {@code initialDelay} of 60s lets
     * the @PostConstruct prime finish first. Both polls run sequentially in
     * this method — they're cheap enough that parallelism isn't worth the
     * extra complexity, and a sequential failure is easier to reason about
     * in logs.
     *
     * <p>Property-driven (2026-06-07 phase-2 memory trim) so we can dial the
     * interval up without a redeploy if the dyno is pressured: each poll
     * deserializes ~hundreds of KB of JSON across NWS+USGS+FEMA into the
     * young gen — at 60s that's ~60 churns/hr per source, and on a 512 MB
     * dyno the GC pressure showed up as elevated R14s. 5 min is the
     * baseline; bump to 600000 (10 min) or higher via Heroku config var if
     * needed. Lower bound is whatever NWS rate-limits accept (~30s).</p>
     */
    @Scheduled(
            fixedDelayString = "${alerts.ingest.intervalMs:300000}",
            initialDelayString = "${alerts.ingest.initialDelayMs:60000}")
    public void scheduledPoll() {
        refreshNow();
    }

    /**
     * Run both upstream polls and merge results into a single snapshot.
     * Each source is independent — if NWS fails but USGS succeeds we
     * still update with the USGS half (and vice versa), preserving the
     * other source's last-good data.
     */
    private void pollAll() {
        // Each source updates the snapshot independently so a failure in
        // one doesn't drop the other.
        Snapshot prev = latest.get();

        List<NormalizedAlert> nws;
        try {
            nws = pollNws();
        } catch (Exception e) {
            log.warn("AlertIngest: NWS poll failed: {}", e.getMessage());
            // Keep previous NWS data
            nws = prev.alerts.stream().filter(a -> "NWS".equals(a.source())).toList();
        }

        List<NormalizedAlert> usgs;
        try {
            usgs = pollUsgs();
        } catch (Exception e) {
            log.warn("AlertIngest: USGS poll failed: {}", e.getMessage());
            usgs = prev.alerts.stream().filter(a -> "USGS".equals(a.source())).toList();
        }

        List<NormalizedAlert> fema;
        try {
            fema = pollFema();
        } catch (Exception e) {
            log.warn("AlertIngest: FEMA poll failed: {}", e.getMessage());
            fema = prev.alerts.stream().filter(a -> "FEMA".equals(a.source())).toList();
        }

        List<NormalizedAlert> merged = new ArrayList<>(nws.size() + usgs.size() + fema.size());
        merged.addAll(nws);
        merged.addAll(usgs);
        merged.addAll(fema);

        Snapshot next = new Snapshot(
                List.copyOf(merged),
                Instant.now(),
                Instant.now()
        );
        latest.set(next);

        // Queue centroid resolution for any zone we haven't seen before, so
        // the dispatch tick (which runs 5 min behind and must not block on an
        // upstream call inside its transaction) finds them already cached.
        // Steady-state this queues nothing — the set of active zones barely
        // moves between polls.
        // ONE zone per alert, not all of them. resolveDispatchCoord wants a
        // single representative coordinate and takes the first zone that has
        // one, so warming every code an alert lists multiplied the work by
        // ~3.5x for no benefit — 909 fetches where 254 would do.
        Set<String> zones = new HashSet<>();
        for (NormalizedAlert a : merged) {
            if (a.geometry() == null && a.ugc() != null && !a.ugc().isEmpty()) {
                zones.add(a.ugc().get(0));
            }
        }
        zoneService.warmZones(zones);
    }

    private List<NormalizedAlert> pollNws() throws Exception {
        long started = System.currentTimeMillis();
        List<NormalizedAlert> normalized = parseNwsFeed(fetchJson(NWS_ACTIVE_URL, "application/geo+json"));
        log.info("AlertIngest: NWS poll OK — {} alerts ingested in {}ms",
                normalized.size(), System.currentTimeMillis() - started);
        return normalized;
    }

    /**
     * Parse a NWS {@code /alerts/active} FeatureCollection into normalized
     * alerts. Split out from {@link #pollNws()} so the parse can be exercised
     * against a captured live feed without a network call — the regression
     * contract for audit P0-4 is "does the real feed still normalize the way
     * we measured", which a hand-built fixture cannot answer.
     */
    List<NormalizedAlert> parseNwsFeed(JsonNode root) {
        JsonNode features = root == null ? null : root.path("features");
        if (features == null || !features.isArray()) {
            log.warn("AlertIngest: NWS response had no 'features' array; " +
                    "treating as empty for this tick.");
            return List.of();
        }

        List<NormalizedAlert> normalized = new ArrayList<>(features.size());
        int nonActual = 0;
        Iterator<JsonNode> it = features.elements();
        while (it.hasNext()) {
            JsonNode f = it.next();
            try {
                NormalizedAlert a = normalizeNws(f);

                // DROP anything that is not a real alert (audit P0-3).
                //
                // `/alerts/active` is not filtered by status, and the measured
                // 2026-08-22 feed contained a live `status: Test` row. Nothing
                // downstream checks — so a test Tornado Warning would have
                // matched a template, produced an auto-post, and fired an APNs
                // time-sensitive push. The FE's own direct query has always
                // passed `status=actual`; the server-side poll never did.
                if (a.status() != null && !"Actual".equalsIgnoreCase(a.status())) {
                    nonActual++;
                    continue;
                }
                normalized.add(a);
            } catch (Exception ex) {
                // Skip individual feature parse errors — don't drop the
                // whole batch because one alert was malformed.
                log.debug("AlertIngest: skipped malformed NWS feature: {}", ex.getMessage());
            }
        }
        if (nonActual > 0) {
            log.info("AlertIngest: dropped {} non-Actual NWS message(s) (test/exercise/draft)", nonActual);
        }
        return normalized;
    }

    /** Install a snapshot directly, bypassing the network (tests only). */
    void setSnapshotForTest(List<NormalizedAlert> alerts) {
        latest.set(new Snapshot(List.copyOf(alerts), Instant.now(), Instant.now()));
    }

    /**
     * Poll FEMA active disaster declarations and dedupe by
     * {@code femaDeclarationString}. FEMA emits one row per designated
     * county, so a hurricane affecting 30 counties shows up as 30 rows
     * — we collapse those into one alert per disaster with a
     * comma-joined area string. {@code $orderby=declarationDate desc}
     * means dedup keeps the most recent row's metadata; secondary rows
     * just contribute their county to the area list.
     *
     * <p><b>Severity:</b> presidential disaster declarations are by
     * definition major events (the trigger is "beyond state and local
     * capacity"). We mark all FEMA alerts {@code "Severe"} so they
     * pass CrisisBand's Severe+Extreme filter — but consumers can still
     * route by {@code source} when they want different UX for
     * recovery-phase declarations vs. NWS warning-phase ones.</p>
     *
     * <p><b>Geometry:</b> FEMA returns county/state names, not
     * polygons. We emit {@code geometry = null}, which falls into
     * {@code getSnapshotForPoint}'s "include unconditionally" branch.
     * Coarse but safe — these are always broad-impact.</p>
     */
    private List<NormalizedAlert> pollFema() throws Exception {
        long started = System.currentTimeMillis();

        java.util.LinkedHashMap<String, FemaAccum> byDecl = new java.util.LinkedHashMap<>();
        int totalRows = 0, skippedStale = 0;

        for (int page = 0; page < FEMA_MAX_PAGES; page++) {
            JsonNode root = fetchJson(femaPageUrl(page), "application/json");
            JsonNode rows = root.path("DisasterDeclarationsSummaries");
            if (!rows.isArray()) {
                log.warn("AlertIngest: FEMA response had no 'DisasterDeclarationsSummaries' array.");
                break;
            }
            if (rows.isEmpty()) break;
            totalRows += rows.size();

            Iterator<JsonNode> it = rows.elements();
            while (it.hasNext()) {
                JsonNode r = it.next();
                String key = textOrNull(r, "femaDeclarationString");
                if (key == null) continue;
                if (!byDecl.containsKey(key) && !isActiveRecovery(r)) {
                    skippedStale++;
                    continue;
                }
                FemaAccum acc = byDecl.computeIfAbsent(key, k -> new FemaAccum(r));
                String area = textOrNull(r, "designatedArea");
                if (area != null && !acc.areas.contains(area)) acc.areas.add(area);
            }

            if (rows.size() < FEMA_PAGE_SIZE) break;   // last page
            if (page == FEMA_MAX_PAGES - 1) {
                log.warn("AlertIngest: FEMA paging stopped at the {}-page cap; "
                        + "some declarations were not read.", FEMA_MAX_PAGES);
            }
        }

        List<NormalizedAlert> normalized = new ArrayList<>(byDecl.size());
        for (FemaAccum acc : byDecl.values()) {
            try {
                normalized.add(normalizeFema(acc));
            } catch (Exception ex) {
                log.debug("AlertIngest: skipped malformed FEMA row: {}", ex.getMessage());
            }
        }

        log.info("AlertIngest: FEMA poll OK — {} active disasters from {} rows "
                        + "({} rows dropped as not-active-recovery) in {}ms",
                normalized.size(), totalRows, skippedStale, System.currentTimeMillis() - started);
        return normalized;
    }

    /** Page URL for the recency + assistance query. Package-private for tests. */
    static String femaPageUrl(int page) {
        String since = java.time.LocalDate.ofInstant(
                Instant.now().minus(FEMA_RECENT_WINDOW), java.time.ZoneOffset.UTC).toString();
        return String.format(Locale.ROOT, FEMA_QUERY_FMT, since, page * FEMA_PAGE_SIZE);
    }

    /**
     * Is this declaration something a person could still act on?
     *
     * <p>Port of the frontend's {@code isActiveRecovery}
     * ({@code emergencyApis.js}), which has been correct since it shipped
     * while the server ingested everything. Four conditions, all from fields
     * already on the row:</p>
     *
     * <ul>
     *   <li>offers individual help — {@code ihProgramDeclared} or
     *       {@code iaProgramDeclared}. Also enforced server-side in the query;
     *       kept here as defence in depth, because the query is a string and
     *       this is not;</li>
     *   <li>not closed out;</li>
     *   <li>declared within {@link #FEMA_RECENT_WINDOW};</li>
     *   <li>its IA filing deadline, where one exists, has not passed.</li>
     * </ul>
     */
    static boolean isActiveRecovery(JsonNode r) {
        if (r == null) return false;

        boolean offersHelp = r.path("ihProgramDeclared").asBoolean(false)
                || r.path("iaProgramDeclared").asBoolean(false);
        if (!offersHelp) return false;

        if (textOrNull(r, "disasterCloseoutDate") != null) return false;

        Instant declared = parseFemaDate(textOrNull(r, "declarationDate"));
        if (declared == null) return false;
        if (declared.isBefore(Instant.now().minus(FEMA_RECENT_WINDOW))) return false;

        Instant lastFiling = parseFemaDate(textOrNull(r, "lastIAFilingDate"));
        if (lastFiling != null && lastFiling.isBefore(Instant.now())) return false;

        return true;
    }

    /** FEMA dates are ISO-8601 with a zone; tolerate anything unparseable. */
    private static Instant parseFemaDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return java.time.OffsetDateTime.parse(iso).toInstant(); }
        catch (Exception ignored) { }
        try { return Instant.parse(iso); }
        catch (Exception ignored) { return null; }
    }

    private List<NormalizedAlert> pollUsgs() throws Exception {
        long started = System.currentTimeMillis();
        JsonNode root = fetchJson(USGS_RECENT_URL, "application/geo+json");
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            log.warn("AlertIngest: USGS response had no 'features' array.");
            return List.of();
        }

        List<NormalizedAlert> normalized = new ArrayList<>(features.size());
        Iterator<JsonNode> it = features.elements();
        while (it.hasNext()) {
            JsonNode f = it.next();
            try {
                normalized.add(normalizeUsgs(f));
            } catch (Exception ex) {
                log.debug("AlertIngest: skipped malformed USGS feature: {}", ex.getMessage());
            }
        }

        log.info("AlertIngest: USGS poll OK — {} quakes ingested in {}ms",
                normalized.size(), System.currentTimeMillis() - started);
        return normalized;
    }

    /**
     * Shared HTTP fetch + JSON parse. Throws on non-2xx so the caller's
     * error path (preserve previous snapshot) fires.
     */
    private JsonNode fetchJson(String url, String accept) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept + ", application/json;q=0.9, */*;q=0.8")
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("Upstream " + url + " returned HTTP " + code);
        }
        return json.readTree(resp.body());
    }

    private NormalizedAlert normalizeNws(JsonNode f) {
        JsonNode p = f.path("properties");
        String id = textOrNull(p, "id");
        if (id == null) id = textOrNull(f, "id");

        String headline = textOrNull(p, "headline");
        if (headline == null) {
            String event = textOrNull(p, "event");
            String area = textOrNull(p, "areaDesc");
            headline = (event != null && area != null) ? event + " — " + area
                    : (event != null) ? event
                    : "Weather Alert";
        }

        // Geometry as raw map so it serializes back to GeoJSON. NWS may
        // return null geometry for alerts without polygons (e.g. some
        // SAME-code-only zones); we pass through as-is.
        JsonNode geomNode = f.path("geometry");
        Object geometry = geomNode.isMissingNode() || geomNode.isNull()
                ? null
                : json.convertValue(geomNode, Map.class);

        // UGC / SAME — the targeting keys for the 82% of alerts that ship no
        // polygon. Both live under `properties.geocode` and were previously
        // dropped at this normalizer, which is what made a zone-only alert
        // indistinguishable from an alert with no location at all.
        JsonNode geocode = p.path("geocode");
        List<String> ugc = stringList(geocode.path("UGC"));
        List<String> same = stringList(geocode.path("SAME"));

        // CAP `references` — the alerts THIS message replaces. Same drop as UGC
        // and SAME had: the normalizer read fifteen `properties.*` fields and
        // never touched this one, so the supersession edge died here and the
        // "Replaced by ..." surface had no source at all.
        //
        // Shape is an array of OBJECTS, not strings — {"@id", "identifier",
        // "sender", "sent"} — so it needs its own extractor. `identifier` is in
        // the SAME namespace as `properties.id` (both `urn:oid:2.49.0.1.840...`),
        // which is what makes the edge joinable at all.
        List<String> references = identifierList(p.path("references"));

        return new NormalizedAlert(
                id,
                "NWS",
                textOrNull(p, "event"),
                textOrNull(p, "severity"),
                textOrNull(p, "urgency"),
                textOrNull(p, "certainty"),
                textOrNull(p, "messageType"),
                textOrNull(p, "status"),
                textOrNull(p, "response"),
                headline,
                textOrNull(p, "description"),
                textOrNull(p, "instruction"),
                textOrNull(p, "areaDesc"),
                isoOrNull(p, "onset", "effective", "sent"),
                isoOrNull(p, "ends", "expires"),
                geometry,
                ugc,
                same,
                references
        );
    }

    /**
     * CAP {@code references} -> the identifiers it names, empty when absent.
     *
     * <p>Tolerates the entries being bare strings as well as objects: the NWS
     * GeoJSON ships objects, but the same field is a space-delimited string in
     * raw CAP XML, and a future source may hand us either.</p>
     */
    private static List<String> identifierList(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            String id = n.isObject() ? n.path("identifier").asText("") : n.asText("");
            if (!id.isEmpty()) out.add(id);
        }
        return List.copyOf(out);
    }

    /** GeoJSON string array -> immutable List, empty when absent or malformed. */
    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            String s = n.asText("");
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }

    /**
     * Per-disaster scratch holder used during FEMA dedup. Carries the
     * first row's metadata (declarationTitle, incidentType, dates) plus
     * a deduplicated list of designated areas as more rows for the same
     * declaration arrive.
     */
    private static final class FemaAccum {
        final JsonNode firstRow;
        final List<String> areas = new ArrayList<>();
        FemaAccum(JsonNode firstRow) { this.firstRow = firstRow; }
    }

    private NormalizedAlert normalizeFema(FemaAccum acc) {
        JsonNode r = acc.firstRow;
        String id = textOrNull(r, "femaDeclarationString");
        String incidentType = textOrNull(r, "incidentType");
        String title = textOrNull(r, "declarationTitle");
        String state = textOrNull(r, "state");

        // Headline carries the incident type word (Hurricane, Fire,
        // Flood, etc.) so the FE's existing keyword-based tagForAlert
        // mapping works against FEMA alerts without a code change.
        String headline;
        if (incidentType != null && title != null) {
            headline = incidentType + " — " + title;
        } else if (incidentType != null) {
            headline = incidentType + (state != null ? " in " + state : "");
        } else if (title != null) {
            headline = title;
        } else {
            headline = "FEMA Disaster Declaration";
        }

        String area;
        if (!acc.areas.isEmpty()) {
            area = String.join(", ", acc.areas);
            // Cap the joined string so headlines don't blow up on
            // statewide declarations with 50+ counties.
            if (area.length() > 240) area = area.substring(0, 237) + "…";
        } else {
            area = state;
        }

        return new NormalizedAlert(
                id,
                "FEMA",
                /* event */ null,   // FEMA has no NWS-style product vocabulary
                // "Minor" on the CAP scale — "minimal to no known threat to
                // life or property" — which is what a recovery declaration
                // actually is (audit P1-2). This was hard-coded "Severe" with
                // the reasoning that a federal declaration is by definition a
                // major event. True of the EVENT, false of the ALERT: the
                // disaster already happened, and what remains is an assistance
                // programme. Meanwhile `severity` is the field every consumer
                // routes on, so 299 declarations — 211 of them 3+ years old —
                // were passing CrisisBand's Severe+Extreme gate and driving a
                // red life-safety band on a calm day. Downgrading here is what
                // stops that, rather than each consumer special-casing FEMA.
                "Minor",
                /* urgency */ null, /* certainty */ null, /* messageType */ null,
                /* status */ null, /* response */ null,
                headline,
                title,
                /* instruction */ null,   // FEMA declarations carry no CAP instruction
                area,
                textOrNull(r, "incidentBeginDate"),
                textOrNull(r, "incidentEndDate"),  // null for active declarations
                /* geometry */ null,
                // FEMA rows carry county/state NAMES, not UGC or SAME codes.
                // They are genuinely unlocated as far as this pipeline is
                // concerned — see the FEMA branch of getSnapshotForPoint.
                List.of(),
                List.of(),
                /* references */ List.of()   // no CAP supersession vocabulary
        );
    }

    /**
     * USGS quake → NormalizedAlert. Severity is derived from magnitude
     * (M6+ Severe, M5+ Moderate, else Minor) so consumers (CrisisBand
     * filter, AlertPost dispatcher) can route by severity without
     * knowing about magnitudes specifically.
     */
    private NormalizedAlert normalizeUsgs(JsonNode f) {
        JsonNode p = f.path("properties");
        String id = textOrNull(f, "id");

        double mag = p.path("mag").asDouble(0.0);
        String place = textOrNull(p, "place");
        String headline = String.format(
                "M%.1f — %s",
                mag,
                place != null ? place : "Earthquake"
        );

        String severity =
                mag >= 6.0 ? "Severe" :
                mag >= 5.0 ? "Moderate" :
                "Minor";

        // USGS time is epoch-millis. Convert to ISO-8601 string for
        // schema parity with NWS.
        String startedAt = null;
        long timeMs = p.path("time").asLong(0L);
        if (timeMs > 0) startedAt = Instant.ofEpochMilli(timeMs).toString();

        // Quake geometry is a Point [lon, lat, depth].
        JsonNode geomNode = f.path("geometry");
        Object geometry = geomNode.isMissingNode() || geomNode.isNull()
                ? null
                : json.convertValue(geomNode, Map.class);

        return new NormalizedAlert(
                id,
                "USGS",
                /* event */ null,   // quakes carry magnitude, not a product name
                severity,
                /* urgency */ null, /* certainty */ null, /* messageType */ null,
                /* status */ null, /* response */ null,
                headline,
                textOrNull(p, "title"),
                /* instruction */ null,   // USGS quakes carry no CAP instruction
                place,
                startedAt,
                /* endsAt */ null, // quakes are point-in-time
                geometry,
                // USGS quakes always carry a Point geometry, so zone matching
                // never applies to them.
                List.of(),
                List.of(),
                /* references */ List.of()   // no CAP supersession vocabulary
        );
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    /**
     * Try several timestamp fields in order; return the first one that
     * parses. NWS uses {@code onset|effective|sent} for start and
     * {@code ends|expires} for end with field availability varying by
     * alert type.
     */
    private static String isoOrNull(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = textOrNull(n, f);
            if (v != null) return v;
        }
        return null;
    }

    /** Read-only access for the AlertResource — full snapshot, no filter. */
    public Snapshot getSnapshot() {
        return latest.get();
    }

    /**
     * Filtered snapshot: only alerts whose geometry's centroid falls
     * within {@code radiusMi} of {@code (lat, lng)}. Alerts with no
     * geometry are included unconditionally (they may apply broadly —
     * e.g. SAME-code NWS zones without polygon data, FEMA recovery
     * declarations once Phase 2 ingests them).
     *
     * <p>Coarse filter — uses the alert geometry's first vertex (Point
     * for quakes; first ring vertex for polygons) rather than a true
     * point-in-polygon test. Adequate for cutting a 387-alert NWS feed
     * down to the dozen relevant to a user's area; precise filtering
     * happens client-side via Leaflet's geometry rendering.</p>
     */
    public Snapshot getSnapshotForPoint(double lat, double lng, double radiusMi) {
        Snapshot s = latest.get();
        double radiusKm = radiusMi * 1.609344;

        // Zone codes covering this coordinate. Empty means "we don't know" —
        // NOT "no zones apply" — and the ladder below degrades accordingly.
        Set<String> userZones = zoneService.zoneCodesForPoint(lat, lng);
        Set<String> userStates = statePrefixes(userZones);

        List<NormalizedAlert> filtered = new ArrayList<>();
        for (NormalizedAlert a : s.alerts) {
            if (matchesPoint(a, lat, lng, radiusKm, userZones, userStates)) filtered.add(a);
        }
        return new Snapshot(List.copyOf(filtered), Instant.now(), s.lastSuccessAt);
    }

    /**
     * Does this alert apply at this coordinate?
     *
     * <p>Three tiers, most precise first (audit P0-4 / P1-1). Before this,
     * there was one rule — "has geometry? radius-test it; otherwise include" —
     * which returned <b>554 alerts / 307 KB</b> to a Salt Lake City user of
     * which <b>exactly one</b> was near them, because 553 of the 554 had no
     * geometry and took the include-everything branch.</p>
     *
     * <ol>
     *   <li><b>Polygon.</b> Unchanged coordinate/radius test. Applies to the
     *       ~18% of alerts NWS ships with geometry plus every USGS quake.</li>
     *   <li><b>Zone code.</b> The alert's UGC set intersected with the codes
     *       covering the user's point. Exact, and it is what NWS intends —
     *       {@code AZZ560} either contains you or it does not. Note this
     *       deliberately ignores {@code radiusMi}: a zone is a containment
     *       question, not a distance one, and pretending otherwise is what
     *       made the radius parameter meaningless.</li>
     *   <li><b>State prefix.</b> When the point lookup failed, fall back to
     *       the first two characters of the UGC ({@code AZ}Z560). Coarse, but
     *       it turns "every alert in the country" into "every alert in your
     *       state" for free, with no network call.</li>
     * </ol>
     *
     * <p>An alert with no geometry, no UGC and no state to compare against is
     * still <b>included</b>. That is the FEMA case — those rows carry county
     * and state <i>names</i>, never codes. Including them is wrong in the
     * small (a Utah user sees a Florida declaration) and right in the large:
     * this method must never be the reason a real alert is not shown. Fixing
     * FEMA properly is audit P1-2, which filters the set down at ingest.</p>
     */
    private static boolean matchesPoint(NormalizedAlert a,
                                        double lat,
                                        double lng,
                                        double radiusKm,
                                        Set<String> userZones,
                                        Set<String> userStates) {
        return matchTypeFor(a, lat, lng, radiusKm, userZones, userStates) != null;
    }

    /**
     * <b>Why</b> this alert applies at this point, or null when it does not.
     *
     * <p>Same ladder as {@link #matchesPoint}, returning the tier that matched
     * rather than a boolean. The frontend needs this: an alert matched by
     * polygon is "this covers your street", one matched by state prefix is
     * "somewhere in your state", and a broadcast row is "we could not tell".
     * Rendering all three identically is how a nationwide FEMA row reads as
     * local news.</p>
     */
    static MatchType matchTypeFor(NormalizedAlert a,
                                  double lat,
                                  double lng,
                                  double radiusKm,
                                  Set<String> userZones,
                                  Set<String> userStates) {
        // Tier 1 — real geometry.
        double[] coord = firstCoord(a.geometry());
        if (coord != null) {
            return haversineKm(lat, lng, coord[1], coord[0]) <= radiusKm
                    ? MatchType.POLYGON : null;
        }

        List<String> ugc = a.ugc();
        if (ugc == null || ugc.isEmpty()) {
            return MatchType.BROADCAST;   // nothing to match on — see Javadoc
        }

        // Tier 2 — exact zone containment.
        if (!userZones.isEmpty()) {
            for (String code : ugc) {
                if (userZones.contains(code.toUpperCase(Locale.ROOT))) return MatchType.ZONE;
            }
            // The user's zones ARE known and this alert does not target any of
            // them. That is a definite no, not an unknown.
            return null;
        }

        // Tier 3 — state prefix, when the point lookup was unavailable.
        if (!userStates.isEmpty()) {
            for (String code : ugc) {
                if (code.length() >= 2
                        && userStates.contains(code.substring(0, 2).toUpperCase(Locale.ROOT))) {
                    return MatchType.STATE_PREFIX;
                }
            }
            return null;
        }

        return MatchType.BROADCAST;
    }

    /** How an alert came to be considered relevant to a coordinate. */
    public enum MatchType {
        POLYGON("polygon"),
        ZONE("zone"),
        STATE_PREFIX("state_prefix"),
        BROADCAST("broadcast");

        private final String wire;
        MatchType(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    /** Zone codes covering a point, for consumers that also need the match reason. */
    public Set<String> zoneCodesForPoint(double lat, double lng) {
        return zoneService.zoneCodesForPoint(lat, lng);
    }

    /** {@code {AZZ560, AZC007}} -> {@code {AZ}}. Exposed alongside the above. */
    public static Set<String> statePrefixesOf(Set<String> zoneCodes) {
        return statePrefixes(zoneCodes);
    }

    /** {@code {AZZ560, AZC007}} -> {@code {AZ}}. */
    private static Set<String> statePrefixes(Set<String> zoneCodes) {
        if (zoneCodes == null || zoneCodes.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>(2);
        for (String c : zoneCodes) {
            if (c != null && c.length() >= 2) out.add(c.substring(0, 2).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /**
     * Pull the first [lon, lat] coordinate from a GeoJSON geometry. Handles
     * Point (USGS quakes) + Polygon (NWS warnings) + MultiPolygon. Returns
     * null when the structure isn't recognized.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static double[] firstCoord(Object geom) {
        if (!(geom instanceof Map)) return null;
        Map m = (Map) geom;
        Object type = m.get("type");
        Object coords = m.get("coordinates");
        if (!(type instanceof String) || coords == null) return null;
        try {
            switch ((String) type) {
                case "Point":
                    // [lon, lat] (or [lon, lat, depth] for quakes)
                    List<Number> p = (List<Number>) coords;
                    return new double[] { p.get(0).doubleValue(), p.get(1).doubleValue() };
                case "Polygon": {
                    // [[ [lon,lat], [lon,lat], ... ]]
                    List<List<List<Number>>> rings = (List<List<List<Number>>>) coords;
                    List<Number> v = rings.get(0).get(0);
                    return new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
                }
                case "MultiPolygon": {
                    // [[[ [lon,lat], ... ]]]
                    List<List<List<List<Number>>>> polys = (List<List<List<List<Number>>>>) coords;
                    List<Number> v = polys.get(0).get(0).get(0);
                    return new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
                }
                default:
                    return null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** Great-circle distance in km. R = 6371. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    /**
     * Manual refresh hook. Used by {@code POST /api/alerts/refresh} to
     * bypass the 5-minute cadence during testing. Polls both sources
     * synchronously then merges into the cache.
     */
    public void refreshNow() {
        pollAll();
    }

    // -------------------------------------------------------------------
    // DTOs returned to the resource layer
    // -------------------------------------------------------------------

    /** Cached alert payload + freshness markers. */
    public record Snapshot(
            List<NormalizedAlert> alerts,
            /** When this snapshot was assembled (always non-null). */
            Instant generatedAt,
            /** When the last successful upstream poll completed (null until first success). */
            Instant lastSuccessAt
    ) {
        public static Snapshot empty() {
            return new Snapshot(List.of(), Instant.now(), null);
        }
    }

    /**
     * Schema mirrors the FE's emergencyApis.js normalized shape so the
     * frontend can swap from its own NWS calls to this endpoint without
     * touching its render code. Keep these field names stable.
     */
    public record NormalizedAlert(
            String id,
            String source,
            /**
             * The NWS product name, verbatim — {@code "Extreme Heat Warning"},
             * {@code "Red Flag Warning"}, {@code "Evacuation Immediate"}.
             *
             * <p><b>This is a controlled vocabulary of 111 values</b>
             * ({@code api.weather.gov/alerts/types}) and it is the only honest
             * key for template matching. The dispatcher used to substring-match
             * a template's event word against the {@code headline}, which
             * matched <b>26 of 310 live alerts (8.4%)</b> and mismatched a
             * further 19% of those — "Flood" is a substring of "Flood
             * <i>Watch</i>", so watches were dispatched with warning copy
             * (audit P0-2, P0-3).</p>
             *
             * <p>Null for USGS and FEMA, which have no equivalent.</p>
             */
            String event,
            String severity,
            /**
             * CAP {@code urgency} — {@code Immediate | Expected | Future | Past | Unknown}.
             *
             * <p><b>{@code Past} means the alert is no longer a live
             * instruction.</b> Measured on the 2026-08-22 feed: all five
             * {@code Past} alerts were cancellations or supersessions
             * ("The Extreme Heat Warning has been cancelled", "…has been
             * replaced"). Dispatching one sends safety copy for a hazard that
             * has been called off.</p>
             *
             * <p>{@code Future} is the watch shape — it is how CAP says
             * "not yet", which is the distinction that used to live only
             * inside a raw title string (audit P0-3).</p>
             */
            String urgency,
            /**
             * CAP {@code certainty} — {@code Observed | Likely | Possible |
             * Unlikely | Unknown}. {@code Possible} is the other half of the
             * watch shape: 11 of the 12 {@code Possible} alerts in the
             * measured feed were Watch products.
             */
            String certainty,
            /** CAP {@code messageType} — {@code Alert | Update | Cancel | Ack | Error}. */
            String messageType,
            /**
             * CAP {@code status} — {@code Actual | Exercise | System | Test |
             * Draft}. Anything but {@code Actual} is dropped at ingest; see
             * {@link #parseNwsFeed}.
             */
            String status,
            /**
             * CAP {@code response} — {@code Shelter | Evacuate | Avoid |
             * Execute | Prepare | Monitor | AllClear | None}. NWS's own
             * one-word answer to "what do I do", and
             * <b>{@code AllClear} means the danger has passed.</b>
             */
            String response,
            String headline,
            String description,
            /**
             * CAP {@code instruction} — the issuing office's own
             * "what to do" prose, e.g. <i>"Use caution when driving
             * high-profile vehicles."</i>
             *
             * <p><b>Carried, not yet surfaced.</b> This was dropped at ingest
             * until 2026-08-25, which made {@code AlertCardDto.Official
             * .instruction} permanently null and the audit's "promote the
             * instruction when whatToDo is absent" fix impossible to write —
             * there was no source to promote from.</p>
             *
             * <p><b>Do NOT render this verbatim without a rule.</b> Measured
             * reading grade on the live feed runs 5.8 to 12.2
             * (docs/audits/2026-08-22-alert-source-audit.md); Extreme Heat
             * opens on a tautology and closes citing OSHA. What the promotion
             * refuses to promote is an open product decision — see
             * docs/epics/map-view-revamp/PHASE-2-2026-08-25.md §3.</p>
             *
             * <p>Null for USGS and FEMA, which have no equivalent.</p>
             */
            String instruction,
            String area,
            String startedAt,
            String endsAt,
            /** Raw GeoJSON geometry (map of {type, coordinates}), or null. */
            Object geometry,
            /**
             * NWS UGC zone codes this alert targets — {@code ["AZZ560"]},
             * {@code ["ORZ691", "WAZ690"]}. Empty for non-NWS sources.
             *
             * <p><b>This is the targeting key for 82% of alerts</b> (audit
             * P0-4). Measured on the live feed: 254 of 310 active alerts carry
             * no polygon, and <b>100% of those carry UGC</b>. NWS uses
             * polygons for short-fuse storm-based products and zone codes for
             * long-duration area-wide ones, so treating a null geometry as
             * "location unknown" mislabelled every Extreme Heat Warning, Red
             * Flag Warning and Flood Watch in the country.</p>
             *
             * <p>The first two characters are the state
             * ({@code AZ}Z560), which is what makes the no-network fallback in
             * {@link #getSnapshotForPoint} possible.</p>
             *
             * <p>{@code affectedZones} is deliberately NOT carried alongside
             * this: it is the same codes expressed as URLs
             * ({@code .../zones/forecast/AZZ560}), and storing one fact twice
             * is how two fields start disagreeing.</p>
             */
            List<String> ugc,
            /**
             * SAME (county FIPS) codes — {@code ["004007"]}. Also 100% present
             * on NWS alerts. Carried for consumers that key on county rather
             * than zone; {@link #getSnapshotForPoint} matches on {@link #ugc}
             * alone, which was verified sufficient.
             */
            List<String> same,
            /**
             * CAP {@code references} — the identifiers of the alerts THIS
             * message replaces. Empty for the original of a series, and for
             * every non-NWS source.
             *
             * <p><b>Measured on the live feed 2026-08-26: 73 of 252 active
             * alerts carry one (29%), and all 73 are {@code messageType:
             * Update}.</b> The identifiers share a namespace with
             * {@link #id} ({@code urn:oid:2.49.0.1.840...}), which is what
             * makes the edge joinable.</p>
             *
             * <p><b>And the direction matters, because only one end of the edge
             * is ever in the snapshot.</b> Same measurement: <b>0 of 99
             * referenced identifiers resolved inside the same response</b> —
             * necessarily, because {@code /alerts/active} returns only ACTIVE
             * alerts and a replaced alert is by definition no longer one.</p>
             *
             * <p>So the honest surface is the FORWARD edge — "this updates an
             * earlier alert", drawn on the message we have — and not the
             * reverse "replaced by X" the design mock draws on the old card,
             * which would need a short history of recently-seen alerts that
             * this service does not keep. See
             * {@code AlertDerivations.supersessionIndex}.</p>
             */
            List<String> references
    ) {}
}
