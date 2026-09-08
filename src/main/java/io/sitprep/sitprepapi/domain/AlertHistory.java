package io.sitprep.sitprepapi.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One recorded alert. Written by {@code AlertHistoryService} from the ingest
 * snapshot; read back by {@code GET /api/alerts/history}.
 *
 * <p><b>Why this is recorded rather than fetched.</b> NWS retains roughly five
 * days of alert history, measured 2026-09-07: Oklahoma City returns the same 23
 * alerts whether asked since 2026-09-01 or since 2025-01-01. A month cannot be
 * fetched. The ingest tick already holds the whole national picture every five
 * minutes and discards it, so recording is not a new data source — it is not
 * throwing one away.</p>
 *
 * <h2>One row per alert, not one per tick</h2>
 *
 * <p>The primary key is the CAP identifier. A five-minute poll therefore does
 * not create 288 rows a day for one multi-day Extreme Heat Warning; it creates
 * one row and moves {@link #lastSeenAt}. This matters more than it sounds:
 * Oklahoma City's "23 alerts in a month" are 23 <i>distinct</i> Heat Advisories,
 * and a naive per-tick write would have stored them thousands of times over.</p>
 *
 * <p>A CAP identifier is immutable per message — an update is a NEW message
 * with a new id whose {@code references} point back at the one it replaces. So
 * the payload for a given id never changes, re-sightings only move
 * {@link #lastSeenAt}, and the supersession chain survives as distinct rows,
 * which is what lets history show the arc of an event rather than its last
 * frame.</p>
 *
 * <h2>The payload is the whole alert</h2>
 *
 * <p>{@link #payload} is the serialized {@code NormalizedAlert}, verbatim. A
 * history row rehydrates into the same object the live feed maps, so history
 * renders through the same {@code AlertFeedService.toCard} pipeline — the same
 * templates, the same tier, the same safety policy. Storing hand-picked columns
 * instead would create a second, lossy representation of an alert, and two
 * representations of one thing is the drift this epic exists to undo.</p>
 *
 * <p>The remaining columns are an <b>index, not a model</b>: every one of them
 * is derived from the payload at write time purely so a point query can narrow
 * ~45,000 rows to a few hundred without deserializing all of them. See
 * {@code AlertHistoryService.candidateTokens} — {@code matchTypeFor} remains
 * the only thing that decides whether an alert applies to a coordinate.</p>
 */
@Entity
@Table(name = "alert_history")
@Getter
@Setter
@NoArgsConstructor
public class AlertHistory {

    @Id
    @Column(name = "alert_id", length = 255)
    private String alertId;

    @Column(name = "source", length = 16, nullable = false)
    private String source;

    /** NWS product name. Null for USGS and FEMA, which have no equivalent. */
    @Column(name = "event", length = 160)
    private String event;

    @Column(name = "severity", length = 16)
    private String severity;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    /**
     * The alert geometry's first vertex — exactly the value
     * {@code matchTypeFor}'s polygon branch distance-tests. Null when the alert
     * ships no geometry, which is 82% of the NWS feed.
     */
    @Column(name = "centroid_lat")
    private Double centroidLat;

    @Column(name = "centroid_lng")
    private Double centroidLng;

    /**
     * No geometry and no UGC. {@code matchTypeFor} returns {@code BROADCAST}
     * for such an alert at every coordinate, so it can never be filtered out of
     * a candidate set — that is the FEMA case, whose rows carry county and
     * state <i>names</i> but never codes.
     */
    @Column(name = "broadcast", nullable = false)
    private boolean broadcast;

    /**
     * UGC zone codes plus their two-character state prefixes, in one bag —
     * {@code [AZZ560, AZ]}. Tier 2 of the match ladder queries the zone codes;
     * tier 3, used when a point's zone lookup is unavailable, queries the
     * prefixes. They live together because {@code matchTypeFor} reads both off
     * the same {@code ugc} list.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "alert_history_token",
            joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "token", length = 16, nullable = false)
    private Set<String> tokens = new LinkedHashSet<>();

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
