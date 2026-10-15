package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertIdentity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifecycle threads over messages already in hand (S2 C4). No I/O: the record
 * lookup and history both gather their messages first, in batches, and ask
 * this the same question about them.
 *
 * <h2>What a thread is</h2>
 *
 * <p>The messages an issuer LINKED — never messages that merely look alike
 * (that is a presentation cluster, {@code clusterKey}, and it never ends or
 * hides anything). Two issuer links exist, both verified edges:</p>
 * <ul>
 *   <li><b>VTEC</b> — NWS names the event itself
 *       ({@code office.phen.sig.etn.year}); every message about it carries the
 *       key. A message's thread is its PRIMARY entry's event
 *       ({@link AlertInterpreter#primaryVtec}) — the same key as its card's
 *       {@code lifecycleInfo.threadKey}.</li>
 *   <li><b>CAP references</b> — for a message with no VTEC, the messages it
 *       names and the messages that name it. The thread key is
 *       {@code CAP:<root>}; a chain whose messages each name only their
 *       predecessor has a different root per message, so those are joined by
 *       following the references themselves.</li>
 * </ul>
 * <p>A reference never joins two VTEC events: the warning that upgraded a watch
 * names the watch's message, but it is the warning's thread, not the watch's.
 * USGS and FEMA carry neither link, so each alert is its own thread.</p>
 *
 * <p>Nothing here is AI and nothing is inferred from wording.</p>
 */
final class AlertThreads {

    private AlertThreads() {}

    static final String VTEC_PREFIX = "VTEC:";
    static final String CAP_PREFIX = "CAP:";

    /** The message's own thread key — exactly its card's {@code lifecycleInfo.threadKey}. */
    static String threadKeyOf(NormalizedAlert a) {
        return a == null ? null : AlertInterpreter.threadKeyOf(a, AlertInterpreter.primaryVtec(a));
    }

    /** The event key of a {@code VTEC:} thread key, else null. */
    static String vtecEventKeyOf(String threadKey) {
        return threadKey != null && threadKey.startsWith(VTEC_PREFIX) ? threadKey.substring(VTEC_PREFIX.length()) : null;
    }

    static boolean isCapThread(String threadKey) {
        return threadKey != null && threadKey.startsWith(CAP_PREFIX);
    }

    static String dedupeKeyOf(NormalizedAlert a) {
        return a == null ? null : AlertIdentity.dedupeKey(a.source(), a.id());
    }

    /**
     * One message per dedupeKey: the first seen wins, so a caller that lists
     * its freshest copy first (the live snapshot, then the newest revision)
     * keeps that one.
     */
    static Map<String, NormalizedAlert> byDedupeKey(Collection<NormalizedAlert> messages) {
        Map<String, NormalizedAlert> out = new LinkedHashMap<>();
        if (messages == null) return out;
        for (NormalizedAlert m : messages) {
            String key = dedupeKeyOf(m);
            if (key != null) out.putIfAbsent(key, m);
        }
        return out;
    }

    /**
     * The partition of {@code messages} into lifecycle threads: dedupeKey →
     * the dedupeKey that represents its thread. Two messages share a thread
     * when they share a thread key, or when both are CAP-threaded and one
     * names the other in its references.
     */
    static Map<String, String> components(Collection<NormalizedAlert> messages) {
        Map<String, NormalizedAlert> byKey = byDedupeKey(messages);
        UnionFind uf = new UnionFind();
        Map<String, String> firstByThreadKey = new HashMap<>();
        for (Map.Entry<String, NormalizedAlert> e : byKey.entrySet()) {
            String key = e.getKey();
            uf.add(key);
            String threadKey = threadKeyOf(e.getValue());
            if (threadKey == null) continue;
            String first = firstByThreadKey.putIfAbsent(threadKey, key);
            if (first != null) uf.union(first, key);
        }
        for (Map.Entry<String, NormalizedAlert> e : byKey.entrySet()) {
            NormalizedAlert m = e.getValue();
            if (!isCapThread(threadKeyOf(m)) || m.references() == null) continue;
            for (String refId : m.references()) {
                String refKey = AlertIdentity.dedupeKey(m.source(), refId);
                NormalizedAlert named = refKey == null ? null : byKey.get(refKey);
                if (named != null && isCapThread(threadKeyOf(named))) uf.union(e.getKey(), refKey);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : byKey.keySet()) out.put(key, uf.find(key));
        return out;
    }

    /**
     * The thread {@code target} belongs to, among {@code messages} (the target
     * is always a member), oldest first.
     *
     * <ul>
     *   <li>A VTEC thread holds every message carrying the event key in ANY
     *       entry — so a watch's thread holds the message that upgraded it,
     *       whose primary entry is the new warning.</li>
     *   <li>A CAP thread holds the target's component ({@link #components}).</li>
     *   <li>Anything else (USGS, FEMA) is the target alone.</li>
     * </ul>
     */
    static List<NormalizedAlert> membersOf(NormalizedAlert target, Collection<NormalizedAlert> messages) {
        String targetKey = dedupeKeyOf(target);
        Map<String, NormalizedAlert> all = new LinkedHashMap<>();
        if (targetKey != null) all.put(targetKey, target);
        all.putAll(byDedupeKey(messages));
        all.put(targetKey, target);

        String threadKey = threadKeyOf(target);
        String event = vtecEventKeyOf(threadKey);
        List<NormalizedAlert> out = new ArrayList<>();
        if (event != null) {
            for (NormalizedAlert m : all.values()) {
                if (m == target || (m.vtecEventKeys() != null && m.vtecEventKeys().contains(event))) out.add(m);
            }
        } else if (isCapThread(threadKey)) {
            Map<String, String> comps = components(all.values());
            String mine = comps.get(targetKey);
            for (Map.Entry<String, NormalizedAlert> e : all.entrySet()) {
                if (mine != null && mine.equals(comps.get(e.getKey()))) out.add(e.getValue());
            }
        } else {
            out.add(target);
        }
        out.sort(OLDEST_FIRST);
        return out;
    }

    /** When the issuer sent it; USGS's own revision time when it has no {@code sent}. */
    static String sentOf(NormalizedAlert a) {
        if (a == null) return null;
        if (a.sent() != null && !a.sent().isBlank()) return a.sent();
        String rev = a.revisionUpdatedAt();
        return rev == null || rev.isBlank() ? null : rev;
    }

    static Instant sentInstant(NormalizedAlert a) {
        return parseInstant(sentOf(a));
    }

    /** Oldest sent first; unknown times last; then by dedupeKey, so the order never depends on input order. */
    static final Comparator<NormalizedAlert> OLDEST_FIRST = Comparator
            .comparing(AlertThreads::sentInstant, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(a -> {
                String k = dedupeKeyOf(a);
                return k == null ? "" : k;
            });

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.trim());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(iso.trim()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** Disjoint sets over strings; the representative is stable for a given insertion order. */
    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();

        void add(String k) {
            parent.putIfAbsent(k, k);
        }

        String find(String k) {
            String root = k;
            while (!root.equals(parent.getOrDefault(root, root))) root = parent.get(root);
            String cur = k;
            while (!cur.equals(root)) {
                String next = parent.get(cur);
                parent.put(cur, root);
                cur = next;
            }
            return root;
        }

        void union(String a, String b) {
            add(a);
            add(b);
            String ra = find(a);
            String rb = find(b);
            if (!ra.equals(rb)) parent.put(rb, ra);
        }
    }
}
