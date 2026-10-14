package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.dto.AlertHistoryResponse;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records what was active, so the hazard surfaces can show what happened.
 *
 * <h2>Why record instead of fetch</h2>
 *
 * <p>Measured 2026-09-07 against the NWS archive endpoint: <b>NWS retains
 * roughly five days, not a month.</b> Oklahoma City returns the same 23 alerts
 * whether asked since 2026-09-01 or since 2025-01-01, oldest always
 * 2026-09-02 — {@code start} is accepted and then ignored past retention. A
 * month of history cannot be fetched from upstream.</p>
 *
 * <p>It can be recorded, and cheaply: {@link AlertIngestService} already holds
 * the whole national picture every five minutes and writes none of it. This is
 * not a new data source. It is not discarding one.</p>
 *
 * <h2>What it deliberately is not</h2>
 *
 * <p>Not "what we pushed to you". {@code NotificationLog} already answers that,
 * already retains 30 days, and is the wrong answer for this page: it is
 * per-user, and it only ever held the ~26% of products that have template copy
 * plus a resolvable geocell. Oklahoma City's entire month of history is Heat
 * Advisories, which are exactly the tier that would be missing.</p>
 *
 * <h2>Honest limits, stated rather than discovered later</h2>
 *
 * <ul>
 *   <li><b>History starts empty and fills.</b> There is no backfill — five days
 *       is all upstream has, and a history that is five days deep on launch day
 *       and then grows is more confusing than one that starts clean. The empty
 *       state says when recording began; see
 *       {@link AlertHistoryResponse.Meta}.</li>
 *   <li><b>It is only as complete as the tick that feeds it.</b> An alert that
 *       both begins and ends inside one ingest window is in no snapshot and so
 *       in no row. That is the ingest cadence's blind spot, inherited rather
 *       than introduced — which is why this ticks faster than ingest does, so
 *       it at least never misses a snapshot that WAS produced.</li>
 *   <li><b>Quiet is normal.</b> Lehi UT had one alert in thirty days, Phoenix
 *       two, Miami four. The surface consuming this must treat "almost nothing
 *       happened" as the expected reading, not as a failure.</li>
 * </ul>
 */
@Service
public class AlertHistoryService {

    private static final Logger log = LoggerFactory.getLogger(AlertHistoryService.class);

    /** Degrees of latitude per km. Good to ~0.5% anywhere, which a prefilter can spend. */
    private static final double KM_PER_DEG_LAT = 111.32;

    /** One tick's worth of upsert work, so a cold start cannot hold a transaction open. */
    static final int MAX_UPSERTS_PER_TICK = 2_000;

    /**
     * A generous ceiling on a page nobody scrolls forever. Oklahoma City, the
     * busiest point measured, produced 23 alerts in thirty days.
     */
    static final int MAX_ROWS_PER_QUERY = 500;

    private final AlertHistoryRepo repo;
    private final AlertIngestService ingest;
    private final AlertDispatchService dispatch;
    private final AlertFeedService feedService;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * The snapshot generation last written. Ticking faster than ingest means
     * most ticks see a snapshot they have already recorded; this makes those
     * ticks free rather than merely idempotent.
     */
    private final AtomicReference<Instant> lastRecorded = new AtomicReference<>(null);

    @Value("${alerts.history.retentionDays:30}")
    private int retentionDays = 30;

    @Value("${alerts.history.enabled:true}")
    private boolean enabled = true;

    /**
     * The ingest poll cadence — the same property {@link AlertIngestService}
     * schedules on. "No longer listed" is measured in polls, not minutes.
     */
    @Value("${alerts.ingest.intervalMs:300000}")
    private long ingestIntervalMs = 300_000L;

    /** Serve-time clock for the read side. A field so a test can pin it. */
    Clock clock = Clock.systemUTC();

    public AlertHistoryService(AlertHistoryRepo repo,
                               AlertIngestService ingest,
                               AlertDispatchService dispatch,
                               AlertFeedService feedService) {
        this.repo = repo;
        this.ingest = ingest;
        this.dispatch = dispatch;
        this.feedService = feedService;
    }

    // ------------------------------------------------------------------
    // Write side
    // ------------------------------------------------------------------

    /**
     * Ticks at half the ingest cadence so every snapshot ingest produces gets
     * recorded at least once. Double-sampling costs nothing —
     * {@link #record(Snapshot)} is an upsert, and the generation guard skips
     * the work entirely.
     */
    @Scheduled(
            fixedDelayString = "${alerts.history.intervalMs:150000}",
            initialDelayString = "${alerts.history.initialDelayMs:90000}")
    public void scheduledRecord() {
        if (!enabled) return;
        try {
            record(ingest.getSnapshot());
        } catch (Exception e) {
            log.warn("AlertHistory: tick failed: {}", e.getMessage(), e);
            try {
                Sentry.captureException(e);
            } catch (Throwable ignored) {
                // Sentry is best-effort; a failed report must not fail the tick.
            }
        }
    }

    /**
     * Upsert every alert in the snapshot, keyed on the CAP identifier.
     *
     * <p><b>One row per alert, not one per tick.</b> A five-minute poll over a
     * three-day Extreme Heat Warning writes one row and moves
     * {@code lastSeenAt} 864 times — it does not write 864 rows. This is the
     * single most important property of the table and the reason the id is the
     * primary key rather than a surrogate.</p>
     *
     * <p><b>NWS re-sightings do not rewrite the payload.</b> A CAP identifier
     * is immutable per message: an update is a new message with a new id whose
     * {@code references} point back at what it replaces. So the alert behind an
     * NWS id never changes, and the whole supersession chain survives as
     * distinct rows — which is what lets history show the arc of an event
     * rather than only its last frame.</p>
     *
     * <p><b>USGS and FEMA do revise under one id</b> — a quake's magnitude, a
     * declaration's counties. For those, a freshly polled payload whose source
     * fingerprint ({@link AlertSourceFingerprint}) differs from the stored one
     * replaces it and re-derives the index columns ({@link #reindex}). Without
     * that, a declaration recorded before its rows carried county codes (every
     * FEMA row written before S2 C1) stayed uncoded for life: history placed it
     * nowhere while the live feed showed it in its counties. Per-revision
     * history for these sources is C3's revision table, not this row.</p>
     *
     * @return how many rows were newly inserted
     */
    @Transactional
    public int record(Snapshot snapshot) {
        if (snapshot == null || snapshot.alerts() == null || snapshot.alerts().isEmpty()) {
            return 0;
        }
        Instant generation = snapshot.generatedAt();
        if (generation != null && generation.equals(lastRecorded.get())) {
            return 0;   // already recorded this one
        }

        Instant now = Instant.now();
        List<NormalizedAlert> batch = snapshot.alerts().size() > MAX_UPSERTS_PER_TICK
                ? snapshot.alerts().subList(0, MAX_UPSERTS_PER_TICK)
                : snapshot.alerts();

        Set<String> ids = new LinkedHashSet<>();
        for (NormalizedAlert a : batch) {
            if (a.id() != null && !a.id().isBlank()) ids.add(a.id());
        }
        if (ids.isEmpty()) return 0;

        Map<String, AlertHistory> existing = new HashMap<>();
        for (AlertHistory h : repo.findAllById(ids)) {
            existing.put(h.getAlertId(), h);
        }

        List<AlertHistory> toSave = new ArrayList<>();
        int inserted = 0;
        int revised = 0;
        for (NormalizedAlert a : batch) {
            if (a.id() == null || a.id().isBlank()) continue;
            AlertHistory row = existing.get(a.id());
            if (row != null) {
                // Only a source that answered this tick has re-listed the alert.
                // A failed source's alerts ride forward in the snapshot, and
                // stamping them made an outage read as a run of sightings
                // (Phase 3 D5) — they keep the time they were last really seen.
                if (!snapshot.freshlyPolled(a.source())) continue;
                row.setLastSeenAt(now);       // still in force — for NWS, that is the whole update
                if (revisesUnderOneId(a.source()) && isRevisedFrom(row, a)) {
                    String payload = serialize(a);
                    if (payload != null) {
                        reindex(row, a, payload);
                        revised++;
                    }
                }
                toSave.add(row);
                continue;
            }
            AlertHistory fresh = toRow(a, now);
            if (fresh == null) continue;      // unserializable; logged in toRow
            toSave.add(fresh);
            inserted++;
        }

        repo.saveAll(toSave);
        lastRecorded.set(generation);
        if (inserted > 0 || revised > 0) {
            log.debug("AlertHistory: {} new, {} still active ({} revised under the same id)",
                    inserted, toSave.size() - inserted, revised);
        }
        return inserted;
    }

    AlertHistory toRow(NormalizedAlert a, Instant now) {
        String payload = serialize(a);
        if (payload == null) return null;
        AlertHistory row = new AlertHistory();
        row.setAlertId(a.id());
        row.setSource(a.source() == null ? "UNKNOWN" : a.source());
        reindex(row, a, payload);
        row.setFirstSeenAt(now);
        row.setLastSeenAt(now);
        return row;
    }

    private String serialize(NormalizedAlert a) {
        try {
            return json.writeValueAsString(a);
        } catch (Exception e) {
            log.warn("AlertHistory: could not serialize {}: {}", a.id(), e.getMessage());
            return null;
        }
    }

    /**
     * The payload and every column derived from it. One method for insert and
     * revision, so a revised row can never be indexed differently from a new
     * one.
     */
    private static void reindex(AlertHistory row, NormalizedAlert a, String payload) {
        row.setEvent(truncate(a.event(), 160));
        row.setSeverity(truncate(a.severity(), 16));
        row.setPayload(payload);

        double[] coord = AlertIngestService.firstCoord(a.geometry());
        row.setCentroidLng(coord == null ? null : coord[0]);   // firstCoord returns [lon, lat]
        row.setCentroidLat(coord == null ? null : coord[1]);

        Set<String> tokens = tokensFor(a);
        // Mutate in place: the managed collection is what Hibernate tracks.
        if (row.getTokens() == null) {
            row.setTokens(new LinkedHashSet<>(tokens));
        } else {
            row.getTokens().clear();
            row.getTokens().addAll(tokens);
        }
        // BROADCAST is what matchTypeFor returns when there is nothing to match
        // on at all — no geometry AND no UGC. Such a row applies at every
        // coordinate and must never be excluded by a prefilter.
        row.setBroadcast(coord == null && tokens.isEmpty());
    }

    /**
     * Sources whose id names the event rather than one message, so the payload
     * behind an id can change: USGS (magnitude/PAGER revisions) and FEMA
     * (declarations amended with counties). NWS is the only one that issues a
     * new id per message.
     */
    static boolean revisesUnderOneId(String source) {
        return source != null && !"NWS".equalsIgnoreCase(source);
    }

    /**
     * Has the issuer changed this alert since the row was written? Decided by
     * the source fingerprint, so a re-stamp with nothing changed ({@code sent},
     * USGS {@code updated}) is not a revision. An unreadable stored payload
     * counts as revised — the fresh one is the only usable record.
     */
    private boolean isRevisedFrom(AlertHistory row, NormalizedAlert fresh) {
        NormalizedAlert stored = rehydrate(row);
        if (stored == null) return true;
        String before = AlertSourceFingerprint.fingerprint(stored);
        String after = AlertSourceFingerprint.fingerprint(fresh);
        return after != null && !after.equals(before);
    }

    /**
     * The zone codes an alert targets, plus their two-character state prefixes.
     *
     * <p>Both kinds in one bag because {@code matchTypeFor} reads both off the
     * same {@code ugc} list: tier 2 compares whole codes against the zones
     * covering a point, tier 3 compares the leading two characters when that
     * point lookup was unavailable. Storing them separately would mean two
     * columns derived from one field.</p>
     */
    static Set<String> tokensFor(NormalizedAlert a) {
        Set<String> out = new LinkedHashSet<>();
        // targetCodes, not ugc(): the matcher reads SAME counties too, and a
        // prefilter that only saw UGC would drop a SAME-only alert it accepts.
        for (String upper : AlertIngestService.targetCodes(a)) {
            if (upper.length() > 16) continue;   // not a UGC code; the column is varchar(16)
            out.add(upper);
            if (upper.length() >= 2) out.add(upper.substring(0, 2));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Read side
    // ------------------------------------------------------------------

    /**
     * What was active near this coordinate over the last {@code days}.
     *
     * <p>Every judgment here is borrowed, none is re-derived:</p>
     * <ul>
     *   <li>whether an alert applies at this point —
     *       {@link AlertIngestService#matchTypeFor}, the same call
     *       {@link AlertFeedService#feedFor} makes, over a payload that
     *       rehydrates into the same record type;</li>
     *   <li>what the card says — {@link AlertFeedService#toRecordCard}, the
     *       same mapper, tier and policy as a live card, in its RECORD form: no
     *       SitPrep guidance and no current directive on any history card, live
     *       or not (D3). History gets <b>no</b> raw-wire-text shortcut either;
     *       the wire text stays in {@code official}, where it always was.</li>
     *   <li>where the alert got to — {@link #recordLifecycle}, from the stored
     *       reverse edges and the last successful poll.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public AlertHistoryResponse historyFor(double lat, double lng, int requestedDays) {
        return historyFor(lat, lng, requestedDays, null);
    }

    /**
     * As above, for a coordinate captured at {@code fixedAt}.
     *
     * <p>History carries the same location verdict as the live feed, decided the
     * same way. The failure mode is identical — a stale coordinate yields a
     * confident answer about the wrong place — and a history page states it more
     * strongly, because "nothing in the last 30 days" reads as a settled record
     * rather than one snapshot.</p>
     */
    @Transactional(readOnly = true)
    public AlertHistoryResponse historyFor(double lat, double lng, int requestedDays, Instant fixedAt) {
        int days = clampDays(requestedDays);
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(days));

        int radiusMi = AppConfigResource.alertsRadiusMi();
        double radiusKm = radiusMi * 1.609344;

        Set<String> userZones = ingest.zoneCodesForPoint(lat, lng);
        Set<String> userStates = AlertIngestService.statePrefixesOf(userZones);

        List<AlertHistory> rows = repo.findCandidates(
                since,
                latMin(lat, radiusKm), latMax(lat, radiusKm),
                lngMin(lat, lng, radiusKm), lngMax(lat, lng, radiusKm),
                candidateTokens(userZones, userStates),
                PageRequest.of(0, MAX_ROWS_PER_QUERY));

        // Rehydrate once; the edge index and the cards both read these.
        List<Map.Entry<AlertHistory, NormalizedAlert>> loaded = new ArrayList<>(rows.size());
        List<NormalizedAlert> evidence = new ArrayList<>(rows.size());
        for (AlertHistory row : rows) {
            NormalizedAlert a = rehydrate(row);
            if (a == null) continue;
            loaded.add(Map.entry(row, a));
            evidence.add(a);
        }

        // The REVERSE edge, batched: every message already in memory that names
        // an earlier alert — the rows just loaded (Cancels included: they are
        // filtered from the cards below, not from the evidence) plus the live
        // snapshot, which holds the newest Updates even before the recorder has
        // written them. No query per row; no query at all.
        Snapshot live = ingest.getSnapshot();
        List<NormalizedAlert> liveAlerts = live == null || live.alerts() == null
                ? List.of() : live.alerts();
        evidence.addAll(liveAlerts);
        Map<String, String> endedBy = AlertDerivations.reverseEdgeIndex(evidence);
        Set<String> listedNow = new HashSet<>();
        for (NormalizedAlert a : liveAlerts) {
            if (a != null && a.id() != null) listedNow.add(a.id());
        }

        List<AlertCardDto> cards = new ArrayList<>();
        for (Map.Entry<AlertHistory, NormalizedAlert> e : loaded) {
            AlertHistory row = e.getKey();
            NormalizedAlert a = e.getValue();

            // PUBLISHABILITY, not lifecycle. The live feed drops anything
            // AlertSafetyPolicy calls SUPPRESS, and that set includes
            // `alert_expired` — which here would suppress every row the page
            // exists to show, since history is made entirely of alerts that
            // have ended. This drops only what must never be rendered at all:
            // drills, private-scope messages, retractions, protocol acks.
            //
            // The full policy still runs, inside toRecordCard, and a record
            // never carries its present-tense "what to do" while the record of
            // it survives — the same rule the frontend already holds by refusing
            // to expand an ended row, moved to the side of the wire that owns
            // it.
            if (AlertSafetyPolicy.publishabilityBlockReason(a) != null) continue;

            MatchType match = AlertIngestService.matchTypeFor(
                    a, lat, lng, radiusKm, userZones, userStates);
            if (match == null) continue;

            // `supersededBy` (the {id, title} link) stays null here: the index
            // above knows THAT this alert was replaced, which is what lifecycle
            // needs, and a linked title is a separate surface decision. The
            // forward `references` edge on the card still carries the chain.
            String lifecycle = recordLifecycle(a, row, endedBy, listedNow, live, now);
            cards.add(feedService.toRecordCard(a, match, radiusMi, lifecycle));
        }

        Instant recordingSince = repo.findRecordingSince();
        return new AlertHistoryResponse(
                List.copyOf(cards),
                new AlertHistoryResponse.Meta(
                        recordingSince == null ? null : recordingSince.toString(),
                        days,
                        AlertFeedResponse.COVERAGE_CAVEAT,
                        AlertFeedService.locationAgeFor(fixedAt)));
    }

    /**
     * Where a recorded alert got to, strongest evidence first.
     *
     * <ol>
     *   <li><b>A stored or live message naming it</b> — {@code cancelled} for a
     *       Cancel or all-clear, {@code superseded} for any other replacement.
     *       This outranks the alert's own {@code expires}: an order called off
     *       at noon that was due to run until midnight was cancelled, and saying
     *       "expired" would lose that.</li>
     *   <li><b>Its own fields</b> — {@link AlertDerivations#lifecycleState}:
     *       past {@code expires} is {@code expired}.</li>
     *   <li><b>Gone from a successful poll</b> — {@code no_longer_listed}, for
     *       an alert that by its own fields is still on. Never
     *       {@code cancelled}: absence is not an issuer statement.</li>
     * </ol>
     */
    String recordLifecycle(NormalizedAlert a, AlertHistory row, Map<String, String> endedBy,
                           Set<String> listedNow, Snapshot live, Instant now) {
        String edge = a.id() == null ? null : endedBy.get(a.id());
        if (edge != null) return edge;

        String own = AlertDerivations.lifecycleState(a, now);
        boolean stillOn = AlertDerivations.LIFECYCLE_ACTIVE.equals(own)
                || AlertDerivations.LIFECYCLE_UPDATED.equals(own);
        if (stillOn && noLongerListed(a, row, listedNow, live)) {
            return AlertDerivations.LIFECYCLE_NO_LONGER_LISTED;
        }
        return own;
    }

    /**
     * True only when a successful poll CLEARLY postdates the alert's last
     * sighting and the live snapshot does not hold it.
     *
     * <p>"Clearly" is two poll intervals. {@code record} stamps
     * {@code lastSeenAt} at or after the snapshot it reads, so an alert still
     * listed trails {@code lastSuccessAt} by at most one interval plus that
     * poll's own run time — the recorder simply has not caught up. Two
     * intervals cannot be that.</p>
     *
     * <p>The live-snapshot check comes first and is not redundant: an alert
     * carried forward from a source that failed this tick, or seen by a
     * recorder that has fallen behind, is in the snapshot with an old
     * {@code lastSeenAt}. It is listed; it must not be called gone.</p>
     *
     * <p>The poll that counts is the alert's OWN source's. When that time is
     * unknown — no success yet, an outage, or a source this snapshot cannot
     * time separately — the answer is "unknown", and unknown labels nothing.</p>
     */
    boolean noLongerListed(NormalizedAlert a, AlertHistory row, Set<String> listedNow, Snapshot live) {
        if (row == null || row.getLastSeenAt() == null) return false;
        if (a.id() != null && listedNow.contains(a.id())) return false;
        Instant sourceSuccess = sourceLastSuccessAt(live, a);
        if (sourceSuccess == null) return false;
        Duration grace = Duration.ofMillis(Math.max(0L, ingestIntervalMs) * 2);
        return row.getLastSeenAt().plus(grace).isBefore(sourceSuccess);
    }

    /**
     * When this alert's source last polled successfully, or null when that is
     * not known.
     *
     * <p>Each source is judged by its own last success
     * ({@link Snapshot#sourceLastSuccessAt()}), never by the snapshot-wide
     * {@code lastSuccessAt()}: a FEMA poll can fail while NWS succeeds, and a
     * declaration must not read as gone when FEMA was simply not asked.</p>
     */
    private static Instant sourceLastSuccessAt(Snapshot live, NormalizedAlert a) {
        if (live == null || a == null || a.source() == null) return null;
        // Each source on its own clock: FEMA is timed by FEMA's last success,
        // never by NWS/USGS. A source that has never succeeded is absent from
        // the map, so it reads unknown and nothing gets labelled.
        Map<String, Instant> bySource = live.sourceLastSuccessAt();
        if (bySource == null) return null;
        return bySource.get(a.source().trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Which tokens to hand the candidate query, mirroring the match ladder's
     * own precedence.
     *
     * <p>{@code matchTypeFor} falls back to state prefixes <b>only</b> when the
     * point's zones are unknown — with zones in hand, an alert that targets
     * none of them is a definite no, not an unknown. Passing state tokens
     * anyway would drag every alert in the state into the candidate set for no
     * gain, since the real matcher rejects them all a moment later.</p>
     */
    static Collection<String> candidateTokens(Set<String> userZones, Set<String> userStates) {
        if (userZones != null && !userZones.isEmpty()) {
            // Plus the state-wide SAME token ("UT*"), which the zone tier also
            // accepts. Not the bare state prefix — that would be every alert in
            // the state, which is what the paragraph above rules out.
            Set<String> out = new LinkedHashSet<>(userZones);
            if (userStates != null) {
                for (String st : userStates) out.add(st + SameCodes.STATEWIDE_SUFFIX);
            }
            return out;
        }
        if (userStates != null && !userStates.isEmpty()) return userStates;
        // Neither known: only geometry and broadcast rows can match anyway, but
        // the IN clause still needs a non-empty list to be legal SQL.
        return List.of(" ");
    }

    NormalizedAlert rehydrate(AlertHistory row) {
        try {
            return json.readValue(row.getPayload(), NormalizedAlert.class);
        } catch (Exception e) {
            log.warn("AlertHistory: unreadable payload for {}: {}", row.getAlertId(), e.getMessage());
            return null;
        }
    }

    int clampDays(int requested) {
        if (requested <= 0) return retentionDays;
        return Math.min(requested, retentionDays);
    }

    // --- bounding box: a circumscribing box, deliberately wider than the circle ---

    static double latMin(double lat, double radiusKm) {
        return lat - radiusKm / KM_PER_DEG_LAT;
    }

    static double latMax(double lat, double radiusKm) {
        return lat + radiusKm / KM_PER_DEG_LAT;
    }

    static double lngMin(double lat, double lng, double radiusKm) {
        return lng - lngDelta(lat, radiusKm);
    }

    static double lngMax(double lat, double lng, double radiusKm) {
        return lng + lngDelta(lat, radiusKm);
    }

    /**
     * Longitude degrees per km shrink with the cosine of latitude. Near the
     * poles that denominator collapses, so the delta is clamped to a full
     * hemisphere — at which point the box stops narrowing anything, which is
     * the correct failure for a prefilter: useless, never wrong.
     */
    private static double lngDelta(double lat, double radiusKm) {
        double cos = Math.cos(Math.toRadians(lat));
        if (cos < 1e-6) return 180.0;
        return Math.min(180.0, radiusKm / (KM_PER_DEG_LAT * cos));
    }

    // ------------------------------------------------------------------
    // Retention
    // ------------------------------------------------------------------

    /**
     * One batch of expired rows.
     *
     * <p>Entity delete, not the bulk JPQL {@code DELETE} the other retention
     * sweeps use. {@code RetentionSweepService} justifies its own choice on the
     * grounds that those tables have "no {@code @ElementCollection} child
     * rows" — this one does, and a bulk delete would orphan
     * {@code alert_history_token} on any database whose foreign key is not
     * declared {@code ON DELETE CASCADE}. Loading a thousand rows once a day is
     * a fair price for not depending on that.</p>
     */
    @Transactional
    public int sweepOnce(int batchSize) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        List<String> ids = repo.findIdsOlderThan(cutoff, PageRequest.of(0, batchSize));
        if (ids.isEmpty()) return 0;
        repo.deleteAllById(ids);
        return ids.size();
    }

    int retentionDays() {
        return retentionDays;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
