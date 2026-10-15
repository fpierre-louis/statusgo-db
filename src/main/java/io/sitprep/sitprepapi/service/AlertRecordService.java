package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertHistory;
import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.dto.AlertRecordResponse;
import io.sitprep.sitprepapi.repo.AlertHistoryRepo;
import io.sitprep.sitprepapi.repo.AlertSourceRevisionRepo;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.AlertIdentity;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The canonical record of an official alert (S2 contract §4): one answer for
 * every link to it, and the batched lifecycle the inbox re-evaluates rows with.
 *
 * <h2>Where the answer comes from, strongest first</h2>
 * <ol>
 *   <li><b>The live ingest snapshot.</b> Listed there → {@code live}, and the
 *       card is the feed's live card re-evaluated now
 *       ({@link AlertFeedService#viewerLiveCard}). The snapshot is the ONLY
 *       thing that can say "still listed".</li>
 *   <li><b>{@code alert_source_revision}</b> (V110) — the newest stored
 *       revision's payload — else <b>{@code alert_history}</b> (rows recorded
 *       before V110, and the only table holding real sightings). Either gives
 *       a {@code record}: {@link AlertFeedService#toRecordCard}, never
 *       guidance, never a primary action, never a current directive.</li>
 * </ol>
 *
 * <p>The record's lifecycle is {@link AlertHistoryService#recordLifecycle} —
 * the same rule, the same reverse edge, the same "no longer listed" test as
 * the history page, so a link and the history row never disagree.
 * {@code alert_source_revision.last_seen_at} is retention-grade (it moves at
 * most hourly) and is never read as a sighting: with no history row, only the
 * snapshot's absence after a successful poll of the alert's own source says
 * "no longer listed".</p>
 *
 * <h2>Threads, in batches</h2>
 *
 * <p>The thread ({@link AlertThreads}: VTEC event, else CAP references) is
 * gathered in ROUNDS, each one statement over {@code alert_source_revision}
 * plus the snapshot already in memory: a VTEC event is one round (every
 * message carries the key); a CAP chain is followed outward a round per hop,
 * at most {@link #MAX_THREAD_ROUNDS}. Never a query per member — the cost
 * grows with hops, not with messages. History rows for members are read in
 * one more statement, only when a member's state depends on a sighting.</p>
 *
 * <p>Nothing here is AI, and nothing is inferred from wording.</p>
 */
@Service
public class AlertRecordService {

    private static final Logger log = LoggerFactory.getLogger(AlertRecordService.class);

    /**
     * Rounds a CAP chain is followed outward. NWS names every earlier message
     * on each update, so a chain is usually whole after one or two; a chain
     * whose messages each name only their predecessor needs one per hop.
     */
    static final int MAX_THREAD_ROUNDS = 6;

    /** {@code dedupeKey@fp12} — a revision ref asked for in place of a dedupeKey. */
    private static final Pattern REVISION_SUFFIX = Pattern.compile("@[0-9a-fA-F]{12}$");

    /** Newest stored revision first: first seen, then sent, then ref, so the order never depends on the database. */
    private static final Comparator<AlertSourceRevision> NEWEST_FIRST = Comparator
            .comparing(AlertSourceRevision::getFirstSeenAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(AlertSourceRevision::getSentAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(AlertSourceRevision::getRevisionRef, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    private final AlertIngestService ingest;
    private final AlertFeedService feed;
    private final AlertHistoryService history;
    private final AlertHistoryRepo historyRepo;
    private final AlertSourceRevisionRepo revisions;
    private final ObjectMapper json = new ObjectMapper();

    /** Serve-time clock. A field so a test can pin it. */
    Clock clock = Clock.systemUTC();

    public AlertRecordService(AlertIngestService ingest, AlertFeedService feed, AlertHistoryService history,
                              AlertHistoryRepo historyRepo, AlertSourceRevisionRepo revisions) {
        this.ingest = ingest;
        this.feed = feed;
        this.history = history;
        this.historyRepo = historyRepo;
        this.revisions = revisions;
    }

    /**
     * One alert's lifecycle as of now — what an inbox row (C5) shows instead
     * of the state it was delivered in.
     *
     * @param dedupeKey   the alert, however the ref was spelled
     * @param revisionRef the revision the answer was judged on: the live copy's
     *                    when listed, else the newest held. A ref asked for an
     *                    older revision still gets the alert's CURRENT answer.
     * @param listed      in the live ingest snapshot right now — the only
     *                    source of "still listed"
     * @param info        the card's {@code lifecycleInfo}, decided exactly as
     *                    the canonical lookup's card decides it
     */
    public record Lifecycle(String dedupeKey, String revisionRef, boolean listed, AlertCardDto.LifecycleInfo info) {
        public String state() { return info == null ? null : info.state(); }

        /** {@code lifecycleInfo.current}: active or updated. */
        public boolean current() { return info != null && info.current(); }

        public String threadKey() { return info == null ? null : info.threadKey(); }

        /**
         * Listed AND current — the only time a surface may offer the alert's
         * primary action. A record of an alert still on by its own fields (a
         * sighting inside the poll grace, or no successful poll yet) is current
         * but not listed, and gets none.
         */
        public boolean actionable() { return listed && current(); }
    }

    // ------------------------------------------------------------------
    // The canonical lookup
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AlertRecordResponse lookup(String key, String sourceHint, Double lat, Double lng, String context) {
        return lookup(key, sourceHint, lat, lng, context, null);
    }

    /**
     * {@code GET /api/alerts/record}.
     *
     * @param key        {@code SOURCE:id}, {@code SOURCE-id}, or a bare id with {@code sourceHint}
     * @param lat        the reader's point, for {@code viewerContext} and
     *                   {@code location.matchType}; both or neither. A link
     *                   resolves wherever the reader is — out of area is said
     *                   honestly ({@code matchType} null, containment
     *                   {@code outside}/{@code unknown}), never refused.
     * @param context    {@code current} or {@code home}, as the feed takes it
     * @param fixedAt    when the point was captured, or null
     */
    @Transactional(readOnly = true)
    public AlertRecordResponse lookup(String key, String sourceHint, Double lat, Double lng,
                                      String context, Instant fixedAt) {
        Instant now = clock.instant();
        Live live = live(now);
        String ctx = AlertInterpreter.normalizeContext(context);
        AlertRecordResponse.Meta meta = new AlertRecordResponse.Meta(
                live.snapshot().lastSuccessAt() == null ? null : live.snapshot().lastSuccessAt().toString(),
                AlertFeedResponse.COVERAGE_CAVEAT, now.toString(), ctx);

        AlertIdentity.Resolved resolved = AlertIdentity.resolve(key, sourceHint);
        if (resolved.status() == AlertIdentity.Status.AMBIGUOUS) {
            return new AlertRecordResponse(AlertRecordResponse.STATUS_AMBIGUOUS, null, null, null, null, meta);
        }
        if (!resolved.isResolved()) return notFound(meta);
        String dk = resolved.dedupeKey();

        Held held = new Held(live);
        NormalizedAlert listed = live.byKey().get(dk);
        List<AlertSourceRevision> own = new ArrayList<>(revisions.findByDedupeKeyIn(List.of(dk)));
        own.sort(NEWEST_FIRST);
        held.addRevisions(own);
        AlertHistory row = null;
        if (listed == null) {
            row = historyRows(List.of(dk)).get(dk);
            if (row != null) held.addHistory(dk, row);
        }
        NormalizedAlert target = listed != null ? listed : held.stored(dk);
        if (target == null || AlertSafetyPolicy.publishabilityBlockReason(target) != null) return notFound(meta);

        followThread(target, held);
        Map<String, NormalizedAlert> endedBy = AlertDerivations.reverseEdgeMessages(held.all());
        Set<String> knownIds = held.knownIds();
        List<NormalizedAlert> members = AlertThreads.membersOf(target, held.all());
        Map<String, AlertHistory> rows = memberHistory(members, row, dk, endedBy, live, held, now);

        AlertInterpreter.Viewer viewer = lat == null || lng == null
                ? null : new AlertInterpreter.Viewer(lat, lng, fixedAt, ctx);
        MatchType match = viewer == null ? null : matchAt(target, lat, lng);

        AlertCardDto card;
        if (listed != null) {
            card = feed.viewerLiveCard(target, match, live.supersededBy().get(target.id()), viewer, knownIds);
        } else {
            NormalizedAlert ender = AlertHistoryService.endingMessageOf(target, endedBy);
            String state = AlertHistoryService.recordLifecycle(target, ender,
                    notListed(target, row, live.snapshot()), now);
            card = feed.toRecordCard(target, match, AppConfigResource.alertsRadiusMi(), state, viewer, ender, knownIds);
        }

        List<AlertRecordResponse.Member> thread = new ArrayList<>(members.size());
        for (NormalizedAlert m : members) {
            String mk = AlertThreads.dedupeKeyOf(m);
            Lifecycle l = lifecycleOf(mk, m, held, endedBy, rows.get(mk), knownIds, now);
            thread.add(new AlertRecordResponse.Member(mk, l.revisionRef(), l.state(), AlertThreads.sentOf(m)));
        }

        Set<String> refs = new LinkedHashSet<>();
        if (listed != null) refs.add(AlertSourceFingerprint.revisionRef(listed));
        for (AlertSourceRevision r : own) refs.add(r.getRevisionRef());
        String historyRef = held.historyRef(dk);
        if (historyRef != null) refs.add(historyRef);

        return new AlertRecordResponse(
                listed != null ? AlertRecordResponse.STATUS_LIVE : AlertRecordResponse.STATUS_RECORD,
                card,
                target.geometry(),
                new AlertRecordResponse.RecordThread(AlertThreads.threadKeyOf(target), List.copyOf(thread)),
                List.copyOf(refs),
                meta);
    }

    private static AlertRecordResponse notFound(AlertRecordResponse.Meta meta) {
        return new AlertRecordResponse(AlertRecordResponse.STATUS_NOT_FOUND, null, null, null, null, meta);
    }

    private MatchType matchAt(NormalizedAlert a, double lat, double lng) {
        Set<String> zones = ingest.zoneCodesForPoint(lat, lng);
        return AlertIngestService.matchTypeFor(a, lat, lng, AppConfigResource.alertsRadiusMi() * 1.609344,
                zones, AlertIngestService.statePrefixesOf(zones));
    }

    /**
     * Gather the target's thread into {@code held}, a round at a time.
     *
     * <p>Each round asks, in ONE statement: the VTEC event's messages (first
     * round only — every message of the event carries the key, so one round
     * holds them all), the messages naming any not-yet-expanded member in CAP
     * {@code references} (its successors, and so its ending), and the messages
     * those members name that are not yet in hand (its predecessors, and so
     * {@code references[].resolved}). A CAP thread then expands the members
     * the round added (stored or live), until none is left or the rounds run out. USGS and
     * FEMA have neither link: no round at all.</p>
     */
    private void followThread(NormalizedAlert target, Held held) {
        String threadKey = AlertThreads.threadKeyOf(target);
        String event = AlertThreads.vtecEventKeyOf(threadKey);
        if (event == null && !AlertThreads.isCapThread(threadKey)) return;

        Set<String> expanded = new HashSet<>();
        List<NormalizedAlert> frontier = List.of(target);
        Set<String> vtec = event == null ? Set.of() : Set.of(event);
        for (int round = 0; round < MAX_THREAD_ROUNDS; round++) {
            Set<String> ids = new LinkedHashSet<>();
            Set<String> named = new LinkedHashSet<>();
            for (NormalizedAlert m : frontier) {
                String mk = AlertThreads.dedupeKeyOf(m);
                if (mk == null || !expanded.add(mk)) continue;
                ids.add(m.id().trim());
                if (m.references() == null) continue;
                for (String ref : m.references()) {
                    String rk = AlertIdentity.dedupeKey(m.source(), ref);
                    if (rk != null && !held.has(rk)) named.add(rk);
                }
            }
            if (ids.isEmpty() && named.isEmpty() && vtec.isEmpty()) break;
            held.addRevisions(revisions.findThreadCandidates(vtec, ids, named));
            vtec = Set.of();
            if (event != null) break;

            // Live members count too: a snapshot message joined to the thread
            // may be named by a stored one the rounds have not reached yet.

            List<NormalizedAlert> next = new ArrayList<>();
            for (NormalizedAlert m : AlertThreads.membersOf(target, held.all())) {
                if (!expanded.contains(AlertThreads.dedupeKeyOf(m))) next.add(m);
            }
            if (next.isEmpty()) break;
            frontier = next;
        }
    }

    /**
     * History rows for the members whose state hangs on a sighting — not
     * listed, nothing ended them, and on by their own fields — in one
     * statement, and none at all when no member needs one.
     */
    private Map<String, AlertHistory> memberHistory(List<NormalizedAlert> members, AlertHistory targetRow,
                                                    String targetKey, Map<String, NormalizedAlert> endedBy,
                                                    Live live, Held held, Instant now) {
        Map<String, AlertHistory> rows = new HashMap<>();
        if (targetRow != null) rows.put(targetKey, targetRow);
        Set<String> need = new LinkedHashSet<>();
        for (NormalizedAlert m : members) {
            String mk = AlertThreads.dedupeKeyOf(m);
            if (mk == null || mk.equals(targetKey) || live.byKey().containsKey(mk)) continue;
            if (AlertHistoryService.endingMessageOf(m, endedBy) != null) continue;
            String own = AlertDerivations.lifecycleState(m, now);
            if (AlertInterpreter.isCurrent(own)) need.add(mk);
        }
        if (!need.isEmpty()) rows.putAll(historyRows(need));
        return rows;
    }

    // ------------------------------------------------------------------
    // The batch C5's inbox page calls
    // ------------------------------------------------------------------

    /**
     * The CURRENT lifecycle of each alert a page of rows names — one call per
     * page, a bounded number of statements however many rows: none when every
     * alert is listed; otherwise one revisions read, one history read and one
     * successor read.
     *
     * <p>Each ref may be a dedupeKey in any spelling {@link AlertIdentity#resolve}
     * accepts with no hint ({@code SOURCE:id}, {@code SOURCE-id}) or a
     * {@code dedupeKey@fp12} revision ref. The map is keyed by the ref AS
     * GIVEN. A ref that is blank, ambiguous (a bare id), unknown, or names a
     * message never rendered (a retraction, a drill) is simply absent — the
     * caller shows the row without a re-evaluated alert.</p>
     *
     * <p>A listed alert is judged as the feed judges it (the snapshot's own
     * replacing message); a record as the history page judges it (the stored
     * and live messages naming it, then its own fields, then a successful
     * poll's absence). The reverse edge reads successors one hop out — what
     * ENDED the alert, which is all a lifecycle needs; the full thread is the
     * lookup's.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Lifecycle> lifecycleFor(Collection<String> refs) {
        if (refs == null || refs.isEmpty()) return Map.of();
        Instant now = clock.instant();
        Live live = live(now);

        Map<String, String> asked = new LinkedHashMap<>();   // ref as given -> dedupeKey
        for (String ref : refs) {
            String dk = dedupeKeyOfRef(ref);
            if (dk != null) asked.put(ref, dk);
        }
        if (asked.isEmpty()) return Map.of();

        Map<String, Lifecycle> byKey = new HashMap<>();
        Set<String> notLive = new LinkedHashSet<>();
        for (String dk : new LinkedHashSet<>(asked.values())) {
            NormalizedAlert a = live.byKey().get(dk);
            if (a == null) {
                notLive.add(dk);
                continue;
            }
            if (AlertSafetyPolicy.publishabilityBlockReason(a) != null) continue;
            byKey.put(dk, new Lifecycle(dk, AlertSourceFingerprint.revisionRef(a), true,
                    AlertInterpreter.liveLifecycle(a, live.supersededBy().get(a.id()), live.ids(), now)));
        }

        if (!notLive.isEmpty()) {
            Held held = new Held(live);
            held.addRevisions(revisions.findByDedupeKeyIn(notLive));
            Map<String, AlertHistory> rows = historyRows(notLive);
            rows.forEach(held::addHistory);

            List<NormalizedAlert> targets = new ArrayList<>();
            for (String dk : notLive) {
                NormalizedAlert a = held.stored(dk);
                if (a != null && AlertSafetyPolicy.publishabilityBlockReason(a) == null) targets.add(a);
            }
            if (!targets.isEmpty()) {
                Set<String> ids = new LinkedHashSet<>();
                Set<String> named = new LinkedHashSet<>();
                for (NormalizedAlert a : targets) {
                    ids.add(a.id().trim());
                    if (a.references() == null) continue;
                    for (String ref : a.references()) {
                        String rk = AlertIdentity.dedupeKey(a.source(), ref);
                        if (rk != null && !held.has(rk)) named.add(rk);
                    }
                }
                held.addRevisions(revisions.findThreadCandidates(Set.of(), ids, named));

                Map<String, NormalizedAlert> endedBy = AlertDerivations.reverseEdgeMessages(held.all());
                Set<String> knownIds = held.knownIds();
                for (NormalizedAlert a : targets) {
                    String dk = AlertThreads.dedupeKeyOf(a);
                    byKey.put(dk, lifecycleOf(dk, a, held, endedBy, rows.get(dk), knownIds, now));
                }
            }
        }

        Map<String, Lifecycle> out = new LinkedHashMap<>();
        asked.forEach((ref, dk) -> {
            Lifecycle l = byKey.get(dk);
            if (l != null) out.put(ref, l);
        });
        return out;
    }

    /** {@code SOURCE:id}, {@code SOURCE-id} or {@code dedupeKey@fp12} → the dedupeKey; else null. */
    static String dedupeKeyOfRef(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String s = ref.trim();
        java.util.regex.Matcher m = REVISION_SUFFIX.matcher(s);
        if (m.find()) s = s.substring(0, m.start());
        AlertIdentity.Resolved r = AlertIdentity.resolve(s, null);
        return r.isResolved() ? r.dedupeKey() : null;
    }

    /**
     * One message's lifecycle: the feed's judgment when it is listed, the
     * history page's when it is not — the rule the lookup's card and thread
     * members and the inbox batch all share.
     */
    private Lifecycle lifecycleOf(String dk, NormalizedAlert a, Held held, Map<String, NormalizedAlert> endedBy,
                                  AlertHistory row, Set<String> knownIds, Instant now) {
        NormalizedAlert listed = held.live().byKey().get(dk);
        if (listed != null) {
            return new Lifecycle(dk, AlertSourceFingerprint.revisionRef(listed), true,
                    AlertInterpreter.liveLifecycle(listed, held.live().supersededBy().get(listed.id()),
                            knownIds, now));
        }
        NormalizedAlert ender = AlertHistoryService.endingMessageOf(a, endedBy);
        String state = AlertHistoryService.recordLifecycle(a, ender, notListed(a, row, held.live().snapshot()), now);
        return new Lifecycle(dk, held.refOf(dk, a), false,
                AlertInterpreter.recordLifecycle(a, state, ender, knownIds, now));
    }

    /**
     * Is an alert the snapshot does not hold "no longer listed"? With a
     * history row, history's own test — its real sightings and the two-poll
     * grace. Without one (a revision only), the snapshot's absence after a
     * successful poll of the alert's own source; never the revision's
     * retention-grade {@code last_seen_at}. No successful poll yet: unknown,
     * which labels nothing.
     */
    private boolean notListed(NormalizedAlert a, AlertHistory row, Snapshot snapshot) {
        if (row != null) return history.noLongerListed(a, row, Set.of(), snapshot);
        return AlertHistoryService.sourceLastSuccessAt(snapshot, a) != null;
    }

    /**
     * {@code alert_history} rows for these alerts, in one statement. The table
     * is keyed by the bare upstream id, so a row counts only when its source
     * matches too — a USGS row never answers for an NWS key.
     */
    private Map<String, AlertHistory> historyRows(Collection<String> dedupeKeys) {
        Set<String> wanted = new HashSet<>(dedupeKeys);
        Set<String> ids = new LinkedHashSet<>();
        for (String dk : dedupeKeys) {
            int colon = dk.indexOf(':');
            if (colon > 0) ids.add(dk.substring(colon + 1));
        }
        Map<String, AlertHistory> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (AlertHistory row : historyRepo.findAllById(ids)) {
            String dk = AlertIdentity.dedupeKey(row.getSource(), row.getAlertId());
            if (dk != null && wanted.contains(dk)) out.put(dk, row);
        }
        return out;
    }

    private Live live(Instant now) {
        Snapshot snap = ingest.getSnapshot();
        if (snap == null) snap = new Snapshot(List.of(), now, null, Map.of(), Set.of());
        Map<String, NormalizedAlert> byKey = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (NormalizedAlert a : snap.alerts()) {
            String dk = AlertThreads.dedupeKeyOf(a);
            if (dk != null) byKey.putIfAbsent(dk, a);
            if (a != null && a.id() != null) ids.add(a.id());
        }
        return new Live(snap, byKey, AlertDerivations.supersessionIndex(snap.alerts()), ids);
    }

    /** The snapshot, indexed once per request. */
    private record Live(Snapshot snapshot, Map<String, NormalizedAlert> byKey,
                        Map<String, NormalizedAlert> supersededBy, Set<String> ids) {}

    /**
     * Every message in hand for one request: the live snapshot, then the
     * newest stored revision of each other alert, then history payloads for
     * what has no revision. One copy per dedupeKey, freshest first.
     */
    private final class Held {
        private final Live live;
        private final Map<String, AlertSourceRevision> bestRow = new HashMap<>();
        private final Map<String, NormalizedAlert> stored = new LinkedHashMap<>();
        private final Map<String, String> storedRef = new HashMap<>();
        private final Map<String, String> historyRef = new HashMap<>();

        Held(Live live) {
            this.live = live;
        }

        Live live() {
            return live;
        }

        /** Keep the newest revision per alert; parse only the one kept. */
        void addRevisions(Collection<AlertSourceRevision> rows) {
            if (rows == null) return;
            for (AlertSourceRevision r : rows) {
                if (r == null || r.getDedupeKey() == null) continue;
                AlertSourceRevision was = bestRow.get(r.getDedupeKey());
                if (was != null && NEWEST_FIRST.compare(was, r) <= 0) continue;
                NormalizedAlert a = parse(r.getPayload(), r.getRevisionRef());
                if (a == null) continue;
                bestRow.put(r.getDedupeKey(), r);
                stored.put(r.getDedupeKey(), a);
                storedRef.put(r.getDedupeKey(), r.getRevisionRef());
            }
        }

        /** A history row's payload, for an alert with no stored revision (rows from before V110). */
        void addHistory(String dk, AlertHistory row) {
            NormalizedAlert a = history.rehydrate(row);
            if (a == null) return;
            historyRef.put(dk, AlertSourceFingerprint.revisionRef(a));
            stored.putIfAbsent(dk, a);
        }

        NormalizedAlert stored(String dk) {
            return stored.get(dk);
        }

        String historyRef(String dk) {
            return historyRef.get(dk);
        }

        /** The revision this message was judged on. */
        String refOf(String dk, NormalizedAlert a) {
            String ref = storedRef.get(dk);
            return ref != null ? ref : AlertSourceFingerprint.revisionRef(a);
        }

        boolean has(String dk) {
            return stored.containsKey(dk) || live.byKey().containsKey(dk);
        }

        /** Live first, so {@link AlertThreads#byDedupeKey}'s first-wins keeps the listed copy. */
        List<NormalizedAlert> all() {
            List<NormalizedAlert> out = new ArrayList<>(live.snapshot().alerts().size() + stored.size());
            out.addAll(live.snapshot().alerts());
            out.addAll(stored.values());
            return out;
        }

        /** Upstream ids of every message in hand, for {@code references[].resolved}. */
        Set<String> knownIds() {
            Set<String> out = new HashSet<>(live.ids());
            for (NormalizedAlert a : stored.values()) if (a.id() != null) out.add(a.id());
            return out;
        }
    }

    private NormalizedAlert parse(String payload, String ref) {
        if (payload == null) return null;
        try {
            return json.readValue(payload, NormalizedAlert.class);
        } catch (Exception e) {
            log.warn("AlertRecord: unreadable revision payload {}: {}", ref, e.getMessage());
            return null;
        }
    }
}
