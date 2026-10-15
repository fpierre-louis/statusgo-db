package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What was active near a coordinate over a past window.
 *
 * <p>Deliberately <b>not</b> "what we pushed to you". Those are different
 * features: pushes are per-user and cover only the products we have template
 * copy for plus a resolvable geocell, which on the measured feed is 81 of 310
 * alerts. A history assembled from pushes would silently omit every Heat
 * Advisory — and Oklahoma City's entire month of history is Heat Advisories.
 * This is the location's record, not the reader's.</p>
 *
 * <p><b>{@code groups} (S2 C4, additive).</b> The same cards, organised by
 * thread: every card is in exactly one {@code lifecycle} group — the messages
 * the issuer linked by VTEC event key or CAP references, a lone message being
 * a thread of one — and lifecycle groups come first. Then, optionally,
 * {@code cluster} groups: cards from DIFFERENT threads that share a
 * presentation {@code clusterKey}. A cluster is a presentation hint only; it
 * never removes a card from {@code alerts} or from its lifecycle group. The row
 * limit is applied after grouping, whole groups at a time, so a displayed group
 * never loses a member (handoff §23).</p>
 *
 * @see AlertCardDto
 */
public record AlertHistoryResponse(List<AlertCardDto> alerts, Meta meta, List<Group> groups) {

    /** Without groups — the shape before S2 C4. */
    public AlertHistoryResponse(List<AlertCardDto> alerts, Meta meta) {
        this(alerts, meta, null);
    }

    public static final String GROUP_LIFECYCLE = "lifecycle";
    public static final String GROUP_CLUSTER = "cluster";

    /**
     * One thread (or one presentation cluster) of the cards in {@code alerts}.
     *
     * @param threadKey  for a lifecycle group, the thread's key ({@code VTEC:<event key>},
     *                   {@code CAP:<root>}, or a lone alert's dedupeKey) — the key of its
     *                   oldest member, which is the furthest back the thread is known;
     *                   for a cluster, the shared {@code clusterKey}
     * @param kind       {@link #GROUP_LIFECYCLE} or {@link #GROUP_CLUSTER}
     * @param members    the members' dedupeKeys, oldest first
     * @param headMember the newest member's dedupeKey — the message the thread
     *                   now rests on
     * @param count      {@code members.size()}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(String threadKey, String kind, List<String> members, String headMember, int count) {}

    /**
     * @param recordingSince ISO-8601 instant of the oldest alert still held,
     *   across all locations — i.e. when this server began watching. Null
     *   before the first tick ever records anything.
     *
     *   <p><b>The empty state is the reason this field exists.</b> "Nothing
     *   happened here in the last 30 days" and "we have only been recording
     *   since Tuesday" are different claims, and on Wednesday only one of them
     *   is true. A quiet history is the NORMAL case, not an edge case — Lehi UT
     *   had one alert in thirty days, Phoenix two, Miami four — so the surface
     *   that renders nothing has to be able to say <i>why</i> it is rendering
     *   nothing.</p>
     * @param days the window actually applied, after clamping to retention. The
     *   caller may ask for more than we keep; this says what it got.
     * @param locationAge whether the coordinate this record was assembled for is
     *   still trustworthy — the SAME shape and the same server-side verdict the
     *   live feed ships.
     *
     *   <p><b>History needs this at least as much as the live feed does, and
     *   arguably more.</b> The failure is identical — a stale coordinate
     *   produces a confident answer about the wrong place — but a history page
     *   is read as a settled record. "Nothing has been active near you in the
     *   last 30 days" is a stronger claim than any single live snapshot makes,
     *   and it is exactly the sentence a user standing somewhere else would be
     *   most reassured and most wrong to believe.</p>
     * @param coverageCaveat the same constant the live feed ships —
     *   {@link AlertFeedResponse#COVERAGE_CAVEAT}, not a second copy of it.
     *   A gap in coverage is a gap in the history too, and the sentence saying
     *   so must be one string in one place or the two will drift.
     */
    public record Meta(String recordingSince,
                       int days,
                       String coverageCaveat,
                       AlertFeedResponse.LocationAge locationAge) {}
}
