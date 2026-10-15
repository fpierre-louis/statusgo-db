package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.PostDto.CommunityExtras.AlertLifecycle;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertIdentity;
import io.sitprep.sitprepapi.util.Vtec;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a dispatched alert post knows about its alert (S2 C6, contract §7).
 *
 * <p>Three readers share it, so they cannot disagree:</p>
 * <ul>
 *   <li>{@code AlertDispatchService.resolveOnce} — why it cleared a post, stored
 *       as {@code alert_post.resolved_reason} (V110);</li>
 *   <li>{@code PostService.withAlertAreas} — {@code community.alertLifecycle} on
 *       every alert post of a page, from the live snapshot and that stored
 *       reason, with no query of its own;</li>
 *   <li>{@code PostService.findDtoById} — which alerts make up the post's
 *       thread, for {@code alertDiscussion}.</li>
 * </ul>
 *
 * <p><b>Poll absence never outranks the issuer.</b> An alert that left the feed
 * with no message naming it is {@code not_listed} (never {@code cancelled}),
 * and only when its source answered on the tick that left it out. The live
 * snapshot is the only liveness answer: {@code alert_source_revision.last_seen_at}
 * moves at most hourly and is never read here.</p>
 */
final class AlertPostContext {

    private AlertPostContext() {}

    /** {@code alert_post.resolved_reason}: an issuer Cancel, AllClear, or VTEC CAN named it. */
    static final String ISSUER_CANCELLED = "issuer_cancelled";
    /** {@code alert_post.resolved_reason}: a later message carried the alert on (Update, CON/EXT, UPG). */
    static final String REPLACED = "replaced";
    /** {@code alert_post.resolved_reason}: its own end time passed, or a VTEC EXP named it. */
    static final String EXPIRED = "expired";
    /** {@code alert_post.resolved_reason}: gone from a successful poll with no message naming it. */
    static final String NOT_LISTED = "not_listed";

    /** How a message that names an earlier alert ended it: a lifecycle state and the card's reason. */
    record Edge(String state, String reason) {}

    /**
     * What {@code endedBy} — a message whose CAP {@code references} name an
     * earlier alert — did to that alert, from its own structured fields only.
     *
     * <p>Its own VTEC first, as {@code AlertInterpreter.liveLifecycle} reads an
     * edge: a message whose every entry is {@code CAN} cancels ({@code EXP}
     * expires, {@code UPG} supersedes). Then its CAP shape: a {@code Cancel} or
     * an AllClear calls it off; any other naming message replaces it.</p>
     *
     * <p>A message that carries any event on ({@code UPG}+{@code NEW}, a
     * {@code CON}) is a replacement, never a cancel: when it cannot be told
     * which entry was the earlier alert's, the answer that keeps the hazard
     * live is the safe one.</p>
     */
    static Edge edgeOf(NormalizedAlert endedBy) {
        String vtec = AlertDerivations.vtecEndingState(endedBy);
        if (vtec != null) {
            // The entry vtecEndingState read the state from: the first one.
            for (Vtec v : endedBy.vtec()) {
                if (v != null) return new Edge(vtec, "vtec_" + v.action().toLowerCase(Locale.ROOT));
            }
        }
        if (AlertDerivations.LIFECYCLE_CANCELLED.equals(AlertDerivations.endingStateOf(endedBy))) {
            return new Edge(AlertDerivations.LIFECYCLE_CANCELLED,
                    "cancel".equalsIgnoreCase(trim(endedBy.messageType())) ? "cap_cancel" : "all_clear");
        }
        return new Edge(AlertDerivations.LIFECYCLE_SUPERSEDED, "cap_update");
    }

    /**
     * The live messages that end an earlier alert, keyed by that alert's
     * dispatch key ({@code SOURCE-id}, what {@code alert_post.alert_id} holds).
     *
     * <p>Only a real, public message may end another — the rule
     * {@link AlertDerivations#reverseEdgeIndex} applies — so a drill or an
     * {@code Ack} naming a live warning ends nothing. When several name the same
     * alert, the strongest ending wins (cancel, then expiry, then replacement):
     * once called off it stays called off, whatever order the feed lists them
     * in.</p>
     */
    static Map<String, NormalizedAlert> endingMessages(Collection<NormalizedAlert> live) {
        if (live == null || live.isEmpty()) return Map.of();
        Map<String, NormalizedAlert> out = new HashMap<>();
        for (NormalizedAlert m : live) {
            if (m == null || m.references() == null || m.references().isEmpty()) continue;
            if (!AlertDerivations.mayEndAnother(m)) continue;
            for (String replacedId : m.references()) {
                if (replacedId == null || replacedId.isBlank() || replacedId.equals(m.id())) continue;
                String key = AlertIdentity.toLegacyDispatchKey(m.source(), replacedId);
                if (key == null) continue;
                out.merge(key, m, (was, now) -> rank(edgeOf(now)) > rank(edgeOf(was)) ? now : was);
            }
        }
        return out;
    }

    private static int rank(Edge e) {
        return switch (e.state()) {
            case AlertDerivations.LIFECYCLE_CANCELLED -> 3;
            case AlertDerivations.LIFECYCLE_EXPIRED -> 2;
            default -> 1;
        };
    }

    /**
     * Why {@code resolveOnce} cleared a post: the issuer's naming message when
     * one is live (it outranks the end time — a warning called off at noon that
     * was due to run until 3 pm was cancelled), else its own end time, else
     * absence from a fresh poll.
     */
    static String resolvedReason(boolean expired, NormalizedAlert endedBy) {
        if (endedBy != null) {
            return switch (edgeOf(endedBy).state()) {
                case AlertDerivations.LIFECYCLE_CANCELLED -> ISSUER_CANCELLED;
                case AlertDerivations.LIFECYCLE_EXPIRED -> EXPIRED;
                default -> REPLACED;
            };
        }
        return expired ? EXPIRED : NOT_LISTED;
    }

    /**
     * {@code community.alertLifecycle} for one dispatched post — strongest
     * evidence first:
     *
     * <ol>
     *   <li><b>Listed now</b> — exactly what the feed card's {@code lifecycleInfo}
     *       says ({@link AlertInterpreter#liveLifecycle}, with the same
     *       in-snapshot replacement the feed applies).</li>
     *   <li><b>A live message names it</b> — that issuer edge: with the alert's
     *       own record, through {@code liveLifecycle} (it matches the VTEC
     *       event); without it, from the naming message alone
     *       ({@link #edgeOf}).</li>
     *   <li><b>A stored issuer ending</b> — {@code issuer_cancelled} or
     *       {@code replaced}, written while the naming message was live. It
     *       outranks the alert's own later expiry.</li>
     *   <li><b>The alert's own fields</b> — past its end time, or its own
     *       all-terminal VTEC.</li>
     *   <li><b>A stored {@code expired}</b>.</li>
     *   <li><b>Gone</b> — a stored {@code not_listed} (confirmed by a fresh poll
     *       when it was written), or absent from a poll of its source that just
     *       succeeded: {@code no_longer_listed}, never current.</li>
     *   <li>Otherwise <b>unknown</b> — null, and the post carries no lifecycle:
     *       a just-booted server or a failed poll proves nothing.</li>
     * </ol>
     *
     * @param alert          the alert the post was made from (live snapshot or history), or null
     * @param listed         {@code alert} is in the live snapshot
     * @param replacedInFeed the feed's in-snapshot replacement for a listed alert, or null
     * @param endedBy        a live message naming an unlisted alert ({@link #endingMessages}), or null
     * @param resolvedReason the post's stored {@code alert_post.resolved_reason}, or null
     * @param sourceFresh    the alert's source answered on the tick that built the snapshot
     */
    static AlertLifecycle lifecycle(NormalizedAlert alert, boolean listed, NormalizedAlert replacedInFeed,
                                    NormalizedAlert endedBy, String resolvedReason, boolean sourceFresh,
                                    Instant now) {
        if (listed && alert != null) {
            return of(AlertInterpreter.liveLifecycle(alert, replacedInFeed, Set.of(), now));
        }
        if (endedBy != null) {
            if (alert != null) return of(AlertInterpreter.liveLifecycle(alert, endedBy, Set.of(), now));
            Edge e = edgeOf(endedBy);
            return new AlertLifecycle(e.state(), false, e.reason());
        }
        if (ISSUER_CANCELLED.equals(resolvedReason)) {
            return new AlertLifecycle(AlertDerivations.LIFECYCLE_CANCELLED, false, ISSUER_CANCELLED);
        }
        if (REPLACED.equals(resolvedReason)) {
            return new AlertLifecycle(AlertDerivations.LIFECYCLE_SUPERSEDED, false, REPLACED);
        }
        if (alert != null && !AlertInterpreter.isCurrent(AlertDerivations.lifecycleState(alert, now))) {
            return of(AlertInterpreter.liveLifecycle(alert, null, Set.of(), now));
        }
        if (EXPIRED.equals(resolvedReason)) {
            return new AlertLifecycle(AlertDerivations.LIFECYCLE_EXPIRED, false, EXPIRED);
        }
        if (NOT_LISTED.equals(resolvedReason) || sourceFresh) {
            return new AlertLifecycle(AlertDerivations.LIFECYCLE_NO_LONGER_LISTED, false, NOT_LISTED);
        }
        return null;
    }

    private static AlertLifecycle of(AlertCardDto.LifecycleInfo info) {
        return info == null ? null : new AlertLifecycle(info.state(), info.current(), info.reason());
    }

    /**
     * The dispatch keys of every message in the post's alert's thread that is
     * in hand — what {@code alertDiscussion} looks posts up by.
     *
     * <p>The thread is the alert's lifecycle thread
     * ({@code lifecycleInfo.threadKey}): NWS gives every update a new message
     * id and the dispatcher a new post, so one hazard's conversation is filed
     * under several ids. In hand means the post's own alert (its id and the
     * earlier messages its CAP {@code references} name) plus every live message
     * of the same source that names one of those, shares one of its VTEC
     * events, or has the same thread key — and what each of those names.</p>
     *
     * <p>Memory only: the live snapshot and the alert's own record, never a
     * query. After the event has left the live feed, a later revision that
     * named this alert is no longer in hand; the backward chain still is.</p>
     *
     * @param dispatchKey the post's {@code alert_post.alert_id}
     * @param alert       that alert's record, or null when unknown
     */
    static Set<String> threadDispatchKeys(String dispatchKey, NormalizedAlert alert,
                                          Collection<NormalizedAlert> live) {
        AlertIdentity.Resolved ref = AlertIdentity.resolve(dispatchKey, null);
        if (!ref.isResolved()) return dispatchKey == null ? Set.of() : Set.of(dispatchKey);
        String source = ref.source();

        Set<String> seed = new LinkedHashSet<>();
        seed.add(ref.upstreamId());
        Set<String> events = new LinkedHashSet<>();
        Set<String> threadKeys = new LinkedHashSet<>();
        if (alert != null) {
            addAll(seed, alert.references());
            addAll(events, alert.vtecEventKeys());
            String own = threadKeyOf(alert);
            if (own != null) threadKeys.add(own);
        }

        List<NormalizedAlert> sameSource = live == null ? List.of() : live.stream()
                .filter(m -> m != null && m.id() != null && source.equals(AlertIdentity.knownSource(m.source())))
                .toList();
        // Pass 1 — the CAP chain: the alert itself, and every live message that
        // names it or what it names. Such a message speaks for the alert's event
        // even when the alert's own record has been swept: each of its VTEC
        // entries that carries on or ends an event joins the thread. A NEW entry
        // does not — it starts an event no earlier message belonged to (the
        // warning half of a watch's UPG+NEW is the warning's thread, not the
        // watch's). Its CAP root joins too; a VTEC thread key is its events.
        Set<NormalizedAlert> members = new LinkedHashSet<>();
        for (NormalizedAlert m : sameSource) {
            if (!seed.contains(m.id().trim()) && !intersects(seed, m.references())) continue;
            members.add(m);
            if (m.vtec() != null) {
                for (Vtec v : m.vtec()) {
                    if (v != null && !"NEW".equals(v.action()) && v.eventKey() != null) events.add(v.eventKey());
                }
            }
            String key = threadKeyOf(m);
            if (key != null && key.startsWith("CAP:")) threadKeys.add(key);
        }
        // Pass 2 — the event: every live message sharing a VTEC event or a
        // thread key in hand (another segment of the same warning, a later
        // revision that names only its immediate predecessor).
        for (NormalizedAlert m : sameSource) {
            String key = threadKeyOf(m);
            if (intersects(events, m.vtecEventKeys()) || (key != null && threadKeys.contains(key))) members.add(m);
        }

        Set<String> ids = new LinkedHashSet<>(seed);
        for (NormalizedAlert m : members) {
            ids.add(m.id().trim());
            addAll(ids, m.references());
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String id : ids) {
            String key = AlertIdentity.toLegacyDispatchKey(source, id);
            if (key != null) keys.add(key);
        }
        return keys;
    }

    private static String threadKeyOf(NormalizedAlert a) {
        return AlertInterpreter.threadKeyOf(a, AlertInterpreter.primaryVtec(a));
    }

    private static void addAll(Set<String> into, List<String> ids) {
        if (ids == null) return;
        for (String id : ids) if (id != null && !id.isBlank()) into.add(id.trim());
    }

    private static boolean intersects(Set<String> a, List<String> b) {
        if (a.isEmpty() || b == null) return false;
        for (String s : b) if (s != null && a.contains(s.trim())) return true;
        return false;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
