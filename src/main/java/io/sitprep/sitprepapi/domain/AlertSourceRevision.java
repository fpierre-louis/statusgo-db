package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One upstream revision of one alert (V110; S2 contract §6): the normalized
 * payload as the issuer last sent it under this {@code revisionRef}, stored
 * ONCE however many polls re-list it.
 *
 * <p>The key is {@code dedupeKey@fp12} ({@code AlertSourceFingerprint}). An NWS
 * message never changes, so it has exactly one revision; a USGS quake or a FEMA
 * declaration that the issuer revises under the same id gets a new row per
 * change. {@code lastSeenAt} moves only when the alert's source answered the
 * poll — a failed source's alerts ride forward in the ingest snapshot and are
 * not sightings (Phase 3 D5).</p>
 *
 * <p>Written by {@code InterpretationRecorder}; swept by
 * {@code RetentionSweepService} 30 days after {@code lastSeenAt}, and only once
 * no {@link AlertInterpretationSnapshot} points at it (the FK is
 * {@code ON DELETE RESTRICT}).</p>
 *
 * <p>{@link Persistable} with a client-assigned key: without it
 * {@code save()} would {@code merge()} — a SELECT per row and, on a key that
 * already exists, an UPDATE that rewrites {@code firstSeenAt}. A revision is
 * inserted once and never rewritten.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "alert_source_revision", indexes = {
        @Index(name = "idx_asr_dedupe", columnList = "dedupe_key"),
        @Index(name = "idx_asr_last_seen", columnList = "last_seen_at")
})
public class AlertSourceRevision implements Persistable<String> {

    @Id
    @Column(name = "revision_ref", length = 320, nullable = false)
    private String revisionRef;

    @Column(name = "dedupe_key", length = 300, nullable = false)
    private String dedupeKey;

    /** {@code NWS}, {@code USGS} or {@code FEMA}. */
    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @Column(name = "upstream_id", length = 280, nullable = false)
    private String upstreamId;

    /** The full 64-hex source fingerprint; the ref keeps only its first 12. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint;

    /** VTEC event keys ({@code office.phen.sig.etn.year}), space-separated, for batched thread lookups. */
    @Column(name = "vtec_event_keys", columnDefinition = "text")
    private String vtecEventKeys;

    /** CAP {@code references} identifiers, space-separated. */
    @Column(name = "reference_ids", columnDefinition = "text")
    private String referenceIds;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** {@code NormalizedAlert} as JSON. */
    @Column(name = "payload", columnDefinition = "text", nullable = false)
    private String payload;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Transient
    private boolean fresh = true;

    @Override
    public String getId() {
        return revisionRef;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
