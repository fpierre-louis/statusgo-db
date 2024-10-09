package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "groups")
@Data
public class Group {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long groupId;

    @ElementCollection
    @CollectionTable(name = "group_admin_emails", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "admin_email")
    private List<String> adminEmails;

    private String alert;
    private LocalDateTime createdAt;
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
    private List<String> pendingMemberEmails; // New field for pending members

    private String privacy;

    @ElementCollection
    @CollectionTable(name = "group_sub_group_ids", joinColumns = @JoinColumn(name = "group_id"))
    @Column(name = "sub_group_id")
    private List<String> subGroupIDs;

    private LocalDateTime updatedAt;
    private String zipCode;

    private String ownerName;
    private String ownerEmail;
}
