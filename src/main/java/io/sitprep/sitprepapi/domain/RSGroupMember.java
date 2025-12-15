package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "rs_group_membership",
        uniqueConstraints = @UniqueConstraint(columnNames = {"rs_group_id", "member_email"}),
        indexes = {
                @Index(name = "idx_rs_mem_group", columnList = "rs_group_id"),
                @Index(name = "idx_rs_mem_email", columnList = "member_email"),
                @Index(name = "idx_rs_mem_status", columnList = "status"),
                @Index(name = "idx_rs_mem_role", columnList = "role")
        }
)
public class RSGroupMember {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "rs_group_member_id", unique = true, updatable = false, nullable = false)
    private String id;

    // ---- Stored columns (source of truth) ----
    @Column(name = "rs_group_id", nullable = false)
    private String groupId;

    @Column(name = "member_email", nullable = false)
    private String memberEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private RSMemberRole role = RSMemberRole.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RSMemberStatus status = RSMemberStatus.PENDING;

    @Column(name = "invited_by_email")
    private String invitedByEmail;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ---- READ-ONLY joins (no schema changes) ----

    /**
     * Optional: access group fields without extra queries.
     * IMPORTANT: referencedColumnName must match RSGroup PK column name: rs_group_id
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rs_group_id", referencedColumnName = "rs_group_id", insertable = false, updatable = false)
    private RSGroup group;

    /**
     * KEY: membership -> UserInfo by email for profileImageURL, names, firebaseUid, etc.
     * READ-ONLY join to avoid write conflicts.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_email", referencedColumnName = "user_email", insertable = false, updatable = false)
    private UserInfo userInfo;

    @PrePersist
    void prePersist() {
        final Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        normalizeEmails();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalizeEmails();
    }

    private void normalizeEmails() {
        if (memberEmail != null) memberEmail = memberEmail.trim().toLowerCase();
        if (invitedByEmail != null) invitedByEmail = invitedByEmail.trim().toLowerCase();
    }
}