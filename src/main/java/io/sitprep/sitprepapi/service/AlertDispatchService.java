package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Auto-post dispatcher — step 6 of {@code docs/ALERTS_INTEGRATION.md}.
 * The headline new behavior: when an alert intersects a populated
 * geocell, the SitPrep system user authors a community post in that
 * cell so users see the alert in their feed without dependence on
 * push notification permission.
 *
 * <p><b>Build state:</b> this is layer 1 of the dispatcher — entity
 * scaffolding ({@link io.sitprep.sitprepapi.domain.AlertPost}),
 * template loading, and the service skeleton. The cron tick + geocell
 * resolution + Post creation + STOMP broadcast + resolve path land in
 * follow-up sessions. Service is wired into Spring + ready to be
 * exercised, but {@link #dispatchOnce()} returns 0 until the rest is
 * built.</p>
 *
 * <p><b>Dedup invariant:</b> exactly one auto-post per (alertId,
 * geocellId). Enforced both by the unique index on AlertPost and by
 * the application-side {@link AlertPostRepo#findByAlertIdAndGeocellId}
 * check. The unique index is the safety net against racing dispatch
 * ticks; the application check makes the flow cleaner.</p>
 *
 * <p><b>Templates:</b> body copy lives in
 * {@code resources/templates/alert-dispatch-templates.json}, loaded at
 * boot via {@link #loadTemplates()}. Editable without redeploy by
 * shipping a new JAR; future improvement is a DB-backed template
 * editor for ops.</p>
 */
@Service
public class AlertDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatchService.class);

    private static final String TEMPLATES_RESOURCE = "templates/alert-dispatch-templates.json";

    private final AlertIngestService ingest;
    private final AlertPostRepo alertPostRepo;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Loaded once at boot. List rather than map because match logic
     * walks templates by source-then-event-then-severity rather than
     * indexing by a single key.
     */
    private List<DispatchTemplate> templates = List.of();

    public AlertDispatchService(AlertIngestService ingest, AlertPostRepo alertPostRepo) {
        this.ingest = ingest;
        this.alertPostRepo = alertPostRepo;
    }

    @PostConstruct
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
                JsonNode n = it.next();
                loaded.add(DispatchTemplate.fromJson(n));
            }
            this.templates = List.copyOf(loaded);
            log.info("AlertDispatch: loaded {} dispatch templates", templates.size());
        } catch (Exception e) {
            log.error("AlertDispatch: failed to load templates from {} — dispatch will be a no-op until fixed", TEMPLATES_RESOURCE, e);
        }
    }

    /**
     * Run one dispatch pass. Stub for now — returns 0 until the cron
     * tick + geocell resolution land. Public + named so the resolve
     * cron + tests + an admin-triggered out-of-band dispatch all share
     * the same entry point.
     *
     * <p>Future flow per spec:</p>
     * <ol>
     *   <li>Read {@code ingest.getSnapshot()} for the active alert set.</li>
     *   <li>For each alert: walk populated geocells where users have
     *       opted in (UserAlertPreference). Compute candidate
     *       (alertId, geocellId) pairs.</li>
     *   <li>Filter via {@link AlertPostRepo#findByAlertIdAndGeocellId}
     *       to skip already-posted pairs.</li>
     *   <li>For each remaining pair: pick the matching template,
     *       create a Post via {@code PostService}, persist an
     *       AlertPost row, broadcast on STOMP.</li>
     * </ol>
     */
    public int dispatchOnce() {
        if (templates.isEmpty()) return 0;
        // Layer 1: skeleton only. Layer 2 wires the actual dispatch
        // through ingest.getSnapshot() + geocell resolution.
        return 0;
    }

    /**
     * Run one resolve pass. Stub for now. Future flow:
     *
     * <ol>
     *   <li>For each {@code alertId} in {@link AlertPostRepo#findActiveAlertIds()}:
     *       check if the upstream alert is still in
     *       {@code ingest.getSnapshot()}.</li>
     *   <li>If not (or if its endsAt has passed): mark the matching
     *       AlertPost rows {@code resolvedAt = now} and update the
     *       parent Post for visual demotion.</li>
     * </ol>
     */
    public int resolveOnce() {
        if (alertPostRepo.findActiveAlertIds().isEmpty()) return 0;
        // Layer 1: skeleton only. Layer 3 wires the resolve path.
        return 0;
    }

    /**
     * Find the matching template for an upstream alert. Returns the
     * first template whose source + event/incidentType + severity all
     * match. Falls back to a {@code _fallback: true} template per
     * source when no specific match is found, so we never silently
     * drop alerts that should generate a post.
     */
    Optional<DispatchTemplate> matchTemplate(String source, String event, String severity, Double magnitude) {
        for (DispatchTemplate t : templates) {
            if (t.matches(source, event, severity, magnitude)) return Optional.of(t);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------
    // Template DTO. Package-private so future layer-2 logic can read
    // body, hazardType, askTag etc. when assembling Post payloads.
    // ---------------------------------------------------------------

    static final class DispatchTemplate {
        final String source;
        final String event;            // NWS event name (Hurricane, Tornado, ...)
        final List<String> severity;   // Lane-A severities for NWS (Severe / Extreme)
        final List<String> incidentTypeAny; // FEMA incident types
        final Double minMag;           // USGS — minimum magnitude
        final boolean fallback;        // true => match any unmatched alert from this source
        final String hazardType;
        final String headline;
        final String body;
        final String askTag;           // /ask?tag= deep link target, or null

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

        boolean matches(String alertSource, String alertEvent, String alertSeverity, Double alertMag) {
            if (!source.equalsIgnoreCase(alertSource)) return false;
            if (fallback) return true;
            if (event != null && !event.equalsIgnoreCase(alertEvent)) return false;
            if (severity != null && !severity.isEmpty()
                    && severity.stream().noneMatch(s -> s.equalsIgnoreCase(alertSeverity))) {
                return false;
            }
            if (incidentTypeAny != null && !incidentTypeAny.isEmpty()
                    && incidentTypeAny.stream().noneMatch(it -> it.equalsIgnoreCase(alertEvent))) {
                return false;
            }
            if (minMag != null && (alertMag == null || alertMag < minMag)) return false;
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
