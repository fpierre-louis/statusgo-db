package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
@Entity
@Table(
        indexes = {
                @Index(name = "idx_post_group_id", columnList = "group_id")
        }
)
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

    private byte[] image; // Binary data of the image

    // Transient field for base64 encoded image for frontend
    @Transient
    private String base64Image;

    // Field for reactions with emoji keys and their counts
    @ElementCollection
    @BatchSize(size = 50)
    private Map<String, Integer> reactions = new HashMap<>();

    // Timestamp for when the post was last edited
    private Instant editedAt;

    // Tags or hashtags for categorizing posts
    @ElementCollection
    @BatchSize(size = 50)
    private List<String> tags = new ArrayList<>();

    // Ensure that the field is initialized with a default value of 0
    private int commentsCount = 0;

    // Mentions of users in the post
    @ElementCollection
    @BatchSize(size = 50)
    private List<String> mentions = new ArrayList<>();
}
