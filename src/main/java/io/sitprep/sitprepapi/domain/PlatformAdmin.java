package io.sitprep.sitprepapi.domain;

import io.sitprep.sitprepapi.constant.PlatformPermission;
import io.sitprep.sitprepapi.constant.PlatformRole;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Getter
@Setter
@Table(name = "platform_admin")
public class PlatformAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PlatformRole role = PlatformRole.NONE;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "platform_admin_grant",
            joinColumns = @JoinColumn(name = "platform_admin_id"))
    @Column(name = "permission", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private Set<PlatformPermission> extraGrants = EnumSet.noneOf(PlatformPermission.class);

    @Column(name = "granted_by", length = 320)
    private String grantedBy;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(nullable = false)
    private boolean active = true;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (grantedAt == null) grantedAt = now;
        if (updatedAt == null) updatedAt = now;
        if (role == null) role = PlatformRole.NONE;
        if (extraGrants == null) extraGrants = EnumSet.noneOf(PlatformPermission.class);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (role == null) role = PlatformRole.NONE;
        if (extraGrants == null) extraGrants = EnumSet.noneOf(PlatformPermission.class);
    }
}
