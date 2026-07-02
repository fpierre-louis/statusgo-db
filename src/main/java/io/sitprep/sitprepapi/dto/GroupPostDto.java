package io.sitprep.sitprepapi.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class GroupPostDto {
    private Long id;
    private String tempId;
    private String author;
    private String content;
    private String groupId;
    private String groupName;

    /** createdAt */
    private Instant timestamp;

    /**
     * R2 object key. Clients set this on write after uploading via
     * {@code POST /api/images}. Stored on the entity; read clients
     * should prefer {@link #imageUrl}.
     */
    private String imageKey;

    /** Public delivery URL derived from {@link #imageKey} via PublicCdn. Read-only. */
    private String imageUrl;

    /**
     * Roster of who reacted with which emoji, populated when the listing path
     * resolves it (chat feed always; per-post fetch always; legacy callers
     * may receive an empty map). Frontend reads this as
     * {@code { [emoji]: [{ userEmail, addedAt }, ...] }} and re-derives
     * counts client-side. Replaces the legacy {@code Map<String,Integer>}
     * counts-only shape.
     */
    private Map<String, List<EmojiReactionDto>> reactions;

    /** user-initiated edit moment (you already had this) */
    private Instant editedAt;

    /** last modified (any change) – used for delta/backfill */
    private Instant updatedAt;

    private List<String> tags;
    private List<String> mentions;
    private int commentsCount;

    /**
     * Optional shared location (message §D). Both non-null → the FE renders a
     * static-map thumbnail; {@code locationLabel} is an optional place name.
     */
    private Double latitude;
    private Double longitude;
    private String locationLabel;

    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageUrl;

    /**
     * When non-null, this post is pinned to the top of its group's feed.
     * Posts with {@code pinnedAt != null} render above timestamp-ordered
     * rows on the FE. Admins toggle it via the pin/unpin endpoints.
     */
    private Instant pinnedAt;

    /**
     * Email of the admin who pinned the post. Drives the FE's
     * "📌 Pinned by {firstname}" chip. Cleared on unpin.
     */
    private String pinnedBy;

    /**
     * Denormalized first name of the pinner so the FE renders
     * "📌 Pinned by Alice" without a second profile lookup per
     * pinned post. Populated server-side in
     * {@code GroupPostService.toDto} when {@link #pinnedBy} resolves.
     * Null when the pinner profile can't be resolved (deleted account,
     * legacy row) — the FE falls back to "📌 Pinned".
     */
    private String pinnedByFirstName;

    /** Number of non-author group members whose chat topic had this post delivered. */
    private int deliveredCount;

    /** Latest delivery moment known to the server for this post. */
    private Instant deliveredAt;

    /** Number of non-author group members whose read pointer covers this post. */
    private int readCount;

    /** Latest read moment known to the server for this post. */
    private Instant readAt;
}
