package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One person saying a map place is "still here" (V85, map-ideal BE-7).
 *
 * <p>Deliberately NOT {@code post_confirm}, which is "Me too" on a post — a
 * different fact. One row per (target, person); a re-confirm moves
 * {@link #confirmedAt} instead of adding a row, so counts are distinct people
 * by construction.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "map_confirmation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_map_confirmation_target_user",
                columnNames = {"target_type", "target_id", "user_email"}),
        indexes = @Index(name = "idx_map_confirmation_target_time",
                columnList = "target_type,target_id,confirmed_at")
)
public class MapConfirmation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code resource | post | osm}. */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    /** {@code resource_listing.id}, community {@code task.id}, or an OSM id like {@code node/123}. */
    @Column(name = "target_id", nullable = false, length = 128)
    private String targetId;

    /** Lower-cased verified email. Never exposed — reads return counts only. */
    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;
}
