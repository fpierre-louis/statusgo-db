package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_info")
public class UserInfo {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "user_id", unique = true, updatable = false)
    private String id;

    // ✅ NEW: stable identity key across SitPrep + Rediscover
    @Column(name = "firebase_uid", unique = true)
    private String firebaseUid;

    @Column(name = "user_first_name", nullable = false)
    private String userFirstName;

    @Column(name = "user_last_name", nullable = false)
    private String userLastName;

    @Column(name = "user_email", unique = true, nullable = false)
    private String userEmail;

    @Column(name = "title")
    private String title;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "longitude")
    private String longitude;

    @Column(name = "latitude")
    private String latitude;

    @Column(name = "user_status")
    private String userStatus;

    @Column(name = "user_status_last_updated")
    private Instant userStatusLastUpdated;

    @Column(name = "status_color")
    private String statusColor;

    @Column(name = "profile_image_url")
    private String profileImageURL;

    @Column(name = "fcm_token")
    private String fcmtoken;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_managed_group_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "managed_group_id")
    private Set<String> managedGroupIDs;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_joined_group_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "joined_group_id")
    private Set<String> joinedGroupIDs;

    // Boxed Integer — not primitive int — so legacy rows with NULL in
    // active_group_alert_count don't blow up entity load. The column was
    // added via ddl-auto=update on a populated table; Postgres added it
    // nullable, so rows that existed before the migration carry NULL.
    // Mapping NULL into a primitive int throws PropertyAccessException
    // and bubbles out of /api/me/{uid} as a 500.
    @Column(name = "active_group_alert_count")
    private Integer activeGroupAlertCounts = 0;

    @Column(name = "group_alert_last_updated")
    private Instant groupAlertLastUpdated;

    @Column(name = "subscription")
    private String subscription;

    @Column(name = "subscription_package")
    private String subscriptionPackage;

    @Column(name = "date_subscribed")
    private Instant dateSubscribed;

    /**
     * When this user last hit any authenticated endpoint. Updated by
     * {@link io.sitprep.sitprepapi.service.LastActivityService} from the
     * Firebase auth filter, throttled to ~5 min/user so write pressure
     * stays bounded. Powers the Family-tab presence dots and any
     * "active in the last X" admin views. Null for users who haven't
     * hit a verified-token request since the column was added.
     */
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /**
     * When this user last completed the Readiness Assessment quiz at
     * /sitprep-quiz. Drives the quarterly nudge banner on /home (per
     * docs/ECOSYSTEM_INTEGRATION.md step 6) — when null OR > 90d ago,
     * the banner appears prompting the user to re-run the assessment.
     * Bumped by {@code POST /api/userinfo/me/assessment} when the FE
     * marks the quiz complete; never reset by anything else.
     */
    @Column(name = "last_assessment_at")
    private Instant lastAssessmentAt;

    /**
     * Last *current* location reported by the user's device — distinct from
     * {@link #latitude}/{@link #longitude} which back the user's home address.
     * Populated by {@code PATCH /api/userinfo/me/location} on the frontend's
     * presence ping (throttled — see useTrackPresence). Null until permission
     * granted at least once. Powers the household presence dots
     * (home / nearby / out / unknown) and the future {@code effectiveLocation}
     * resolution in the per-group sharing story.
     */
    @Column(name = "last_known_lat")
    private Double lastKnownLat;

    @Column(name = "last_known_lng")
    private Double lastKnownLng;

    @Column(name = "last_known_location_at")
    private Instant lastKnownLocationAt;

    /**
     * Per-group location sharing preference. Map of {@code groupId} →
     * sharing mode (one of {@code "always"}, {@code "check-in-only"},
     * {@code "never"}). The household feed gates {@code lastKnownLat/Lng}
     * on this map plus the group's alert state.
     *
     * <p>Defaults are not stored: an absent entry means "use the group's
     * default" (Households default to {@code check-in-only}, others to
     * {@code never}). The frontend's {@code shouldShareLocation} helper
     * computes the same way.</p>
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_info_group_location_sharing",
            joinColumns = @JoinColumn(name = "user_info_id"))
    @MapKeyColumn(name = "group_id", length = 64)
    @Column(name = "mode", length = 32)
    private Map<String, String> groupLocationSharing = new HashMap<>();
}