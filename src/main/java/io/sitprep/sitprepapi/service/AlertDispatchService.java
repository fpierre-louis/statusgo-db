package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.Task.TaskPriority;
import io.sitprep.sitprepapi.domain.Task.TaskStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.TaskDto;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.NominatimGeocodeService.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Auto-post dispatcher — step 6 of {@code docs/ALERTS_INTEGRATION.md}.
 *
 * <p>When an active alert intersects a populated geocell, the SitPrep
 * system user authors a community post in that cell. Posts use the
 * existing {@link Task} entity (kind = "system-alert" via tags) so they
 * flow through the canonical {@code TaskService.discoverCommunity} feed
 * + STOMP broadcast on {@code /topic/community/tasks/{zipBucket}} + the
 * shared {@code FeedItemShell} render — no separate entity or render
 * path. Future migration to a unified {@code CommunityPost} per
 * MARKETPLACE_AND_FEED_CALM.md is a refactor of consumers, not the
 * dispatcher.</p>
 *
 * <p><b>Dedup:</b> exactly one auto-post per (alertId, geocellId).
 * Enforced by the unique index on {@link AlertPost} and by the
 * application-side {@link AlertPostRepo#findByAlertIdAndGeocellId}
 * check ahead of the create call. Geocell is the
 * {@code zipBucket} from a Nominatim reverse-geocode of the alert's
 * first-vertex coord — same key the {@code TaskService} community
 * feed uses.</p>
 *
 * <p><b>v1 scope:</b> NWS warnings (Severe + Extreme) and USGS quakes
 * (M5.5+) only. FEMA declarations are in the alert ingest cache but
 * have no geometry, so dispatch defers to a future state-keyed flow.
 * Resolve path (layer 3) lands in a follow-up session.</p>
 */
@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);

    private static final String TEMPLATES_RESOURCE = "templates/alert-dispatch-templates.json";

    /** Reserved system author for SitPrep auto-posts. */
    static final String SYSTEM_EMAIL = "system@sitprep.app";

    private final AlertIngestService ingest;
    private final AlertPostRepo alertPostRepo;
    private final TaskService taskService;
    private final UserInfoRepo userInfoRepo;
    private final NominatimGeocodeService geocode;
    private final ObjectMapper json = new ObjectMapper();

    private List<DispatchTemplate> templates = List.of();

    public AlertDispatchService(AlertIngestService ingest,
                                AlertPostRepo alertPostRepo,
                                TaskService taskService,
                                UserInfoRepo userInfoRepo,
                                NominatimGeocodeService geocode) {
        this.ingest = ingest;
        this.alertPostRepo = alertPostRepo;
        this.taskService = taskService;
        this.userInfoRepo = userInfoRepo;
        this.geocode = geocode;
    }

    @PostConstruct
    void init() {
        loadTemplates();
        ensureSystemUser();
    }

    void loadTemplates() {
        try (InputStream in = new ClassPathResource(TEMPLATES_RESOURCE).getInputStream()) {
            JsonNode root = json.readTree(in);
            JsonNode arr = root.path("templates");
            if (!arr.isArray()) {
                log.warn("AlertDispatch: templates JSON missing 'templates' array — dispatch disabled until fixed.");
                return;
            }
            List<DispatchTemplate> loaded = new ArrayList<>(arr.size());
            Iterator<JsonNode> it = arr.elements();
            while (it.hasNext()) {
                loaded.add(DispatchTemplate.fromJson(it.next()));
            }
            this.templates = List.copyOf(loaded);
            log.info("AlertDispatch: loaded {} dispatch templates", templates.size());
        } catch (Exception e) {
            log.error("AlertDispatch: failed to load templates from {} — dispatch will be a no-op until fixed", TEMPLATES_RESOURCE, e);
        }
    }

    /**
     * Idempotent system-user seed. Reserved {@code system@sitprep.app}
     * with display name "SitPrep" + the SitPrep avatar so auto-posts
     * render with a recognizable author through the existing
     * {@code TaskService.withAuthors} pipeline. Runs once at boot;
     * unique constraint on userEmail catches concurrent seeds across
     * pod restarts.
     */
    void ensureSystemUser() {
        try {
            if (userInfoRepo.findByUserEmail(SYSTEM_EMAIL).isPresent()) return;
            UserInfo u = new UserInfo();
            u.setUserEmail(SYSTEM_EMAIL);
            u.setUserFirstName("SitPrep");
            u.setUserLastName("");
            // Avatar lives in the public bundle — sitprep-images CDN domain.
            // Falls back to hashed initials if the URL 404s on the FE side.
            u.setProfileImageURL("https://sitprepimages.com/system/sitprep-avatar.png");
            userInfoRepo.save(u);
            log.info("AlertDispatch: seeded system user {}", SYSTEM_EMAIL);
        } catch (Exception e) {
            log.warn("AlertDispatch: ensureSystemUser failed (will retry on next boot): {}", e.getMessage());
        }
    }

    /**
     * Quarter-hourly cron tick. {@code initialDelay} of 7min keeps
     * dispatch off the boot path while ingest's first prime resolves
     * (ingest's own initialDelay is 1min + the prime is async). Lower
     * cadence than ingest (5min) since dispatch is bounded by ingest's
     * snapshot — running more often than ingest doesn't surface new
     * alerts. Higher would compound costs.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT7M")
    public void scheduledDispatch() {
        try {
            int created = dispatchOnce();
            if (created > 0) {
                log.info("AlertDispatch: dispatched {} new auto-posts", created);
            } else {
                log.debug("AlertDispatch: no new auto-posts");
            }
        } catch (Exception e) {
            log.warn("AlertDispatch: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one dispatch pass. Public + named so layer 3's resolve cron,
     * tests, and an admin-triggered out-of-band dispatch can share the
     * same entry point. Returns the count of new auto-posts created.
     *
     * <p>Pass:</p>
     * <ol>
     *   <li>Read {@code ingest.getSnapshot()}.</li>
     *   <li>For each alert with geometry: derive a geocell via
     *       reverse-geocode of the first-vertex coord.</li>
     *   <li>Skip if {@link AlertPostRepo#findByAlertIdAndGeocellId}
     *       already has a row.</li>
     *   <li>Match a template; skip when no template matches (severity
     *       below threshold, source not configured, etc.).</li>
     *   <li>Build a Task body from the template + create via
     *       {@link TaskService#create} (which handles WS broadcast +
     *       zipBucket population). Persist an AlertPost tracking row.</li>
     * </ol>
     */
    @Transactional
    public int dispatchOnce() {
        if (templates.isEmpty()) return 0;
        AlertIngestService.Snapshot snap = ingest.getSnapshot();
        if (snap == null || snap.alerts() == null || snap.alerts().isEmpty()) return 0;

        int created = 0;
        for (NormalizedAlert a : snap.alerts()) {
            try {
                if (a.id() == null || a.id().isBlank()) continue;
                String alertId = a.source() + "-" + a.id();

                // Geometry-required for v1 (FEMA declarations bucketed
                // separately in a future state-keyed flow).
                double[] coord = firstCoord(a.geometry());
                if (coord == null) continue;

                // Reverse-geocode → zipBucket. Skip silently when the
                // geocoder fails or doesn't have a zip — alerts in
                // remote ocean / desert areas don't get auto-posts.
                String zipBucket = lookupZipBucket(coord[1], coord[0]); // lat, lng
                if (zipBucket == null || zipBucket.isBlank()) continue;

                // Application-side dedup ahead of the unique-index
                // safety net. Cheap (one indexed lookup) and avoids a
                // failed insert + rollback for the common case.
                if (alertPostRepo.findByAlertIdAndGeocellId(alertId, zipBucket).isPresent()) continue;

                // Template match drives both severity-eligibility and
                // body content. No template = not eligible (e.g. NWS
                // Moderate, USGS M4.5).
                Optional<DispatchTemplate> tplOpt = matchForAlert(a);
                if (tplOpt.isEmpty()) continue;
                DispatchTemplate tpl = tplOpt.get();

                Task body = buildAutoPostTask(a, tpl, coord);
                TaskDto dto = taskService.create(body, SYSTEM_EMAIL);

                AlertPost ap = new AlertPost();
                ap.setAlertId(alertId);
                ap.setHazardType(tpl.hazardType);
                ap.setGeocellId(zipBucket);
                ap.setPostId(dto.id());
                ap.setExpiresAt(parseInstantOrNull(a.endsAt()));
                alertPostRepo.save(ap);

                created++;
            } catch (Exception e) {
                // Per-alert failure shouldn't break the whole tick. Log
                // + Sentry-capture and move on.
                log.warn("AlertDispatch: skipped alert {} {}: {}", a.source(), a.id(), e.getMessage());
                try { Sentry.captureException(e); } catch (Throwable ignored) {}
            }
        }
        return created;
    }

    /**
     * Quarter-hourly resolve tick. Lower cadence than dispatch — alerts
     * clear less often than they appear, and a delay of up to 15min
     * before the auto-post drops from the feed is acceptable.
     * {@code initialDelay} of 12min keeps it staggered against the
     * dispatch tick (PT5M + 7min initial = first dispatch at boot+7min;
     * resolve fires at boot+12min, 27min, 42min, ...).
     */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT12M")
    public void scheduledResolve() {
        try {
            int resolved = resolveOnce();
            if (resolved > 0) {
                log.info("AlertDispatch: resolved {} cleared auto-posts", resolved);
            } else {
                log.debug("AlertDispatch: no auto-posts to resolve");
            }
        } catch (Exception e) {
            log.warn("AlertDispatch: resolve tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run one resolve pass. Walks all unresolved {@link AlertPost} rows
     * grouped by alertId, checks the upstream {@code ingest.getSnapshot()}
     * for whether the alert is still active, and for cleared alerts:
     *
     * <ol>
     *   <li>{@code TaskService.cancel(postId)} on each parent Task —
     *       sets status CANCELLED + broadcasts on the same STOMP topic
     *       that delivered the create. Connected clients drop the task
     *       from their list per {@code useCommunityTasks}'s WS handler.</li>
     *   <li>Mark {@code AlertPost.resolvedAt = now} so the next
     *       resolve tick skips this row.</li>
     * </ol>
     *
     * <p><b>Resolution criteria</b> (any one triggers):</p>
     * <ul>
     *   <li>Upstream alert no longer in {@code ingest.getSnapshot()}.</li>
     *   <li>{@code AlertPost.expiresAt} has passed (defense in depth —
     *       guards against an upstream that keeps a stale alert active
     *       past its declared {@code endsAt}).</li>
     * </ul>
     */
    @Transactional
    public int resolveOnce() {
        List<String> trackedAlertIds = alertPostRepo.findActiveAlertIds();
        if (trackedAlertIds.isEmpty()) return 0;

        // Build the active-set from the ingest snapshot for an O(N)
        // lookup. Same alertId composition used at dispatch time:
        // "{source}-{id}".
        AlertIngestService.Snapshot snap = ingest.getSnapshot();
        Set<String> activeIds = new HashSet<>();
        if (snap != null && snap.alerts() != null) {
            for (NormalizedAlert a : snap.alerts()) {
                if (a.id() != null && !a.id().isBlank()) {
                    activeIds.add(a.source() + "-" + a.id());
                }
            }
        }

        Instant now = Instant.now();
        int resolvedCount = 0;
        for (String alertId : trackedAlertIds) {
            try {
                List<AlertPost> rows = alertPostRepo.findActiveByAlertId(alertId);
                if (rows.isEmpty()) continue;

                // Decide: still active or cleared? Take the most-permissive
                // interpretation (defense-in-depth): an alert is cleared
                // when EITHER it's gone from the snapshot OR its
                // expiresAt has passed.
                AlertPost first = rows.get(0);
                boolean inActiveSet = activeIds.contains(alertId);
                boolean expired = first.getExpiresAt() != null
                        && first.getExpiresAt().isBefore(now);
                if (inActiveSet && !expired) continue;

                for (AlertPost ap : rows) {
                    try {
                        // Cancel the parent Task — broadcasts via
                        // TaskService.saveAndBroadcast so connected
                        // clients drop it from useCommunityTasks.
                        taskService.cancel(ap.getPostId());
                    } catch (Exception inner) {
                        // Task may already be CANCELLED (manual cancel)
                        // or DONE (TaskService.cancel rejects DONE).
                        // Either way, we still want to mark the AlertPost
                        // resolved so the next tick stops trying.
                        log.debug("AlertDispatch: cancel skipped for task {}: {}",
                                ap.getPostId(), inner.getMessage());
                    }
                    ap.setResolvedAt(now);
                    alertPostRepo.save(ap);
                    resolvedCount++;
                }
            } catch (Exception e) {
                log.warn("AlertDispatch: resolve skipped for alert {}: {}", alertId, e.getMessage());
                try { Sentry.captureException(e); } catch (Throwable ignored) {}
            }
        }
        return resolvedCount;
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private Task buildAutoPostTask(NormalizedAlert a, DispatchTemplate tpl, double[] coord) {
        Task t = new Task();
        // Title and body filled from the template, with simple slot
        // substitution. {name} pulls from headline; {mag}/{distance}
        // are USGS-specific (parsed from headline).
        t.setTitle(tpl.headline);
        t.setDescription(fillBody(tpl.body, a));
        t.setPriority(TaskPriority.URGENT);
        t.setStatus(TaskStatus.OPEN);
        t.setLatitude(coord[1]);   // lat
        t.setLongitude(coord[0]);  // lng
        // groupId left null → community scope
        Set<String> tags = new HashSet<>();
        tags.add("system-alert");
        if (tpl.hazardType != null) tags.add(tpl.hazardType);
        if (a.source() != null) tags.add(a.source().toLowerCase(Locale.ROOT));
        t.setTags(tags);
        return t;
    }

    private static String fillBody(String body, NormalizedAlert a) {
        if (body == null) return "";
        String headline = a.headline() == null ? "" : a.headline();
        String filled = body;
        // {name}: pull a "name" from the NWS headline (best-effort —
        // headlines typically read "Hurricane Helene warning" so we
        // capture the second word for hurricanes; fall back to the
        // hazard type word).
        filled = filled.replace("{name}", inferAlertName(headline));
        // {mag}: USGS magnitude from the headline format "M5.6 — ..."
        Double mag = parseUsgsMag(headline);
        if (mag != null) {
            filled = filled.replace("{mag}", String.format(Locale.ROOT, "%.1f", mag));
        }
        // {distance} / {direction}: not computed in v1; leave the slot
        // as a literal so future iterations can fill it without a
        // template change.
        return filled;
    }

    private static String inferAlertName(String headline) {
        if (headline == null || headline.isBlank()) return "";
        // Crude: split by spaces, take the second token if the first
        // is a known hazard noun (Hurricane Helene → Helene).
        String[] parts = headline.trim().split("\\s+");
        if (parts.length >= 2) {
            String head = parts[0].toLowerCase(Locale.ROOT);
            if (head.equals("hurricane") || head.equals("tropical")) {
                return parts[1].replaceAll("[^A-Za-z0-9]", "");
            }
        }
        return "";
    }

    private static Double parseUsgsMag(String headline) {
        if (headline == null) return null;
        // "M5.6 — 14km E of Encinitas, CA"
        int mIdx = headline.indexOf('M');
        int dashIdx = headline.indexOf('—');
        if (mIdx < 0 || dashIdx <= mIdx) return null;
        try {
            return Double.parseDouble(headline.substring(mIdx + 1, dashIdx).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String lookupZipBucket(double lat, double lng) {
        try {
            Place p = geocode.reverse(lat, lng);
            return p == null ? null : p.zipBucket();
        } catch (Exception e) {
            log.debug("AlertDispatch: reverse-geocode failed at ({}, {}): {}", lat, lng, e.getMessage());
            return null;
        }
    }

    private static Instant parseInstantOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso); }
        catch (Exception ignored) { return null; }
    }

    /**
     * First [lon, lat] coord from a GeoJSON geometry. Mirrors
     * {@code AlertIngestService.firstCoord} but inlined here since
     * that method is private.
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
                case "Point": {
                    List<Number> p = (List<Number>) coords;
                    return new double[] { p.get(0).doubleValue(), p.get(1).doubleValue() };
                }
                case "Polygon": {
                    List<List<List<Number>>> rings = (List<List<List<Number>>>) coords;
                    List<Number> v = rings.get(0).get(0);
                    return new double[] { v.get(0).doubleValue(), v.get(1).doubleValue() };
                }
                case "MultiPolygon": {
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

    /**
     * Find the matching template for a normalized alert. Walks
     * templates in declaration order; first match wins. Severity
     * threshold + USGS magnitude floor + FEMA incidentType are all
     * encoded in the templates JSON, so a template miss == not
     * eligible.
     */
    Optional<DispatchTemplate> matchForAlert(NormalizedAlert a) {
        for (DispatchTemplate t : templates) {
            if (t.matchesAlert(a)) return Optional.of(t);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------
    // Template DTO
    // ---------------------------------------------------------------

    static final class DispatchTemplate {
        final String source;
        final String event;
        final List<String> severity;
        final List<String> incidentTypeAny;
        final Double minMag;
        final boolean fallback;
        final String hazardType;
        final String headline;
        final String body;
        final String askTag;

        DispatchTemplate(String source, String event, List<String> severity,
                         List<String> incidentTypeAny, Double minMag, boolean fallback,
                         String hazardType, String headline, String body, String askTag) {
            this.source = source;
            this.event = event;
            this.severity = severity;
            this.incidentTypeAny = incidentTypeAny;
            this.minMag = minMag;
            this.fallback = fallback;
            this.hazardType = hazardType;
            this.headline = headline;
            this.body = body;
            this.askTag = askTag;
        }

        boolean matchesAlert(NormalizedAlert a) {
            if (a == null || a.source() == null) return false;
            if (!source.equalsIgnoreCase(a.source())) return false;
            if (fallback) return true;

            String headline = a.headline() == null ? "" : a.headline();
            String headlineLower = headline.toLowerCase(Locale.ROOT);

            // Event substring match (NWS templates carry an `event`
            // field; we look it up in the headline since NormalizedAlert
            // doesn't carry the raw NWS `event` property separately).
            if (event != null && !event.isBlank()
                    && !headlineLower.contains(event.toLowerCase(Locale.ROOT))) {
                return false;
            }

            // Severity match (NWS).
            if (severity != null && !severity.isEmpty()) {
                if (a.severity() == null) return false;
                boolean sevOk = severity.stream()
                        .anyMatch(s -> s.equalsIgnoreCase(a.severity()));
                if (!sevOk) return false;
            }

            // FEMA incidentType — substring match against headline
            // (which we constructed as "{incidentType} — {title}").
            if (incidentTypeAny != null && !incidentTypeAny.isEmpty()) {
                boolean any = incidentTypeAny.stream()
                        .anyMatch(it -> headlineLower.contains(it.toLowerCase(Locale.ROOT)));
                if (!any) return false;
            }

            // USGS magnitude floor.
            if (minMag != null) {
                Double mag = parseUsgsMag(headline);
                if (mag == null || mag < minMag) return false;
            }

            return true;
        }

        static DispatchTemplate fromJson(JsonNode n) {
            return new DispatchTemplate(
                    text(n, "source"),
                    text(n, "event"),
                    stringArray(n, "severity"),
                    stringArray(n, "incidentTypeAny"),
                    n.has("minMag") ? n.get("minMag").asDouble() : null,
                    n.path("_fallback").asBoolean(false),
                    text(n, "hazardType"),
                    text(n, "headline"),
                    text(n, "body"),
                    text(n, "askTag")
            );
        }

        private static String text(JsonNode n, String f) {
            JsonNode v = n.path(f);
            return (v.isMissingNode() || v.isNull()) ? null : v.asText();
        }

        private static List<String> stringArray(JsonNode n, String f) {
            JsonNode v = n.path(f);
            if (!v.isArray()) return null;
            List<String> out = new ArrayList<>(v.size());
            v.forEach(x -> out.add(x.asText()));
            return out;
        }
    }
}
