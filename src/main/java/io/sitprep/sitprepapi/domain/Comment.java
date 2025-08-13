package io.sitprep.sitprepapi.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Setter
@Getter
@Entity
@Table(
        indexes = {
                @Index(name = "idx_comment_post_id", columnList = "postId")
        }
)
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long postId;
    private String author;
    private String content;
    private Instant timestamp;
}
