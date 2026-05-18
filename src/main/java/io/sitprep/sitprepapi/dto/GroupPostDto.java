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

    private String authorFirstName;
    private String authorLastName;
    private String authorProfileImageURL;

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
}
