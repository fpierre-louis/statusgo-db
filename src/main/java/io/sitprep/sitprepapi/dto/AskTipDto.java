package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
public class AskTipDto {
    private Long id;

    private String authorEmail;
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

    private String title;
    private String body;
    private String coverImageKey;
    private List<String> imageKeys;

    private Set<String> tags;
    private Set<String> hazardTags;

    private Double latitude;
    private Double longitude;
    private String zipBucket;
    private String placeLabel;

    private int voteScore;
    private long viewCount;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant editedAt;

    private Integer viewerVote;
    private Boolean viewerBookmarked;
    private boolean viewerIsAuthor;

    private boolean hazardMatched;
}
