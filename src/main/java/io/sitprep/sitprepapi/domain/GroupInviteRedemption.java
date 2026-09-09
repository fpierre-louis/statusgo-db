package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(
        name = "group_invite_redemptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_group_invite_redemption_user",
                columnNames = {"invite_id", "user_email"}
        )
)
@Data
public class GroupInviteRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invite_id", nullable = false)
    private String inviteId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;
}
