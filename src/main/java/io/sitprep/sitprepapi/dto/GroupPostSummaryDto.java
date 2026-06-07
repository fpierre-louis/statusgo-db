package io.sitprep.sitprepapi.dto;

import lombok.*;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupPostSummaryDto {
    private Long id;
    private String groupId;
    private String groupName;

    private String author;                 // email
    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageUrl;

    private String content;
    private Instant timestamp;

    /**
     * When non-null, this post is pinned to the top of its group's
     * feed. Surfaced on every summary so the FE can render a "Pinned"
     * badge consistently in either the dedicated pinned section or in
     * the inline {@code recentPosts} list (a pinned post may appear in
     * both — the FE de-dupes by id). Null on the typical post.
     */
    private Instant pinnedAt;

    /**
     * Email of the admin who last pinned this post; null when the post
     * isn't pinned. The FE renders this as "Pinned by {firstName}" when
     * the matching member is found in the group's roster.
     */
    private String pinnedBy;
}
