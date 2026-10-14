package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * What SitPrep said about one source revision under one policy (V110; S2
 * contract §6) — the record a notification row points at, so "what did we tell
 * this person" survives later template, policy and interpreter changes.
 *
 * <p>{@code fingerprint} covers the revision ref, {@code POLICY_VERSION},
 * {@code AlertInterpreter.VERSION}, the template key and its review version,
 * and every field of the policy decision. Any of those changing is a new
 * snapshot; a re-poll of the same revision under the same rules is not.</p>
 *
 * <p>{@code contentJson} is the interpreter, presentation, source-identity and
 * lifecycle blocks as they were at creation. It holds <b>no viewer context and
 * no engagement counts</b>: one snapshot serves every reader, and whatever
 * depends on who is reading, or on what happened since, is re-evaluated at read
 * time.</p>
 *
 * <p>{@code revisionRef} is a plain column, not an association: the row is
 * written and read on its own, and the database FK ({@code ON DELETE RESTRICT})
 * is what stops a revision being swept out from under it.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "alert_interpretation_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_ais_fingerprint", columnNames = "fingerprint"),
        indexes = @Index(name = "idx_ais_revision", columnList = "revision_ref"))
public class AlertInterpretationSnapshot implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", length = 64, nullable = false)
    private String fingerprint;

    @Column(name = "revision_ref", length = 320, nullable = false)
    private String revisionRef;

    @Column(name = "dedupe_key", length = 300, nullable = false)
    private String dedupeKey;

    @Column(name = "policy_version", length = 40, nullable = false)
    private String policyVersion;

    @Column(name = "interpreter_version", length = 40, nullable = false)
    private String interpreterVersion;

    @Column(name = "template_key", length = 200)
    private String templateKey;

    @Column(name = "template_review_version")
    private Integer templateReviewVersion;

    @Column(name = "decision_json", columnDefinition = "text", nullable = false)
    private String decisionJson;

    @Column(name = "content_json", columnDefinition = "text", nullable = false)
    private String contentJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Client-assigned UUID: insert, never merge. Same reason as {@link AlertSourceRevision}. */
    @Transient
    private boolean fresh = true;

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
