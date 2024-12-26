package io.sitprep.sitprepapi.domain;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String author;
    private String content;
    private Long groupId;
    private String groupName;
    private Instant timestamp;

    private byte[] image; // Binary data of the image

    // Transient field for base64 encoded image for frontend
    @Transient
    private String base64Image;

    // Field for reactions with emoji keys and their counts
    @ElementCollection
    private Map<String, Integer> reactions = new HashMap<>();



    // Timestamp for when the post was last edited
    private Instant editedAt;

    // Tags or hashtags for categorizing posts
    @ElementCollection
    private List<String> tags = new ArrayList<>();

    // Ensure that the field is initialized with a default value of 0
    private int commentsCount = 0;

    // Mentions of users in the post
    @ElementCollection
    private List<String> mentions = new ArrayList<>();
}
