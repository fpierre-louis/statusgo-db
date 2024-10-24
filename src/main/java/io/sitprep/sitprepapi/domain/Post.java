package io.sitprep.sitprepapi.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;  // Correct import for Transient
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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
    private LocalDateTime timestamp;

    private byte[] image; // Binary data of the image

    // Transient field for base64 encoded image for frontend
    @Transient
    private String base64Image;
}
