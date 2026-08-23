package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.constant.HazardType;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostPriority;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.NominatimGeocodeService.Place;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Duration;
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
 * existing {@link Post} entity (kind = "system-alert" via tags) so they
 * flow through the canonical {@code PostService.discoverCommunity} feed
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
 * first-vertex coord — same key the {@code PostService} community
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

    /**
     * Radius (km) around an alert's representative coordinate within
     * which located, push-enabled users get an FCM push for a life-
     * threatening NWS warning. ~80 km ≈ 50 mi, matching the FE's
     * {@code radiusMi.alerts} local-hazard window. v1 centers on the
     * alert's first-vertex coord; a point-in-polygon-per-user test is
     * the future refinement.
     */
    private static final double SEVERE_PUSH_RADIUS_KM = 80.0;

    /** Safety cap on a single alert's push fan-out. */
    private static final int MAX_PUSH_RECIPIENTS = 500;

    /**
     * How old a user's location fix may be and still be used for push
     * targeting (audit P1-3).
     *
     * <p>Geolocation never auto-refreshes by policy — the cached coord is what
     * the app reads until an explicit user gesture replaces it
     * (docs/location/LOCATION_FRESHNESS.md). So without a bound, "last known
     * location" can be arbitrarily old, and a life-safety push gets aimed at
     * where someone was rather than where they are.</p>
     *
     * <p>14 days is a compromise, and worth naming as one: too short and a
     * user who has not opened the app in a fortnight silently stops receiving
     * warnings for their own home; too long and we are guessing. It is
     * property-driven so it can be tuned without a redeploy.</p>
     */
    @Value("${alerts.push.locationMaxAgeDays:14}")
    private int locationMaxAgeDays = 14;

    private final AlertIngestService ingest;
    private final AlertPostRepo alertPostRepo;
    private final PostService taskService;
    private final UserInfoRepo userInfoRepo;
    private final NominatimGeocodeService geocode;
    private final NotificationService notificationService;
    /** Zone centroids for the 82% of alerts that ship no polygon (audit P0-4). */
    private final NwsZoneService zoneService;
    /** Per-recipient send decision — honours the hazard opt-outs (audit P1-4). */
    private final PushPolicyService pushPolicyService;
    private final ObjectMapper json = new ObjectMapper();

    private List<DispatchTemplate> templates = List.of();

    public AlertDispatchService(AlertIngestService ingest,
                                AlertPostRepo alertPostRepo,
                                PostService taskService,
                                UserInfoRepo userInfoRepo,
                                NominatimGeocodeService geocode,
                                NotificationService notificationService,
                                NwsZoneService zoneService,
                                PushPolicyService pushPolicyService) {
        this.ingest = ingest;
        this.alertPostRepo = alertPostRepo;
        this.taskService = taskService;
        this.userInfoRepo = userInfoRepo;
        this.geocode = geocode;
        this.notificationService = notificationService;
        this.zoneService = zoneService;
        this.pushPolicyService = pushPolicyService;
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
                JsonNode n = it.next();
                // The templates array carries bare strings as section headings
                // ("--- NWS · WATCH tier ---"). JSON has no comments and this
                // file is meant to be read and edited by hand, so the headings
                // earn their keep; skipping non-objects is the cost.
                if (!n.isObject()) continue;
                loaded.add(DispatchTemplate.fromJson(n));
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
     * {@code PostService.withAuthors} pipeline. Runs once at boot;
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
            u.setProfileImageUrl("https://sitprepimages.com/system/sitprep-avatar.png");
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
     *   <li>Build a Post body from the template + create via
     *       {@link PostService#create} (which handles WS broadcast +
     *       zipBucket population). Persist an AlertPost tracking row.</li>
     * </ol>
     */
    @Transactional
    public int dispatchOnce() {
        if (templates.isEmpty()) return 0;
        AlertIngestService.Snapshot snap = ingest.getSnapshot();
        if (snap == null || snap.alerts() == null || snap.alerts().isEmpty()) return 0;

        int created = 0;
        // Severe-alert push recipient pool — loaded lazily on the first
        // push-worthy alert so a quiet tick does zero extra DB work.
        List<UserInfo> pushCandidates = null;
        for (NormalizedAlert a : snap.alerts()) {
            try {
                if (a.id() == null || a.id().isBlank()) continue;

                // An alert can arrive carrying its own retraction. Skip those
                // before anything else — see isStillInForce.
                if (!isStillInForce(a)) continue;

                String alertId = a.source() + "-" + a.id();

                // A representative coordinate for this alert. Polygon first;
                // for the 82% of alerts that ship none, fall back to the
                // centroid of a UGC zone they target (audit P0-4).
                //
                // This gate used to be `if (coord == null) continue;`, which
                // meant every zone-only alert was silently skipped — no
                // auto-post, no push. Measured: that dropped 40 of the 75
                // live Severe alerts, including every Extreme Heat Warning
                // and Red Flag Warning in the country.
                //
                // FEMA rows still fall through here and that is correct: they
                // carry county NAMES, not codes, so there is genuinely nothing
                // to resolve. Their state-keyed flow is separate work.
                double[] coord = resolveDispatchCoord(a);
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

                Post body = buildAutoPostTask(a, tpl, coord);
                PostDto dto = taskService.create(body, SYSTEM_EMAIL);

                AlertPost ap = new AlertPost();
                ap.setAlertId(alertId);
                ap.setHazardType(tpl.hazardType);
                ap.setGeocellId(zipBucket);
                ap.setPostId(dto.id());
                ap.setExpiresAt(parseInstantOrNull(a.endsAt()));
                alertPostRepo.save(ap);

                // Life-threatening NWS warnings (Severe/Extreme) also
                // earn an FCM push to nearby located users — the feed
                // post alone only reaches users with the app open.
                // Fires exactly once per alert, all-time: the
                // (alertId, geocellId) dedup above means this branch is
                // reached only when a NEW AlertPost is created.
                if (isLifeThreatening(a, tpl)) {
                    if (pushCandidates == null) {
                        pushCandidates = userInfoRepo.findPushablesWithLocation(
                                Instant.now().minus(Duration.ofDays(locationMaxAgeDays)));
                    }
                    pushSevereAlert(a, tpl, coord, pushCandidates);
                }

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
     *   <li>{@code PostService.cancel(postId)} on each parent Post —
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
                        // Cancel the parent Post — broadcasts via
                        // PostService.saveAndBroadcast so connected
                        // clients drop it from useCommunityTasks.
                        taskService.cancel(ap.getPostId());
                    } catch (Exception inner) {
                        // Post may already be CANCELLED (manual cancel)
                        // or DONE (PostService.cancel rejects DONE).
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

    private Post buildAutoPostTask(NormalizedAlert a, DispatchTemplate tpl, double[] coord) {
        Post t = new Post();
        // Dispatched severe/extreme alerts are alert-update posts so the
        // community feed pins the freshest one to the top (~24h, or until a
        // newer alert replaces it) and renders the "Pinned by your area"
        // strip — replacing the old sticky top alert band.
        t.setKind("alert-update");
        // Title and body from the template. fillBody substitutes {mag} and
        // {place} and guarantees no unresolved slot survives (audit P0-5).
        t.setTitle(tpl.headline);
        t.setDescription(fillBody(tpl, a));
        t.setPriority(PostPriority.URGENT);
        t.setStatus(PostStatus.OPEN);
        // DERIVED, NOT CAPTURED (V60). The alert's own end time has been on the
        // wire and stored on AlertPost.expiresAt all along — it was simply never
        // copied onto the post, so the feed could not say when a warning stops
        // being in effect and the FE's official/alert strip had to return null.
        // Same parse as the AlertPost write below, from the same field, so the
        // two records cannot disagree about when this alert ends.
        t.setEffectiveUntil(parseInstantOrNull(a.endsAt()));
        t.setLatitude(coord[1]);   // lat
        t.setLongitude(coord[0]);  // lng
        // groupId left null → community scope

        // ── V62: THREE TYPED CHANNELS, NOT ONE UNTYPED SET ────────────────
        // This used to write three strings into `tags`: "system-alert", the
        // hazard, and the source. One Set<String> carrying provenance AND
        // hazard AND (elsewhere) user topics, with nothing able to tell them
        // apart — 12,126 posts' worth in production before it was caught.
        //
        // `tags` is now strictly user-authored and the machine writes here:
        t.setSourceKey(a.source() == null ? null : a.source().toLowerCase(Locale.ROOT));
        // Through HazardType so the dispatcher cannot introduce a spelling the
        // rest of the app does not recognise. An unmapped template hazard is
        // DROPPED rather than stored raw — a value only one writer understands
        // is how the four vocabularies happened.
        t.setHazardTags(new HashSet<>(HazardType.normalize(List.of(
                tpl.hazardType == null ? "" : tpl.hazardType))));
        // NOTE: "system-alert" is not replaced by a tag — it is now expressed
        // by `sourceKey` being non-null and machine-owned. A separate boolean
        // would be a third way to say the same thing.
        return t;
    }

    /**
     * Used only when a template's body AND its headline are both unusable.
     * Plain, true, and actionable without knowing which hazard it is.
     */
    static final String LAST_RESORT_BODY = "Check local alerts for what to do.";

    /** Any {@code {slot}} the substitution pass did not resolve. */
    private static final java.util.regex.Pattern UNRESOLVED_SLOT =
            java.util.regex.Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_]*\\}");

    /**
     * Fill a template body's slots from the alert.
     *
     * <h3>Why {@code {distance}} / {@code {direction}} are gone (audit P0-5)</h3>
     *
     * <p>They were never substituted. {@code fillBody} documented the choice —
     * "not computed in v1; leave the slot as a literal so future iterations can
     * fill it" — and the literal string
     * {@code "M6.2 earthquake about {distance}mi {direction}."} reached an APNs
     * time-sensitive push that breaks through Focus modes.</p>
     *
     * <p><b>They cannot be computed where this runs.</b> Distance is a property
     * of a (alert, recipient) pair, and this body is built once and handed to
     * {@code sendHazardAlertBatch} as a single FCM MulticastMessage for up to
     * 500 recipients. Personalising it means abandoning the batch for N
     * sequential sends inside a transaction — a real regression in the
     * life-safety path to gain a number the alert already expresses better.</p>
     *
     * <p>So {@code {place}} replaces them, filled from the alert's own area.
     * USGS already ships a human-anchored location — <i>"14km E of Encinitas,
     * CA"</i> — which beats "23mi NE" with no reference point, and is true for
     * every recipient in the batch.</p>
     *
     * <h3>{@code {name}} is also gone</h3>
     *
     * <p>{@code inferAlertName} took the second token of the headline when the
     * first was "Hurricane". Real NWS headlines read <i>"Hurricane Warning
     * issued August 30 at 5:00AM EDT by NWS Miami FL"</i> — so the second token
     * is <b>"Warning"</b>, and the copy rendered <i>"Hurricane Warning is on
     * the way."</i> The storm name is not reliably in the headline; the
     * template no longer claims it.</p>
     */
    static String fillBody(DispatchTemplate tpl, NormalizedAlert a) {
        if (tpl == null) return "";
        return fillBody(tpl.body, a, tpl.headline);
    }

    /**
     * @param plainFallback the copy to fall back to if sanitising removes
     *   every sentence. <b>Must be our own plain-language string</b> — see
     *   {@link #sanitizeSlots}.
     */
    static String fillBody(String body, NormalizedAlert a, String plainFallback) {
        if (body == null) return "";
        String headline = a == null || a.headline() == null ? "" : a.headline();
        String filled = body;

        Double mag = parseUsgsMag(headline);
        if (mag != null) {
            filled = filled.replace("{mag}", String.format(Locale.ROOT, "%.1f", mag));
        }

        String place = a == null ? null : a.area();
        if (place != null && !place.isBlank()) {
            filled = filled.replace("{place}", place.trim());
        }

        return sanitizeSlots(filled, plainFallback);
    }

    /**
     * Guarantee no unresolved slot ever reaches a user.
     *
     * <p>The template set is clean today and there is a test asserting it. This
     * exists anyway because {@code alert-dispatch-templates.json} is edited by
     * hand <b>without a redeploy</b> — so "the templates are correct" is a
     * property of the current file, not of the system. P0-5 was exactly this
     * failure, and the fix is not worth much if the next hand-edit can
     * reintroduce it.</p>
     *
     * <p>An unresolved slot removes <b>the whole sentence containing it</b>,
     * not just the token: deleting the token alone leaves "about mi ." which
     * reads as a rendering bug and is arguably worse than the brace.</p>
     *
     * <h3>The fallback is our copy, never the wire text</h3>
     *
     * <p>If sanitising removes every sentence, this falls back to
     * {@code plainFallback} — the <b>template's own headline</b> ("Dangerous
     * heat"), which we authored and measured for reading level.</p>
     *
     * <p>It deliberately does <b>not</b> fall back to
     * {@code NormalizedAlert.headline()}. That is the raw NWS wire string —
     * <i>"Extreme Heat Warning issued August 22 at 11:42AM MST until August 29
     * at 8:00PM MST by NWS Phoenix AZ"</i> — carrying the issuing office and
     * two absolute timestamps. It was the first version of this method, and it
     * meant the safety net for a broken template was to reintroduce the exact
     * jargon P0-1 through P0-3 exist to remove, on the rare path instead of the
     * common one. A degraded path is still a user-facing path.</p>
     */
    static String sanitizeSlots(String filled, String plainFallback) {
        if (filled == null) return "";
        if (!UNRESOLVED_SLOT.matcher(filled).find()) {
            return collapseSpaces(filled);
        }

        // Split on sentence ends, keeping the terminator with its sentence.
        String[] sentences = filled.split("(?<=[.!?])\\s+");
        StringBuilder kept = new StringBuilder();
        List<String> dropped = new ArrayList<>();
        for (String sentence : sentences) {
            if (UNRESOLVED_SLOT.matcher(sentence).find()) {
                dropped.add(sentence.trim());
                continue;
            }
            if (kept.length() > 0) kept.append(' ');
            kept.append(sentence.trim());
        }

        String result = collapseSpaces(kept.toString());
        if (result.isBlank()) {
            result = collapseSpaces(plainFallback == null ? "" : plainFallback);
        }
        if (result.isBlank()) {
            // Both the body and our own headline are unusable. Say something
            // true and plain rather than nothing or wire text.
            result = LAST_RESORT_BODY;
        }

        // A template bug should be loud. It is editable without a deploy, so
        // the log line is how anyone finds out it happened.
        log.warn("AlertDispatch: template body had unresolved slot(s) {} — dropped that sentence. "
                + "Fix alert-dispatch-templates.json.", dropped);
        try { Sentry.captureMessage("Alert template contained an unresolved slot: " + dropped); }
        catch (Throwable ignored) {}

        return result;
    }

    private static String collapseSpaces(String s) {
        return s == null ? "" : s.replaceAll("\\s{2,}", " ").trim();
    }

    /** Package-visible alias so the feed mapper can read magnitude too. */
    static Double parseUsgsMagnitude(String headline) { return parseUsgsMag(headline); }

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

    // ---------------------------------------------------------------
    // Severe-alert push fan-out
    // ---------------------------------------------------------------

    /**
     * Whether an alert warrants an FCM push, not just a feed post.
     *
     * <p><b>Keyed on the template's tier, not on CAP severity</b> (audit
     * P0-3). Severity was the wrong instrument: NWS rates a <i>Flood
     * Watch</i> "Severe" — the same value a Flood <i>Warning</i> carries —
     * so a severity gate could not tell "flooding is possible tonight" from
     * "flooding is happening now", and pushed both. The product name knows
     * the difference, and {@code tier} is that distinction made explicit in
     * the one place the copy is authored.
     *
     * <p>USGS quakes are excluded: the shaking has already happened, so the
     * feed post is sufficient and a push would be after-the-fact noise.
     * FEMA declarations are recovery context and are all watch-tier.
     */
    /**
     * Is this alert still telling people to do something?
     *
     * <p>Two CAP fields say "no", and both were being dropped at the
     * normalizer (audit P0-3):</p>
     *
     * <ul>
     *   <li><b>{@code response == AllClear}</b> — the alert exists to say the
     *       danger has passed.</li>
     *   <li><b>{@code urgency == Past}</b> — the alert is retrospective.</li>
     * </ul>
     *
     * <p>This is not theoretical. On the measured 2026-08-22 feed, all five
     * {@code Past} alerts were retractions, three of them also
     * {@code AllClear}, and one of those was an <b>Extreme Heat Warning whose
     * headline reads "The Extreme Heat Warning has been cancelled."</b> With
     * P0-2's exact-event matching that row matches the "Dangerous heat"
     * template, which is warning-tier — so without this gate it would have
     * sent an APNs time-sensitive push telling people to seek air
     * conditioning for a warning that had just been called off. The other two
     * {@code Past} rows are supersessions ("…has been replaced"), which
     * {@code AllClear} alone does not catch, which is why both fields are
     * checked rather than one.</p>
     *
     * <p>Note this is a different question from expiry. An alert can be
     * within its {@code endsAt} window and still be a retraction; that is
     * exactly what these five were.</p>
     */
    static boolean isStillInForce(NormalizedAlert a) {
        if (a == null) return false;
        if ("AllClear".equalsIgnoreCase(a.response())) return false;
        if ("Past".equalsIgnoreCase(a.urgency())) return false;
        return true;
    }

    /**
     * Cross-check the template's declared tier against the alert's own CAP
     * shape, and refuse the mismatch.
     *
     * <p>Exact-event matching (P0-2) already prevents a Watch product from
     * reaching Warning copy, so in a correct configuration this never fires.
     * It exists because {@code alert-dispatch-templates.json} is edited by
     * hand without a deploy: the one thing standing between a mis-typed
     * {@code "tier": "warning"} on a Watch product and an interruptive push is
     * a human noticing. CAP already knows the answer —
     * {@code certainty: Possible} and {@code urgency: Future} are how the
     * issuer says "not yet" — so we ask it rather than trusting our own
     * config.</p>
     */
    static boolean tierMatchesAlertShape(NormalizedAlert a, DispatchTemplate tpl) {
        if (a == null || tpl == null) return false;
        if (!tpl.isWarningTier()) return true;   // watch copy is never the dangerous direction
        if ("Possible".equalsIgnoreCase(a.certainty())) return false;
        if ("Future".equalsIgnoreCase(a.urgency())) return false;
        return true;
    }

    static boolean isLifeThreatening(NormalizedAlert a, DispatchTemplate tpl) {
        if (a == null || a.source() == null || tpl == null) return false;
        if (!"NWS".equalsIgnoreCase(a.source())) return false;
        if (!isStillInForce(a)) return false;
        return tpl.isWarningTier() && tierMatchesAlertShape(a, tpl);
    }

    /**
     * Fan a life-threatening alert out to every located, push-enabled
     * user within {@link #SEVERE_PUSH_RADIUS_KM} of the alert's
     * representative coordinate, via one batched
     * {@link NotificationService#sendHazardAlertBatch} call — online
     * users get a STOMP frame, offline users an iOS time-sensitive
     * APNs push ({@code hazard_alert} breaks through Focus modes), and
     * everyone gets an inbox log row.
     *
     * <p>Fan-out is capped at {@link #MAX_PUSH_RECIPIENTS} as a guard
     * against a bad coordinate matching the whole table. The batch send
     * runs inside the dispatch transaction — acceptable at the 5-min
     * cron cadence and bounded by the 500-token multicast limit.</p>
     */
    private void pushSevereAlert(NormalizedAlert a,
                                 DispatchTemplate tpl,
                                 double[] coord,
                                 List<UserInfo> candidates) {
        if (candidates == null || candidates.isEmpty()) return;

        double alertLat = coord[1];
        double alertLng = coord[0];

        // Radius-filter the pool down to users near this alert, capped
        // so a bad coordinate can't fan out to the whole table.
        List<UserInfo> nearby = new ArrayList<>();
        for (UserInfo u : candidates) {
            if (nearby.size() >= MAX_PUSH_RECIPIENTS) {
                log.warn("AlertDispatch: severe-alert push hit the {}-recipient cap for {}-{}",
                        MAX_PUSH_RECIPIENTS, a.source(), a.id());
                break;
            }
            if (u.getLastKnownLat() == null || u.getLastKnownLng() == null) continue;
            double distKm = GeoUtil.haversineKm(alertLat, alertLng,
                    u.getLastKnownLat(), u.getLastKnownLng());
            if (distKm <= SEVERE_PUSH_RADIUS_KM) nearby.add(u);
        }
        if (nearby.isEmpty()) return;

        // ── PUSH POLICY (audit P1-4) ──────────────────────────────────────
        // Until now this path never consulted PushPolicyService, so the three
        // hazard toggles on /account/alert-preferences — "NWS weather alerts",
        // "Earthquakes", "Wildfires nearby" — were decorative. A user who
        // unchecked one still got the push. The categories existed, the
        // evaluate() chokepoint existed, and nothing connected them.
        //
        // Only Lane A recipients receive the push. Warning-tier alerts carry
        // the critical bypass, so quiet hours still do not suppress a
        // life-safety warning (see PushPolicyService.isCriticalBypass) — the
        // change here is that an explicit opt-out is finally honoured, which
        // is a stronger user signal than a quiet window.
        PushPolicyService.Category category = pushCategoryFor(a, tpl);
        List<UserInfo> laneA = new ArrayList<>(nearby.size());
        int suppressed = 0;
        for (UserInfo u : nearby) {
            PushPolicyService.Lane lane =
                    pushPolicyService.evaluate(u.getUserEmail(), category, a.severity());
            if (lane == PushPolicyService.Lane.A) laneA.add(u); else suppressed++;
        }
        if (suppressed > 0) {
            log.info("AlertDispatch: {} of {} nearby user(s) suppressed by push policy for {}",
                    suppressed, nearby.size(), a.source() + "-" + a.id());
        }
        if (laneA.isEmpty()) return;
        nearby = laneA;

        String title = (tpl != null && tpl.headline != null && !tpl.headline.isBlank())
                ? tpl.headline
                : "Severe weather alert nearby";
        String body = truncate(buildPushBody(a, tpl), 160);
        String referenceId = a.source() + "-" + a.id();

        // One batched MulticastMessage instead of N sequential sends.
        // Deep-link to the renamed hazards page. /Fema is still routed in
        // the FE as an alias (see App.js) for in-flight pushes; new ones
        // land on the canonical /hazards URL.
        notificationService.sendHazardAlertBatch(nearby, title, body, referenceId, "/hazards");
        log.info("AlertDispatch: severe-alert push for {} dispatched to {} nearby user(s)",
                referenceId, nearby.size());
    }

    /**
     * Which preference toggle governs this alert.
     *
     * <p>Maps to the categories {@code PushPolicyService} already defines and
     * {@code AlertPreferencesPage} already renders — this is the wiring that
     * was missing, not a new vocabulary. Hazard type is the discriminator
     * because it is what the user sees on the settings screen: someone who
     * mutes "Wildfires nearby" means the Red Flag Warning, whichever office
     * issued it.</p>
     */
    static PushPolicyService.Category pushCategoryFor(NormalizedAlert a, DispatchTemplate tpl) {
        if (a != null && "USGS".equalsIgnoreCase(a.source())) {
            return PushPolicyService.Category.USGS_QUAKE_MAJOR;
        }
        if (tpl != null && HazardType.WILDFIRE.wire().equals(tpl.hazardType)) {
            return PushPolicyService.Category.WILDFIRE_NEAR;
        }
        return PushPolicyService.Category.NWS_SEVERE_EXTREME;
    }

    /**
     * Push body — prefer the curated template body (concise safety
     * guidance), fall back to the raw NWS headline.
     */
    private static String buildPushBody(NormalizedAlert a, DispatchTemplate tpl) {
        if (tpl != null && tpl.body != null && !tpl.body.isBlank()) {
            return fillBody(tpl, a);
        }
        return a.headline() != null ? a.headline() : "Tap for safety steps.";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)).trim() + "…";
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
     * A representative {@code [lon, lat]} for an alert, or null when it has no
     * location this pipeline can resolve.
     *
     * <p>Polygon first, then the centroid of the first UGC zone we have warmed
     * (audit P0-4). {@link NwsZoneService#centroidForZone} is cache-only and
     * non-blocking by design — a zone not yet warmed returns null here and the
     * alert dispatches on a later tick, which is a bounded delay on a 5-minute
     * cron rather than a 200 ms upstream call inside this transaction.</p>
     */
    private double[] resolveDispatchCoord(NormalizedAlert a) {
        double[] fromGeometry = centroidOfGeometry(a.geometry());
        if (fromGeometry != null) return fromGeometry;

        if (a.ugc() == null) return null;
        for (String ugc : a.ugc()) {
            Optional<double[]> centroid = zoneService.centroidForZone(ugc);
            if (centroid.isPresent()) {
                double[] latLng = centroid.get();
                return new double[] { latLng[1], latLng[0] };   // -> [lon, lat]
            }
        }
        return null;
    }

    /**
     * Representative {@code [lon, lat]} for a GeoJSON geometry — the mean of
     * every vertex (audit P1-3).
     *
     * <p>This replaced a {@code firstCoord} helper that took <b>the first
     * vertex of the first ring</b> (now deleted). That coordinate is an arbitrary corner of the
     * polygon, and it was doing three jobs: placing the auto-post, and
     * centring an 80 km push radius. On a long county-spanning warning
     * polygon the first vertex can sit tens of miles from the affected
     * population, so the push circle was offset by that much — reaching people
     * outside the warning and missing people inside it.</p>
     *
     * <p>Same arithmetic as {@link NwsZoneService#centroidOf}, over a {@code
     * Map} rather than a {@code JsonNode} because that is the shape the ingest
     * normalizer stores. Not an area centroid, deliberately: a vertex mean is
     * stable, cheap, has no degenerate cases, and is strictly closer to the
     * affected area than a corner.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static double[] centroidOfGeometry(Object geom) {
        if (!(geom instanceof Map)) return null;
        Object coords = ((Map) geom).get("coordinates");
        if (coords == null) return null;
        double[] acc = new double[2];
        int[] n = new int[1];
        accumulateCoords(coords, acc, n);
        if (n[0] == 0) return null;
        return new double[] { acc[0] / n[0], acc[1] / n[0] };   // [lon, lat]
    }

    /**
     * Walk arbitrarily-nested coordinate lists down to [lon, lat] pairs.
     *
     * <p><b>Skips a ring's closing vertex.</b> GeoJSON requires a linear ring
     * to repeat its first position as its last, so a naive vertex mean
     * double-counts that corner and pulls the centroid toward it. On a
     * 500-vertex NWS polygon the bias is negligible; on a simple 5-point county
     * box it moves the result by a fifth of the polygon's width — and a box is
     * exactly the shape a hand-drawn warning tends to be.</p>
     */
    @SuppressWarnings("rawtypes")
    private static void accumulateCoords(Object node, double[] acc, int[] n) {
        if (!(node instanceof List)) return;
        List list = (List) node;
        if (list.isEmpty()) return;
        if (list.get(0) instanceof Number) {
            if (list.size() >= 2) {
                acc[0] += ((Number) list.get(0)).doubleValue();
                acc[1] += ((Number) list.get(1)).doubleValue();
                n[0]++;
            }
            return;
        }
        int size = list.size();
        // A ring: first element is itself a coordinate pair, and the last
        // repeats the first. Drop the duplicate.
        if (size > 1 && isCoordPair(list.get(0)) && samePosition(list.get(0), list.get(size - 1))) {
            size--;
        }
        for (int i = 0; i < size; i++) accumulateCoords(list.get(i), acc, n);
    }

    @SuppressWarnings("rawtypes")
    private static boolean isCoordPair(Object o) {
        return o instanceof List && ((List) o).size() >= 2 && ((List) o).get(0) instanceof Number;
    }

    @SuppressWarnings("rawtypes")
    private static boolean samePosition(Object a, Object b) {
        if (!isCoordPair(a) || !isCoordPair(b)) return false;
        List la = (List) a, lb = (List) b;
        return ((Number) la.get(0)).doubleValue() == ((Number) lb.get(0)).doubleValue()
                && ((Number) la.get(1)).doubleValue() == ((Number) lb.get(1)).doubleValue();
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

    static final String TIER_WARNING = "warning";

    static final class DispatchTemplate {
        final String source;
        /** Exact NWS product names this template covers. */
        final List<String> eventAny;
        /** {@code warning} | {@code watch} — drives push eligibility. */
        final String tier;
        final List<String> incidentTypeAny;
        final Double minMag;
        final boolean fallback;
        final String hazardType;
        final String headline;
        final String body;
        final String askTag;

        DispatchTemplate(String source, List<String> eventAny, String tier,
                         List<String> incidentTypeAny, Double minMag, boolean fallback,
                         String hazardType, String headline, String body, String askTag) {
            this.source = source;
            this.eventAny = eventAny;
            this.tier = tier;
            this.incidentTypeAny = incidentTypeAny;
            this.minMag = minMag;
            this.fallback = fallback;
            this.hazardType = hazardType;
            this.headline = headline;
            this.body = body;
            this.askTag = askTag;
        }

        /**
         * Does this template describe this alert?
         *
         * <p><b>NWS matches on the exact product name</b>, not on a substring
         * of the headline (audit P0-2). The old rule asked whether the
         * template's event word appeared anywhere in the headline, which
         * failed in both directions at once: it matched only 26 of 310 live
         * alerts because "Excessive Heat" is not a product NWS issues any
         * more, and it over-matched because "Flood" is a substring of "Flood
         * Watch" — so 5 of those 26 were watches dispatched as warnings.</p>
         */
        boolean matchesAlert(NormalizedAlert a) {
            if (a == null || a.source() == null) return false;
            if (!source.equalsIgnoreCase(a.source())) return false;
            if (fallback) return true;

            String headline = a.headline() == null ? "" : a.headline();

            // NWS: exact product name, case-insensitive. A template that
            // declares eventAny matches nothing else, so an alert whose
            // product we have no copy for is simply not dispatched — which is
            // the honest outcome, and is what makes the coverage test able to
            // catch a rename.
            if (eventAny != null && !eventAny.isEmpty()) {
                if (a.event() == null || a.event().isBlank()) return false;
                boolean hit = eventAny.stream().anyMatch(e -> e.equalsIgnoreCase(a.event()));
                if (!hit) return false;
            }

            // FEMA incidentType — still a substring match against the headline
            // we constructed as "{incidentType} — {title}". FEMA has no
            // controlled product vocabulary to match exactly against.
            if (incidentTypeAny != null && !incidentTypeAny.isEmpty()) {
                String headlineLower = headline.toLowerCase(Locale.ROOT);
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

        /** Warning-tier copy interrupts; watch-tier copy does not. */
        boolean isWarningTier() {
            return TIER_WARNING.equalsIgnoreCase(tier);
        }

        static DispatchTemplate fromJson(JsonNode n) {
            return new DispatchTemplate(
                    text(n, "source"),
                    stringArray(n, "eventAny"),
                    text(n, "tier"),
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
