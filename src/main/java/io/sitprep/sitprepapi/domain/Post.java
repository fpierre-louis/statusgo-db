package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.*;

@Setter
@Getter
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String author;
    private String content;

    @Column(name = "group_id")
    private String groupId;

    private String groupName;
    private Instant timestamp;

    // Store as BLOB, fetch lazily, and never serialize raw bytes
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @JsonIgnore
    private byte[] image;

    // Base64 for frontend only (populated in DTO mapping)
    @Transient
    private String base64Image;

    // Reactions
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private Map<String, Integer> reactions = new HashMap<>();

    // Last edit
    private Instant editedAt;

    // Tags
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<String> tags = new ArrayList<>();

    // Denormalized count for quick reads
    private int commentsCount = 0;

    // Mentions
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 64)
    private List<String> mentions = new ArrayList<>();
}
