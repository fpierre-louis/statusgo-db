package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator; // Keep import for now if other places use it
import org.hibernate.annotations.UuidGenerator; // ✅ NEW: Import for UUID generation
import java.time.Instant;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_info")
public class UserInfo {
    @Id
    @GeneratedValue // ✅ FIX: Use default strategy or specify generation type
    @UuidGenerator // ✅ FIX: Use @UuidGenerator for UUID generation strategy
    @Column(name = "user_id", unique = true, updatable = false)
    private String id;

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

    @ElementCollection
    @CollectionTable(name = "user_managed_group_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "managed_group_id")
    private Set<String> managedGroupIDs;

    @ElementCollection
    @CollectionTable(name = "user_joined_group_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "joined_group_id")
    private Set<String> joinedGroupIDs;

    @Column(name = "active_group_alert_count", nullable = false)
    private int activeGroupAlertCounts = 0;

    @Column(name = "group_alert_last_updated")
    private Instant groupAlertLastUpdated;

    @Column(name = "subscription")
    private String subscription;

    @Column(name = "subscription_package")
    private String subscriptionPackage;

    @Column(name = "date_subscribed")
    private Instant dateSubscribed;
}