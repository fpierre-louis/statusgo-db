package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "groups")
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Group {

    @Id
    @Column(name = "group_id", unique = true, nullable = false)
    private String groupId;  // UUID as primary ID (VARCHAR)

    @ElementCollection
    @CollectionTable(name = "group_admin_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "admin_email")
    private List<String> adminEmails;

    private String alert;
    private Instant createdAt;
    private String description;
    private String groupCode;
    private String groupName;
    private String groupType;
    private String lastUpdatedBy;
    private Integer memberCount;

    @ElementCollection
    @CollectionTable(name = "group_member_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "member_email")
    private List<String> memberEmails;

    @ElementCollection
    @CollectionTable(name = "group_pending_member_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "pending_member_email")
    private List<String> pendingMemberEmails;

    private String privacy;

    @ElementCollection
    @CollectionTable(name = "group_sub_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "sub_group_id")
    private List<String> subGroupIDs;

    @ElementCollection
    @CollectionTable(name = "group_parent_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "parent_group_id")
    private List<String> parentGroupIDs;

    private Instant updatedAt;
    private String address;
    private String longitude;
    private String latitude;
    private String zipCode;
    private String ownerName;
    private String ownerEmail;

}