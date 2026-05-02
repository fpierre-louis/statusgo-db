package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.AlertModeState;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.AlertModeStateRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-cell alert-mode state machine. Implements step 1 of
 * {@code docs/SPONSORED_AND_ALERT_MODE.md} ("Mode state + read-only
 * API"). Reads triggers from {@link AlertIngestService}'s in-memory
 * snapshot and produces a persisted {@link AlertModeState} per cell
 * with hysteresis on downgrades.
 *
 * <p><b>v1 trigger sources:</b> NWS Severe+Extreme + USGS M5.5+
 * intersecting a 50mi radius around the cell's anchor point. FEMA
 * declarations + AirNow + app-internal Group.alert states are
 * deliberately deferred to a follow-up session — the spec lists them
 * as eligible triggers but each needs additional plumbing (FEMA needs
 * state inference per cell; AirNow isn't in the BE cache; Group needs
 * a zipBucket column to query against). Skeleton + endpoint + cron
 * are the load-bearing pieces; trigger sources land incrementally.</p>
 *
 * <p><b>Hysteresis:</b> a cell can RAISE its state at any time, but
 * only DROPS through the dwell window:</p>
 * <ul>
 *   <li>{@code attention → calm}: 2h dwell</li>
 *   <li>{@code alert → attention}: 4h dwell</li>
 *   <li>{@code crisis → alert}: 6h dwell</li>
 * </ul>
 *
 * <p>Stored as {@link AlertModeState#getHysteresisExpiresAt} on the
 * row — the next computed mode level can't drop below the previous
 * level until {@code now > hysteresisExpiresAt}. Triggers that would
 * raise the level reset the dwell to the new (higher) state's window.</p>
 */
@Service
public class AlertModeService {

    private static final Logger log = LoggerFactory.getLogger(AlertModeService.class);

    /** State strings — lowercased, stable across the API surface. */
    public static final String CALM      = "calm";
    public static final String ATTENTION = "attention";
    public static final String ALERT     = "alert";
    public static final String CRISIS    = "crisis";

    /** Numeric ranking for max() / hysteresis comparisons. */
    private static final Map<String, Integer> STATE_RANK = Map.of(
            CALM, 0,
            ATTENTION, 1,
            ALERT, 2,
            CRISIS, 3
    );
    private static final Map<Integer, String> STATE_BY_RANK = Map.of(
            0, CALM,
            1, ATTENTION,
            2, ALERT,
            3, CRISIS
    );

    /** Dwell windows on downgrade — matches spec "Mode state machine". */
    private static final Duration DWELL_ATTENTION_TO_CALM   = Duration.ofHours(2);
    private static final Duration DWELL_ALERT_TO_ATTENTION  = Duration.ofHours(4);
    private static final Duration DWELL_CRISIS_TO_ALERT     = Duration.ofHours(6);

    /** Radius around the cell anchor used to intersect alert geometry. */
    private static final double TRIGGER_RADIUS_MI = 50.0;

    /** USGS magnitude threshold for "alert" level (ALERTS_INTEGRATION). */
    private static final double USGS_ALERT_MIN_MAG = 5.5;

    private final AlertIngestService ingest;
    private final AlertModeStateRepo modeRepo;
    private final PostRepo taskRepo;
    private final NominatimGeocodeService geocode;
    private final ObjectMapper json = new ObjectMapper();

    public AlertModeService(AlertIngestService ingest,
                            AlertModeStateRepo modeRepo,
                            PostRepo taskRepo,
                            NominatimGeocodeService geocode) {
        this.ingest = ingest;
        this.modeRepo = modeRepo;
        this.taskRepo = taskRepo;
        this.geocode = geocode;
    }

    // -------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------

    /**
     * Get the current mode for the cell containing {@code (lat, lng)}.
     * Reverse-geocodes to a zipBucket via Nominatim, then returns the
     * persisted state OR a transient "calm" fallback when no row
     * exists yet (the cron tick will create one on its next pass).
     *
     * <p>Lazy-create on read is intentional: the resource endpoint
     * stays fast (no compute on the request thread), and unpopulated
     * cells (no users, no tasks, no triggers) never accumulate rows.</p>
     */
    @Transactional(readOnly = true)
    public AlertModeState getForLatLng(double lat, double lng) {
        String zipBucket = lookupZipBucket(lat, lng);
        if (zipBucket == null || zipBucket.isBlank()) {
            return transientCalm("unknown");
        }
        return modeRepo.findById(zipBucket).orElseGet(() -> transientCalm(zipBucket));
    }

    /**
     * Recompute + persist the mode for one cell. Public for the cron
     * tick + tests + admin-triggered recompute. Returns the state row
     * (created if missing, updated in-place otherwise).
     */
    @Transactional
    public AlertModeState evaluateForCell(String zipBucket, double anchorLat, double anchorLng) {
        Snapshot snap = ingest.getSnapshot();
        List<Trigger> triggers = collectTriggers(snap, anchorLat, anchorLng);

        // Candidate state level = max across triggers, default calm.
        int candidateRank = triggers.stream()
                .mapToInt(t -> STATE_RANK.getOrDefault(t.level, 0))
                .max()
                .orElse(0);

        AlertModeState row = modeRepo.findById(zipBucket).orElseGet(() -> {
            AlertModeState fresh = new AlertModeState();
            fresh.setZipBucket(zipBucket);
            fresh.setState(CALM);
            fresh.setEnteredAt(Instant.now());
            return fresh;
        });

        int currentRank = STATE_RANK.getOrDefault(row.getState(), 0);
        Instant now = Instant.now();

        int nextRank;
        Instant nextHysteresisExpiry = row.getHysteresisExpiresAt();
        if (candidateRank >= currentRank) {
            // Raising or holding — apply immediately. Reset the dwell
            // for the NEW level so a future drop has to wait.
            nextRank = candidateRank;
            nextHysteresisExpiry = (candidateRank > 0)
                    ? now.plus(dwellForDrop(STATE_BY_RANK.get(candidateRank)))
                    : null;
        } else {
            // Candidate is lower than current. Honor the dwell — only
            // drop if we're past hysteresisExpiresAt.
            if (row.getHysteresisExpiresAt() != null && now.isBefore(row.getHysteresisExpiresAt())) {
                nextRank = currentRank; // hold
            } else {
                // Past dwell — drop one rank toward candidate, then set
                // a fresh dwell at the new level (gradual de-escalation).
                int dropTo = Math.max(candidateRank, currentRank - 1);
                nextRank = dropTo;
                nextHysteresisExpiry = (dropTo > 0)
                        ? now.plus(dwellForDrop(STATE_BY_RANK.get(dropTo)))
                        : null;
            }
        }

        String nextState = STATE_BY_RANK.get(nextRank);
        if (!nextState.equals(row.getState())) {
            row.setEnteredAt(now);
        }
        row.setState(nextState);
        if (!triggers.isEmpty()) row.setLastTriggerSeen(now);
        row.setHysteresisExpiresAt(nextHysteresisExpiry);
        row.setTriggersJson(serializeTriggers(triggers));
        return modeRepo.save(row);
    }

    /**
     * Cron tick — walk all populated cells (distinct zipBucket on
     * Post, since every community task gets a reverse-geocode at
     * create time) and recompute their state. PT5M cadence matches
     * the alert ingest poll, so a brand-new NWS alert can show up in
     * the right cell's mode within 5–10 min.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT9M")
    public void scheduledTick() {
        try {
            int updated = recomputeAllPopulatedCells();
            if (updated > 0) {
                log.info("AlertMode: recomputed {} populated cells", updated);
            } else {
                log.debug("AlertMode: no populated cells to recompute");
            }
        } catch (Exception e) {
            log.warn("AlertMode: tick failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    int recomputeAllPopulatedCells() {
        List<String> buckets = taskRepo.findDistinctZipBuckets();
        int n = 0;
        for (String b : buckets) {
            try {
                List<Post> anchors = taskRepo.findAnchorTasksByZipBucket(b);
                if (anchors.isEmpty()) continue;
                Post anchor = anchors.get(0);
                evaluateForCell(b, anchor.getLatitude(), anchor.getLongitude());
                n++;
            } catch (Exception inner) {
                log.warn("AlertMode: skipped cell {}: {}", b, inner.getMessage());
                try { Sentry.captureException(inner); } catch (Throwable ignored) {}
            }
        }
        return n;
    }

    // -------------------------------------------------------------------
    // Trigger collection
    // -------------------------------------------------------------------

    private List<Trigger> collectTriggers(Snapshot snap, double lat, double lng) {
        List<Trigger> out = new ArrayList<>();
        if (snap == null || snap.alerts() == null) return out;

        // Use the existing AlertIngestService point-radius filter so we
        // share the same Haversine logic that drives community-feed
        // intersection. Geometry-less alerts (FEMA) come back via the
        // "include unconditionally" branch — they're not used as mode
        // triggers in v1 since "any FEMA declaration nationally" would
        // raise every cell. FEMA mode triggers land in a follow-up.
        Snapshot point = ingest.getSnapshotForPoint(lat, lng, TRIGGER_RADIUS_MI);
        if (point == null || point.alerts() == null) return out;

        for (NormalizedAlert a : point.alerts()) {
            if ("FEMA".equalsIgnoreCase(a.source())) continue;
            Trigger t = classify(a);
            if (t != null) out.add(t);
        }
        return out;
    }

    private Trigger classify(NormalizedAlert a) {
        String source = a.source();
        if (source == null) return null;

        if ("NWS".equalsIgnoreCase(source)) {
            String sev = a.severity() == null ? "" : a.severity().toLowerCase(Locale.ROOT);
            // Spec mapping: advisory→attention, warning→alert,
            // emergency→crisis. NWS doesn't use those exact words for
            // severity — it uses Severity (Severe/Extreme/etc.) and
            // a separate event type. Approximation: Severe→alert,
            // Extreme→crisis. Lower severities don't trigger.
            if ("extreme".equals(sev)) return Trigger.of(source, ALERT, 0.95, a);
            if ("severe".equals(sev))  return Trigger.of(source, ATTENTION, 0.85, a);
            return null;
        }
        if ("USGS".equalsIgnoreCase(source)) {
            // Magnitude parsed by AlertIngest into the headline like
            // "M5.6 — 14km E of Encinitas, CA". Reuse the same parser.
            Double mag = parseUsgsMag(a.headline());
            if (mag == null) return null;
            if (mag >= 6.5)             return Trigger.of(source, ALERT, 0.9, a);
            if (mag >= USGS_ALERT_MIN_MAG) return Trigger.of(source, ATTENTION, 0.7, a);
            return null;
        }
        return null;
    }

    private static Double parseUsgsMag(String headline) {
        if (headline == null) return null;
        int mIdx = headline.indexOf('M');
        int dashIdx = headline.indexOf('—');
        if (mIdx < 0 || dashIdx <= mIdx) return null;
        try {
            return Double.parseDouble(headline.substring(mIdx + 1, dashIdx).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Duration dwellForDrop(String fromState) {
        return switch (fromState) {
            case CRISIS    -> DWELL_CRISIS_TO_ALERT;
            case ALERT     -> DWELL_ALERT_TO_ATTENTION;
            case ATTENTION -> DWELL_ATTENTION_TO_CALM;
            default        -> Duration.ZERO;
        };
    }

    private String lookupZipBucket(double lat, double lng) {
        try {
            NominatimGeocodeService.Place p = geocode.reverse(lat, lng);
            return p == null ? null : p.zipBucket();
        } catch (Exception e) {
            log.debug("AlertMode: reverse-geocode failed at ({}, {}): {}", lat, lng, e.getMessage());
            return null;
        }
    }

    private String serializeTriggers(List<Trigger> triggers) {
        try {
            return json.writeValueAsString(triggers);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static AlertModeState transientCalm(String zipBucket) {
        AlertModeState s = new AlertModeState();
        s.setZipBucket(zipBucket);
        s.setState(CALM);
        s.setEnteredAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return s;
    }

    // -------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------

    /**
     * Stored as JSON inside {@link AlertModeState#getTriggersJson}.
     * Public-shaped fields so it serializes cleanly to the FE on the
     * mode endpoint.
     */
    public static final class Trigger {
        public String source;
        public String level;
        public double confidence;
        public String alertId;
        public String headline;
        public String since;

        static Trigger of(String source, String level, double confidence, NormalizedAlert a) {
            Trigger t = new Trigger();
            t.source = source;
            t.level = level;
            t.confidence = confidence;
            t.alertId = a.id();
            t.headline = a.headline();
            t.since = a.startedAt();
            return t;
        }
    }
}
