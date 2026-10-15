package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertSourceRevision;

import java.util.Collection;
import java.util.List;

/**
 * The batched thread lookup over {@code alert_source_revision} (S2 C4): which
 * stored messages belong with a set of alerts, in ONE statement however many
 * alerts are asked about — never one query per thread member.
 *
 * <p>A fragment of {@link AlertSourceRevisionRepo}, because the predicate is a
 * disjunction whose width depends on the input (one token match per key), which
 * a fixed JPQL string cannot express.</p>
 */
public interface AlertSourceRevisionThreadQuery {

    /**
     * Revisions matching ANY of:
     * <ul>
     *   <li>{@code vtecEventKeys} — whose space-separated {@code vtec_event_keys}
     *       carries one of these keys as a whole token (the same NWS event);</li>
     *   <li>{@code referencedIds} — whose space-separated {@code reference_ids}
     *       carries one of these upstream ids as a whole token (a later message
     *       naming one of them in CAP {@code references});</li>
     *   <li>{@code dedupeKeys} — whose {@code dedupe_key} is one of these
     *       (a message one of them names, looked up exactly).</li>
     * </ul>
     *
     * <p>Whole-token matching, so {@code ...001.1} never matches {@code ...001.12}.
     * Any argument may be empty; all empty returns nothing without a query.</p>
     */
    List<AlertSourceRevision> findThreadCandidates(Collection<String> vtecEventKeys,
                                                   Collection<String> referencedIds,
                                                   Collection<String> dedupeKeys);
}
