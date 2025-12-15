package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rs_posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RSPost {

    @Id
    @GeneratedValue
    @Column(name = "rs_post_id", updatable = false, nullable = false)
    private UUID rsPostId;

    @Column(name = "rs_group_id", nullable = false)
    private String rsGroupId;

    @Column(name = "created_by_email", nullable = false)
    private String createdByEmail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;
}