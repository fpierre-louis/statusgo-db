package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A task / request-for-help. Three scopes all share this one entity:
 *
 * <ul>
 *   <li><b>Group task</b> — {@code groupId != null && claimedByGroupId == null}.
 *       Visible only to that group's members. The traditional work-order flow.</li>
 *   <li><b>Community / personal task</b> — {@code groupId == null}. Visible to
 *       anyone in the {@code latitude/longitude} + radius set by the viewer.
 *       Used when an individual asks for help and any nearby group can claim.</li>
 *   <li><b>Group-claimed community task</b> — community-scope task that a group
 *       leader has claimed on behalf of their group. Both the requester and
 *       the claimer-group's members see live status.</li>
 * </ul>
 *
 * <p>{@code zipBucket} (first 3 chars of postcode) is a cheap pre-filter for
 * the community-discover JPQL — by-radius scans hit only rows matching the
 * viewer's bucket before the in-memory Haversine pass.</p>
 */
@Entity
@Getter
@Setter
@Table(
        name = "task",
        indexes = {
                @Index(name = "idx_task_group_status", columnList = "group_id,status"),
                @Index(name = "idx_task_zip_status", columnList = "zip_bucket,status"),
                @Index(name = "idx_task_requester", columnList = "requester_email"),
                @Index(name = "idx_task_claimer", columnList = "claimed_by_email")
        }
)
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null = personal/community-scope; non-null = bound to that group. */
    @Column(name = "group_id")
    private String groupId;

    @Column(name = "requester_email", nullable = false)
    private String requesterEmail;

    /** The group that claimed this task (community → claimed). Null while open. */
    @Column(name = "claimed_by_group_id")
    private String claimedByGroupId;

    /** The specific user inside the claimer group who took it on. Null while open. */
    @Column(name = "claimed_by_email")
    private String claimedByEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskPriority priority;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 4096)
    private String description;

    /** For radius filtering on community-scope tasks. Null otherwise. */
    private Double latitude;
    private Double longitude;

    /** First 3 chars of postcode — see class doc. */
    @Column(name = "zip_bucket", length = 8)
    private String zipBucket;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** R2 image keys (post/<uuid>.jpg style). Receipts, damage photos, etc. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_image_keys", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "image_key")
    private List<String> imageKeys = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "task_tags", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "tag")
    private Set<String> tags = new HashSet<>();

    /** For sub-task hierarchies (work-order breakdowns). Null for top-level tasks. */
    @Column(name = "parent_task_id")
    private Long parentTaskId;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = TaskStatus.OPEN;
        if (priority == null) priority = TaskPriority.MEDIUM;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public enum TaskStatus {
        OPEN, CLAIMED, IN_PROGRESS, DONE, CANCELLED
    }

    public enum TaskPriority {
        LOW, MEDIUM, HIGH, URGENT
    }
}
