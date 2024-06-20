package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_info")
public class UserInfo {
    @Id
    @UuidGenerator
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

    @Column(name = "user_status")
    private String userStatus;

    @Column(name = "status_color")
    private String statusColor;

    @Column(name = "profile_image_url")
    private String profileImageURL;

    @Column(name = "fcm_token")
    private String FCMTokens;

    @ElementCollection
    @CollectionTable(name = "user_managed_group_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "managed_group_id")
    private Set<String> managedGroupIDs;

    @ElementCollection
    @CollectionTable(name = "user_joined_group_ids", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "joined_group_id")
    private Set<String> joinedGroupIDs;

    @Column(name = "group_alert")
    private String groupAlert;

    @Column(name = "subscription")
    private String subscription;

    @Column(name = "subscription_package")
    private String subscriptionPackage;

    @Column(name = "date_subscribed")
    private LocalDateTime dateSubscribed;
}
