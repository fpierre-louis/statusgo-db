package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Set;

/**
 * One ActivityKit push token, paired with the FCM registration token of the
 * device that owns it (V106; docs/epics/notifications-and-native/live_activities/GAMEPLAN.md §3.5).
 *
 * <p>FCM delivers a Live Activity push only with BOTH: {@code message.token}
 * (the device) and {@code apns.live_activity_token} (the activity). The single
 * {@code user_info.fcm_token} column cannot hold that pairing — it is
 * whichever device registered last — so each row carries its own.</p>
 *
 * <p>The owner is always the verified caller of
 * {@code PUT /api/live-activities/tokens}, never a body field.</p>
 */
@Entity
@Getter
@Setter
@Table(name = "live_activity_token",
        uniqueConstraints = @UniqueConstraint(name = "uk_live_activity_token_apns",
                columnNames = "apns_token"))
public class LiveActivityToken {

    public static final String KIND_SITUATION = "situation";
    public static final String KIND_LOCATION_SHARE = "location_share";
    public static final String KIND_CHECKIN_REQUEST = "checkin_request";
    /** P3b: an official NWS warning; update tokens follow an alert thread (V112). */
    public static final String KIND_OFFICIAL_WARNING = "official_warning";
    public static final Set<String> KINDS = Set.of(KIND_SITUATION, KIND_LOCATION_SHARE, KIND_CHECKIN_REQUEST,
            KIND_OFFICIAL_WARNING);

    public static final String TYPE_UPDATE = "update";
    public static final String TYPE_PUSH_TO_START = "push_to_start";
    public static final Set<String> TOKEN_TYPES = Set.of(TYPE_UPDATE, TYPE_PUSH_TO_START);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower-cased. */
    @Column(name = "user_email", nullable = false, length = 320)
    private String userEmail;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "token_type", nullable = false, length = 16)
    private String tokenType;

    /** groupId (situation, checkin_request) or sessionId (location_share); null for push_to_start. */
    @Column(name = "reference_id", length = 128)
    private String referenceId;

    /** Apple {@code Activity.id}; null for push_to_start. */
    @Column(name = "activity_id", length = 64)
    private String activityId;

    /** Hex, lower-cased. The upsert key. */
    @Column(name = "apns_token", nullable = false, length = 256)
    private String apnsToken;

    @Column(name = "fcm_token", nullable = false, length = 512)
    private String fcmToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Set on end, dismissal, an UNREGISTERED reply, or the sweep. Null while live. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
