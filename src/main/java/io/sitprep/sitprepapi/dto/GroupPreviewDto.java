package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Sanitized public preview of a group — what a non-member sees on the
 * join-confirmation page (FE: {@code JoinPrivateGroup}) and on the
 * discover surface when a user is deciding whether to join.
 *
 * <p>Crucially, this DTO does NOT expose member emails, admin emails,
 * or pending-member emails. Returning the full {@link io.sitprep.sitprepapi.domain.Group}
 * entity to non-members (the previous behavior of
 * {@code GET /api/groups/{groupId}}) leaked the entire roster to anyone
 * with the group's id. This DTO surfaces only what the user needs to
 * decide whether they want to join: identity (name, type, owner),
 * purpose, scale (member count), location context, and operational
 * state (active-alert badge).</p>
 *
 * <p>{@code viewerStatus} tells the FE which CTA to render without a
 * second round-trip:</p>
 * <ul>
 *   <li>{@code "OWNER"}  — viewer owns this group; render "View your circle"</li>
 *   <li>{@code "ADMIN"}  — viewer is an admin; render "Manage circle"</li>
 *   <li>{@code "MEMBER"} — already in; render "Open circle"</li>
 *   <li>{@code "PENDING"} — pending request exists; render "Request pending"</li>
 *   <li>{@code "NONE"}   — viewer is unrelated; render Join (public) or Request (private)</li>
 * </ul>
 */
public record GroupPreviewDto(
        String groupId,
        String groupName,
        String groupType,
        String description,
        String privacy,
        String ownerName,
        int adminCount,
        int memberCount,
        String address,
        Double latitude,
        Double longitude,
        Instant createdAt,
        boolean alertActive,
        String viewerStatus
) {
    public static final String STATUS_OWNER = "OWNER";
    public static final String STATUS_ADMIN = "ADMIN";
    public static final String STATUS_MEMBER = "MEMBER";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_NONE = "NONE";
}
