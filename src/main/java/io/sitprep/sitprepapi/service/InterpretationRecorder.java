package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.AlertInterpretationSnapshot;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.repo.AlertInterpretationSnapshotRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.AlertIdentity;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Records what the alert feed was and what SitPrep said about it (S2 contract
 * §6): one {@link AlertSourceRevision} per upstream revision and one
 * {@link AlertInterpretationSnapshot} per interpretation of it.
 *
 * <h2>When it runs</h2>
 *
 * <p>Once per SUCCESSFUL ingest tick — {@link AlertIngestService} calls
 * {@link #onPoll} after a poll in which at least one source answered. A tick
 * where every upstream failed changes nothing upstream said, so there is
 * nothing to record.</p>
 *
 * <h2>Bounded by distinct revisions, not by polls</h2>
 *
 * <p>Every 5-minute tick re-lists the same few hundred alerts. Two in-memory
 * LRUs — revision refs (with the {@code lastSeenAt} last written) and snapshot
 * fingerprints (with their ids) — make a repeat poll cost nothing: no query, no
 * write. A cache miss (a new revision, or the first tick after a restart) costs
 * one batched SELECT per 500 keys, then one INSERT per new row.</p>
 *
 * <p>{@code lastSeenAt} is moved only for an alert whose source answered this
 * tick (Phase 3 D5: carried-forward data is not a sighting), and at most once
 * per {@link #LAST_SEEN_STEP}, in one UPDATE for the batch.</p>
 *
 * <p><b>{@code lastSeenAt} is retention-grade only</b> (an accepted deviation
 * from contract §6, which says "bump {@code last_seen_at}" per tick): it may
 * trail the real last sighting by up to {@link #LAST_SEEN_STEP}. Its one reader
 * is the 30-day retention sweep, which an hour of slack does not move. Nothing
 * may read it as a liveness bound — not the record lookup (C4) deciding
 * live-versus-record, not dispatch or the inbox (C5) deciding "still listed".
 * Whether an alert is listed now is the ingest snapshot's answer, and only
 * its.</p>
 *
 * <h2>The snapshot id the feed hands out</h2>
 *
 * <p>{@link #cachedSnapshotId} is the feed card's {@code interpretationRef}: a
 * lookup in the fingerprint cache, never a query, so a person's feed costs no
 * more database work than before. A card whose interpretation has not been
 * recorded yet — the decision or the lifecycle moved since the last tick —
 * carries null rather than the id of a snapshot that says something else. The
 * fingerprint folds in the card's {@link LifecycleMode}, so a RECORD (replaced
 * in the snapshot, or a history card) only ever names a record snapshot, never
 * the live one with its {@code current=true} and primary action.</p>
 *
 * <h2>One writer at a time</h2>
 *
 * <p>{@link #record} and both sweeps hold this recorder's monitor, and each
 * sweep's DELETE commits before the monitor is released. Otherwise a sweep
 * could delete a row between a tick's lookup and its cache write, and the
 * cache would go on claiming a row the table no longer has — the revision would
 * never be written again and its next snapshot would fail the FK. The feed's
 * cache reads take only the short {@code cacheLock}, never the monitor.</p>
 *
 * <p>Nothing here is AI. The content is the interpreter's existing output,
 * serialized.</p>
 */
@Service
public class InterpretationRecorder {

    private static final Logger log = LoggerFactory.getLogger(InterpretationRecorder.class);

    /**
     * How stale a written {@code lastSeenAt} may get before a re-listing moves
     * it — the price of one UPDATE an hour instead of one a tick. It is why
     * {@code lastSeenAt} is retention-grade only (see the class doc): never a
     * liveness bound.
     */
    static final Duration LAST_SEEN_STEP = Duration.ofHours(1);

    /** Keys per IN list. */
    static final int CHUNK = 500;

    private static final int MAX_UPSTREAM_ID = 280;
    private static final int MAX_DEDUPE_KEY = 300;
    private static final int MAX_REVISION_REF = 320;

    private final AlertSourceRevisionRepo revisions;
    private final AlertInterpretationSnapshotRepo snapshots;
    private final Supplier<AlertFeedService> feed;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Guards both caches; a person's feed reads them while a tick writes. The
     * monitor ({@code synchronized} on this) orders ticks against sweeps; this
     * lock only keeps the maps whole.
     */
    private final Object cacheLock = new Object();
    /** revisionRef → the {@code lastSeenAt} this process knows is stored. */
    private final Map<String, Instant> revisionCache;
    /** snapshot fingerprint → the stored snapshot's id and revision. */
    private final Map<String, CachedSnapshot> snapshotCache;

    /** A stored snapshot, by fingerprint; the revision lets a vanished revision take its snapshots with it. */
    private record CachedSnapshot(UUID id, String revisionRef) {}

    /** Write-time clock. A field so a test can pin it. */
    Clock clock = Clock.systemUTC();

    /**
     * The feed is reached lazily: it reads this recorder's cache for
     * {@code interpretationRef}, and this recorder asks it to build the
     * interpretation, so neither can take the other in its constructor.
     */
    @Autowired
    public InterpretationRecorder(AlertSourceRevisionRepo revisions,
                                  AlertInterpretationSnapshotRepo snapshots,
                                  ObjectProvider<AlertFeedService> feed,
                                  AlertIngestService ingest,
                                  @Value("${alerts.interpretation.cacheSize:8192}") int cacheSize) {
        this(revisions, snapshots, feed::getObject, cacheSize);
        ingest.onSuccessfulPoll(this::onPoll);
    }

    InterpretationRecorder(AlertSourceRevisionRepo revisions,
                           AlertInterpretationSnapshotRepo snapshots,
                           Supplier<AlertFeedService> feed,
                           int cacheSize) {
        this.revisions = revisions;
        this.snapshots = snapshots;
        this.feed = feed;
        this.revisionCache = lru(cacheSize);
        this.snapshotCache = lru(cacheSize);
    }

    /** What one {@link #record} call wrote. */
    public record Result(int newRevisions, int touchedRevisions, int newSnapshots) {
        static final Result NONE = new Result(0, 0, 0);
    }

    /**
     * The ingest hook. A recording failure costs the record, never the tick:
     * the feed has already swapped in the new snapshot by the time this runs.
     */
    void onPoll(Snapshot snapshot) {
        try {
            Result r = record(snapshot);
            if (r.newRevisions() > 0 || r.newSnapshots() > 0) {
                log.debug("InterpretationRecorder: {} new revision(s), {} new snapshot(s), {} re-listed",
                        r.newRevisions(), r.newSnapshots(), r.touchedRevisions());
            }
        } catch (Exception e) {
            log.warn("InterpretationRecorder: tick failed: {}", e.getMessage(), e);
            try {
                Sentry.captureException(e);
            } catch (Throwable ignored) {
                // Sentry is best-effort.
            }
        }
    }

    /**
     * Records every alert in {@code snapshot}. Idempotent: the same snapshot
     * twice writes nothing the second time, and — while the caches hold its
     * keys — does not touch the database at all.
     */
    public synchronized Result record(Snapshot snapshot) {
        if (snapshot == null || snapshot.alerts() == null || snapshot.alerts().isEmpty()) {
            return Result.NONE;
        }
        AlertFeedService assessor = feed.get();
        Instant now = clock.instant();

        // Pass 1 — identity, decision and lifecycle mode, in memory. One entry
        // per revision and one per interpretation, whatever the snapshot
        // repeats. What replaced what is a fact about the whole snapshot (the
        // feed builds it the same way), and it decides which alerts read as
        // records.
        Map<String, NormalizedAlert> supersededBy = AlertDerivations.supersessionIndex(snapshot.alerts());
        Map<String, Seen> byRef = new LinkedHashMap<>();
        Map<String, Seen> byFingerprint = new LinkedHashMap<>();
        for (NormalizedAlert a : snapshot.alerts()) {
            Seen s = identify(a, a == null ? null : supersededBy.get(a.id()), assessor, now);
            if (s == null) continue;
            byRef.putIfAbsent(s.revisionRef(), s);
            byFingerprint.putIfAbsent(s.snapshotFingerprint(), s);
        }
        if (byRef.isEmpty()) return Result.NONE;

        Set<String> stored = new HashSet<>();
        int newRevisions = recordRevisions(snapshot, byRef, now, stored);
        int touched = touchRelisted(snapshot, byRef, now, stored);
        int newSnapshots = recordSnapshots(snapshot, byFingerprint, stored, assessor, now);
        return new Result(newRevisions, touched, newSnapshots);
    }

    /**
     * The interpretation snapshot id for a card, from the cache only — the
     * feed's {@code interpretationRef}. Null when this interpretation has not
     * been recorded (or has fallen out of the cache).
     */
    public String cachedSnapshotId(String snapshotFingerprint) {
        if (snapshotFingerprint == null) return null;
        CachedSnapshot hit;
        synchronized (cacheLock) {
            hit = snapshotCache.get(snapshotFingerprint);
        }
        return hit == null ? null : hit.id().toString();
    }

    /**
     * The recorded snapshot id of {@code a}'s LIVE interpretation as of now —
     * what dispatch stamps on a notification row (C5). {@code replacedBy} is
     * the message that replaced {@code a} in the same ingest snapshot, if any
     * ({@link AlertDerivations#supersessionIndex}); with it the answer is the
     * record snapshot, as on the feed.
     *
     * <p>A cache read, never a query. Null when this exact interpretation has
     * not been recorded yet — the decision or lifecycle moved since the last
     * tick — and the caller then writes the row with no snapshot rather than
     * one that says something else.</p>
     */
    public String liveSnapshotId(NormalizedAlert a, NormalizedAlert replacedBy, AlertFeedService.Assessment assessed) {
        if (a == null || assessed == null) return null;
        return cachedSnapshotId(snapshotFingerprint(AlertSourceFingerprint.revisionRef(a), assessed, a,
                LifecycleMode.live(a, replacedBy, clock.instant())));
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    private record Seen(NormalizedAlert alert,
                        NormalizedAlert replacedBy,
                        String dedupeKey,
                        String fingerprint,
                        String revisionRef,
                        AlertFeedService.Assessment assessed,
                        String snapshotFingerprint) {}

    private static Seen identify(NormalizedAlert a, NormalizedAlert replacedBy,
                                 AlertFeedService assessor, Instant now) {
        if (a == null || a.id() == null || a.id().isBlank()) return null;
        String dedupeKey = AlertIdentity.dedupeKey(a.source(), a.id());
        String fp = AlertSourceFingerprint.fingerprint(a);
        String ref = AlertSourceFingerprint.revisionRef(dedupeKey, fp);
        if (ref == null || a.id().trim().length() > MAX_UPSTREAM_ID
                || dedupeKey.length() > MAX_DEDUPE_KEY || ref.length() > MAX_REVISION_REF) {
            return null;    // no column holds it; the live feed is unaffected
        }
        AlertFeedService.Assessment assessed = assessor.assess(a);
        return new Seen(a, replacedBy, dedupeKey, fp, ref, assessed,
                snapshotFingerprint(ref, assessed, a, LifecycleMode.live(a, replacedBy, now)));
    }

    /**
     * The part of an interpretation that is about TIME rather than about the
     * revision: whether the card is a record, and its lifecycle verdict.
     *
     * <p>One revision under one decision reads two ways. Live, it is current
     * and may carry a primary action and steps; replaced in the snapshot, or
     * looked up in history, it is a record and carries none of them. Before
     * this was part of the fingerprint, a record card's
     * {@code interpretationRef} named the live snapshot — {@code current=true},
     * a primary action — while the card itself said neither (C3 review 1).</p>
     *
     * <p>The verdict only, never the evidence: references, timestamps and the
     * replacing message's key stay out, so a revision gets one snapshot per way
     * it can read, not one per poll. Every input here is what decides the
     * card's {@code presentation.current}, priority, tone, label and whether
     * {@code primaryAction} may exist ({@link AlertInterpreter#interpret}).</p>
     */
    record LifecycleMode(boolean record, String state, boolean current, boolean verified,
                         String reason, boolean allClear) {

        /** As a card holds it: its record flag, its {@code lifecycleInfo} and its all-clear reading. */
        static LifecycleMode of(boolean record, AlertCardDto.LifecycleInfo info, boolean allClear) {
            return info == null
                    ? new LifecycleMode(record, null, false, false, null, allClear)
                    : new LifecycleMode(record, info.state(), info.current(), info.verified(), info.reason(), allClear);
        }

        /**
         * A live card's mode at {@code now} — the derivation the feed's live
         * card makes: a record exactly when something replaced it, the
         * interpreter's live lifecycle, and the shared all-clear reading.
         */
        static LifecycleMode live(NormalizedAlert a, NormalizedAlert replacedBy, Instant now) {
            return of(replacedBy != null,
                    AlertInterpreter.liveLifecycle(a, replacedBy, Set.of(), now),
                    AlertFeedService.allClearOf(a, replacedBy));
        }
    }

    /**
     * The snapshot fingerprint (contract §6): revision ref, policy version,
     * interpreter version, template key and review version, every field of the
     * decision — and the card's {@link LifecycleMode} (C3 review 1, beyond §6),
     * so a record and the live card never share a snapshot. Shared by the
     * writer and every reader that resolves a snapshot id — the feed card, and
     * dispatch through {@link #liveSnapshotId} — so the two cannot drift.
     *
     * <p>Each part is written as {@code name=<length>:<value>} on its own line,
     * the same framing as {@link AlertSourceFingerprint}, so no value can shift
     * into its neighbour.</p>
     */
    static String snapshotFingerprint(String revisionRef, AlertFeedService.Assessment assessed,
                                      NormalizedAlert a, LifecycleMode mode) {
        if (revisionRef == null || assessed == null || assessed.decision() == null || mode == null) return null;
        DispatchTemplate tpl = assessed.template();
        AlertSafetyPolicy.Decision d = assessed.decision();
        StringBuilder sb = new StringBuilder(256);
        line(sb, "revisionRef", revisionRef);
        line(sb, "policyVersion", AlertSafetyPolicy.POLICY_VERSION);
        line(sb, "interpreterVersion", AlertInterpreter.VERSION);
        line(sb, "templateKey", AlertInterpreter.templateKeyOf(a, tpl));
        line(sb, "templateReviewVersion", stringOf(reviewVersionOf(tpl)));
        line(sb, "dispatchMode", d.dispatchMode() == null ? null : d.dispatchMode().wire());
        line(sb, "guidanceMode", d.guidanceMode() == null ? null : d.guidanceMode().wire());
        line(sb, "compatibility", d.compatibility() == null ? null : d.compatibility().wire());
        line(sb, "capActions", String.join(",", capActionsOf(d)));
        line(sb, "movementDirective", d.movementDirective() == null ? null : d.movementDirective().wire());
        line(sb, "asIssuedMovementDirective",
                d.asIssuedMovementDirective() == null ? null : d.asIssuedMovementDirective().wire());
        line(sb, "reason", d.reason());
        line(sb, "record", String.valueOf(mode.record()));
        line(sb, "lifecycleState", mode.state());
        line(sb, "lifecycleCurrent", String.valueOf(mode.current()));
        line(sb, "lifecycleVerified", String.valueOf(mode.verified()));
        line(sb, "lifecycleReason", mode.reason());
        line(sb, "allClear", String.valueOf(mode.allClear()));
        return sha256Hex(sb.toString());
    }

    private static void line(StringBuilder sb, String name, String value) {
        String v = value == null ? "" : value;
        sb.append(name).append('=').append(v.length()).append(':').append(v).append('\n');
    }

    private static List<String> capActionsOf(AlertSafetyPolicy.Decision d) {
        TreeSet<String> sorted = new TreeSet<>();
        if (d.capActions() != null) {
            for (AlertSafetyPolicy.ProtectiveAction p : d.capActions()) {
                if (p != null) sorted.add(p.name());
            }
        }
        return List.copyOf(sorted);
    }

    private static Integer reviewVersionOf(DispatchTemplate tpl) {
        return tpl == null || tpl.safetyReview == null ? null : tpl.safetyReview.version;
    }

    // ------------------------------------------------------------------
    // Source revisions
    // ------------------------------------------------------------------

    /**
     * Inserts each revision not stored yet. Fills {@code stored} with every ref
     * now known to be in the table, which is what a snapshot's FK needs.
     */
    private int recordRevisions(Snapshot snapshot, Map<String, Seen> byRef, Instant now, Set<String> stored) {
        List<String> unknown = new ArrayList<>();
        synchronized (cacheLock) {
            for (String ref : byRef.keySet()) {
                // get(), not containsKey(): a hit is a use, and the LRU is
                // ordered by use — a key re-listed every tick never ages out.
                if (revisionCache.get(ref) != null) stored.add(ref);
                else unknown.add(ref);
            }
        }
        if (unknown.isEmpty()) return 0;

        // Two columns per row, never the payloads: a restart's first tick
        // re-reads every listed revision.
        Map<String, Instant> found = new LinkedHashMap<>();
        for (List<String> chunk : chunks(unknown)) {
            for (AlertSourceRevisionRepo.LastSeenByRef r : revisions.findLastSeenByRefIn(chunk)) {
                found.put(r.getRevisionRef(), r.getLastSeenAt());
            }
        }

        List<AlertSourceRevision> fresh = new ArrayList<>();
        for (String ref : unknown) {
            if (found.containsKey(ref)) continue;
            AlertSourceRevision row = toRevision(byRef.get(ref), seenAt(snapshot, byRef.get(ref).alert(), now));
            if (row != null) fresh.add(row);
        }
        if (!fresh.isEmpty()) {
            revisions.saveAll(fresh);
            for (AlertSourceRevision r : fresh) found.put(r.getRevisionRef(), r.getLastSeenAt());
        }
        synchronized (cacheLock) {
            revisionCache.putAll(found);
        }
        stored.addAll(found.keySet());
        return fresh.size();
    }

    /**
     * When a revision was really seen: now, if its source answered this tick;
     * otherwise that source's last success — carried-forward data is not a
     * sighting.
     */
    private static Instant seenAt(Snapshot snapshot, NormalizedAlert a, Instant now) {
        if (snapshot.freshlyPolled(a.source())) return now;
        Instant last = a.source() == null ? null : snapshot.sourceLastSuccessAt().get(a.source());
        return last == null || last.isAfter(now) ? now : last;
    }

    private AlertSourceRevision toRevision(Seen s, Instant seenAt) {
        String payload;
        try {
            payload = json.writeValueAsString(s.alert());
        } catch (Exception e) {
            log.warn("InterpretationRecorder: could not serialize {}: {}", s.revisionRef(), e.getMessage());
            return null;
        }
        NormalizedAlert a = s.alert();
        AlertSourceRevision row = new AlertSourceRevision();
        row.setRevisionRef(s.revisionRef());
        row.setDedupeKey(s.dedupeKey());
        row.setSource(s.dedupeKey().substring(0, s.dedupeKey().indexOf(':')));
        row.setUpstreamId(a.id().trim());
        row.setFingerprint(s.fingerprint());
        row.setVtecEventKeys(joinOrNull(a.vtecEventKeys()));
        row.setReferenceIds(joinOrNull(a.references()));
        row.setSentAt(parseInstant(a.sent()));
        row.setPayload(payload);
        row.setFirstSeenAt(seenAt);
        row.setLastSeenAt(seenAt);
        return row;
    }

    /**
     * Moves {@code lastSeenAt} for revisions their source re-listed this tick,
     * once per {@link #LAST_SEEN_STEP}, in one statement per chunk.
     *
     * <p>The new time is cached only when the UPDATE moved every row it was
     * handed. A short count means some ref the cache vouched for is not in
     * the table as the cache thinks; the count cannot say which, so the whole
     * chunk is forgotten ({@link #forget}) and left out of {@code stored} — no
     * snapshot is hung on it this tick, and the next tick re-reads it and
     * writes back whatever is missing.</p>
     */
    private int touchRelisted(Snapshot snapshot, Map<String, Seen> byRef, Instant now, Set<String> stored) {
        Instant due = now.minus(LAST_SEEN_STEP);
        List<String> touch = new ArrayList<>();
        synchronized (cacheLock) {
            for (Seen s : byRef.values()) {
                if (!snapshot.freshlyPolled(s.alert().source())) continue;
                Instant known = revisionCache.get(s.revisionRef());
                if (known != null && known.isBefore(due)) touch.add(s.revisionRef());
            }
        }
        if (touch.isEmpty()) return 0;
        int touched = 0;
        List<String> unsure = new ArrayList<>();
        for (List<String> chunk : chunks(touch)) {
            int moved = revisions.touchLastSeen(chunk, now);
            if (moved == chunk.size()) {
                synchronized (cacheLock) {
                    for (String ref : chunk) revisionCache.put(ref, now);
                }
                touched += moved;
            } else {
                unsure.addAll(chunk);
            }
        }
        if (!unsure.isEmpty()) {
            log.warn("InterpretationRecorder: lastSeen touch matched fewer rows than cached; re-reading {} ref(s)",
                    unsure.size());
            forget(unsure);
            unsure.forEach(stored::remove);
        }
        return touched;
    }

    /**
     * Drops {@code refs} from the revision cache, and every cached snapshot
     * hung on one of them — a revision the table does not have cannot have
     * snapshots either (the FK). The next tick that lists them re-reads both.
     */
    private void forget(Collection<String> refs) {
        Set<String> gone = new HashSet<>(refs);
        synchronized (cacheLock) {
            revisionCache.keySet().removeAll(gone);
            snapshotCache.values().removeIf(c -> gone.contains(c.revisionRef()));
        }
    }

    // ------------------------------------------------------------------
    // Interpretation snapshots
    // ------------------------------------------------------------------

    private int recordSnapshots(Snapshot snapshot, Map<String, Seen> byFingerprint, Set<String> storedRefs,
                                AlertFeedService assessor, Instant now) {
        List<String> unknown = new ArrayList<>();
        synchronized (cacheLock) {
            for (Map.Entry<String, Seen> e : byFingerprint.entrySet()) {
                // A revision that could not be stored cannot carry a snapshot (FK).
                if (!storedRefs.contains(e.getValue().revisionRef())) continue;
                if (snapshotCache.get(e.getKey()) == null) unknown.add(e.getKey());   // get(): a hit is a use
            }
        }
        if (unknown.isEmpty()) return 0;

        Map<String, CachedSnapshot> found = new LinkedHashMap<>();
        for (List<String> chunk : chunks(unknown)) {
            for (AlertInterpretationSnapshotRepo.IdByFingerprint row : snapshots.findIdsByFingerprintIn(chunk)) {
                Seen s = byFingerprint.get(row.getFingerprint());
                if (s != null) found.put(row.getFingerprint(), new CachedSnapshot(row.getId(), s.revisionRef()));
            }
        }

        // Which ids are in hand (for references[].resolved) is built once, and
        // only when something new needs interpreting. The card is built at the
        // same `now` as the fingerprint's lifecycle mode, so the two agree.
        List<AlertInterpretationSnapshot> fresh = new ArrayList<>();
        Set<String> knownIds = null;
        for (String fp : unknown) {
            if (found.containsKey(fp)) continue;
            if (knownIds == null) {
                knownIds = new HashSet<>();
                for (NormalizedAlert a : snapshot.alerts()) {
                    if (a != null && a.id() != null) knownIds.add(a.id());
                }
            }
            Seen s = byFingerprint.get(fp);
            AlertInterpretationSnapshot row = toSnapshot(s, fp,
                    assessor.interpretationCard(s.alert(), s.replacedBy(), s.assessed(), knownIds, now),
                    now);
            if (row != null) fresh.add(row);
        }
        if (!fresh.isEmpty()) {
            try {
                snapshots.saveAll(fresh);
            } catch (RuntimeException e) {
                // Most likely the FK: a revision the cache vouched for is not
                // stored. Forget those revisions so the next tick re-reads them
                // instead of failing the same way every five minutes.
                forget(fresh.stream().map(AlertInterpretationSnapshot::getRevisionRef).toList());
                throw e;
            }
            for (AlertInterpretationSnapshot r : fresh) {
                found.put(r.getFingerprint(), new CachedSnapshot(r.getId(), r.getRevisionRef()));
            }
        }
        synchronized (cacheLock) {
            snapshotCache.putAll(found);
        }
        return fresh.size();
    }

    private AlertInterpretationSnapshot toSnapshot(Seen s, String fp, AlertCardDto card, Instant now) {
        AlertSafetyPolicy.Decision d = s.assessed().decision();
        DispatchTemplate tpl = s.assessed().template();

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("dispatchMode", d.dispatchMode() == null ? null : d.dispatchMode().wire());
        decision.put("guidanceMode", d.guidanceMode() == null ? null : d.guidanceMode().wire());
        decision.put("compatibility", d.compatibility() == null ? null : d.compatibility().wire());
        decision.put("capActions", capActionsOf(d));
        decision.put("movementDirective", d.movementDirective() == null ? null : d.movementDirective().wire());
        decision.put("asIssuedMovementDirective",
                d.asIssuedMovementDirective() == null ? null : d.asIssuedMovementDirective().wire());
        decision.put("reason", d.reason());

        // What every reader was told — never who was reading (viewerContext),
        // never how many engaged (communityThreads), never what to tap (actions,
        // which carry the reader's map point).
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("interpreter", card == null ? null : card.interpreter());
        content.put("presentation", card == null ? null : card.presentation());
        content.put("sourceIdentity", card == null ? null : card.sourceIdentity());
        content.put("lifecycleInfo", card == null ? null : card.lifecycleInfo());

        String decisionJson;
        String contentJson;
        try {
            decisionJson = json.writeValueAsString(decision);
            contentJson = json.writeValueAsString(content);
        } catch (Exception e) {
            log.warn("InterpretationRecorder: could not serialize the interpretation of {}: {}",
                    s.revisionRef(), e.getMessage());
            return null;
        }

        AlertInterpretationSnapshot row = new AlertInterpretationSnapshot();
        row.setId(UUID.randomUUID());
        row.setFingerprint(fp);
        row.setRevisionRef(s.revisionRef());
        row.setDedupeKey(s.dedupeKey());
        row.setPolicyVersion(AlertSafetyPolicy.POLICY_VERSION);
        row.setInterpreterVersion(AlertInterpreter.VERSION);
        row.setTemplateKey(truncate(AlertInterpreter.templateKeyOf(s.alert(), tpl), 200));
        row.setTemplateReviewVersion(reviewVersionOf(tpl));
        row.setDecisionJson(decisionJson);
        row.setContentJson(contentJson);
        row.setCreatedAt(now);
        return row;
    }

    // ------------------------------------------------------------------
    // Retention (contract §6 steps 2 and 3; driven by RetentionSweepService)
    // ------------------------------------------------------------------

    /**
     * One batch of snapshots older than {@code retentionDays} that no
     * notification row names and whose revision is no longer listed.
     *
     * <p>Holds the recorder's monitor, so it never runs inside a tick (see
     * the class doc). Deliberately NOT {@code @Transactional}: the repository's
     * DELETE commits on its own, inside the monitor — a transaction around this
     * method would commit after the monitor is released, and a tick could read
     * the doomed rows as stored in between. The candidates leave the cache
     * before the DELETE, so no card is handed an id mid-delete; one the DELETE
     * keeps is simply re-read on its next listing.</p>
     *
     * @return rows deleted (the drain loop stops on a short batch)
     */
    public synchronized int sweepSnapshotsOnce(int retentionDays, int batchSize) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        List<UUID> ids = snapshots.findSweepableIds(cutoff, PageRequest.of(0, batchSize));
        if (ids.isEmpty()) return 0;
        Set<UUID> doomed = new HashSet<>(ids);
        synchronized (cacheLock) {
            snapshotCache.values().removeIf(c -> doomed.contains(c.id()));
        }
        return snapshots.deleteSweepable(ids);
    }

    /**
     * One batch of revisions last seen more than {@code retentionDays} ago
     * that no snapshot points at. Same monitor and commit rule as
     * {@link #sweepSnapshotsOnce}.
     */
    public synchronized int sweepRevisionsOnce(int retentionDays, int batchSize) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        List<String> refs = revisions.findSweepableRefs(cutoff, PageRequest.of(0, batchSize));
        if (refs.isEmpty()) return 0;
        synchronized (cacheLock) {
            revisionCache.keySet().removeAll(refs);
        }
        return revisions.deleteSweepable(refs, cutoff);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Access-ordered, so a key the feed keeps re-listing never ages out —
     * which holds only because every hit is a {@code get()}: {@code containsKey}
     * does not count as a use.
     */
    private static <K, V> Map<K, V> lru(int capacity) {
        int cap = Math.max(16, capacity);
        return new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > cap;
            }
        };
    }

    /** Cache sizes, for tests. */
    int[] cacheSizes() {
        synchronized (cacheLock) {
            return new int[] {revisionCache.size(), snapshotCache.size()};
        }
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        }
        return out;
    }

    private static String joinOrNull(Collection<String> values) {
        if (values == null || values.isEmpty()) return null;
        List<String> kept = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) kept.add(v.trim());
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(iso.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String stringOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
