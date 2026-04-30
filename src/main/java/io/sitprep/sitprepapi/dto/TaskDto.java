package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.util.PublicCdn;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wire shape for tasks. Image keys are turned into delivery URLs server-side
 * so the frontend just renders. {@code distanceKm} is populated only on
 * community-discover responses; null elsewhere.
 *
 * <p>Author profile fields ({@code requesterFirstName},
 * {@code requesterLastName}, {@code requesterProfileImageUrl}) are
 * populated server-side by {@code TaskService} so feed surfaces can
 * render the standard post anatomy (avatar + name + 3-dot menu) without
 * fanning out a separate {@code POST /userinfo/profiles/batch} round
 * trip per page-load. Honors the codebase principle "backend shapes the
 * data, frontend just displays" (per CLAUDE.md). Null when the
 * requester's profile can't be resolved (deleted account, etc.).</p>
 */
public record TaskDto(
        Long id,
        String groupId,
        String requesterEmail,
        String requesterFirstName,
        String requesterLastName,
        String requesterProfileImageUrl,
        String claimedByGroupId,
        String claimedByEmail,
        Task.TaskStatus status,
        Task.TaskPriority priority,
        String title,
        String description,
        Double latitude,
        Double longitude,
        String zipBucket,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt,
        Instant claimedAt,
        Instant completedAt,
        Long parentTaskId,
        Set<String> tags,
        List<String> imageKeys,
        List<String> imageUrls,
        /** Filled by community-discover service; null for group/by-me feeds. */
        Double distanceKm
) {

    /**
     * Entity-only conversion. Author profile fields stay null — the
     * caller (typically {@code TaskService}) is expected to fold in
     * profile data via {@link #withAuthor(UserInfo)} so the FE doesn't
     * need a separate profiles-batch round trip.
     */
    public static TaskDto fromEntity(Task t, Double distanceKm) {
        List<String> keys = t.getImageKeys() == null ? List.of() : t.getImageKeys();
        List<String> urls = keys.stream()
                .map(PublicCdn::toPublicUrl)
                .collect(Collectors.toList());
        return new TaskDto(
                t.getId(),
                t.getGroupId(),
                t.getRequesterEmail(),
                /* requesterFirstName */ null,
                /* requesterLastName */ null,
                /* requesterProfileImageUrl */ null,
                t.getClaimedByGroupId(),
                t.getClaimedByEmail(),
                t.getStatus(),
                t.getPriority(),
                t.getTitle(),
                t.getDescription(),
                t.getLatitude(),
                t.getLongitude(),
                t.getZipBucket(),
                t.getDueAt(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getClaimedAt(),
                t.getCompletedAt(),
                t.getParentTaskId(),
                t.getTags() == null ? Set.of() : t.getTags(),
                keys,
                urls,
                distanceKm
        );
    }

    public static TaskDto fromEntity(Task t) {
        return fromEntity(t, null);
    }

    /**
     * Returns a copy of this DTO with author profile fields populated
     * from {@code u}. Used by {@code TaskService.discoverCommunity}
     * after a batch UserInfo lookup. Profile-image key is converted to
     * a public CDN URL via {@link PublicCdn#toPublicUrl} so the FE just
     * sets {@code <img src=...>}.
     */
    public TaskDto withAuthor(UserInfo u) {
        if (u == null) return this;
        String avatarUrl = u.getProfileImageURL();  // already a URL on UserInfo
        return new TaskDto(
                id,
                groupId,
                requesterEmail,
                u.getUserFirstName(),
                u.getUserLastName(),
                avatarUrl,
                claimedByGroupId,
                claimedByEmail,
                status,
                priority,
                title,
                description,
                latitude,
                longitude,
                zipBucket,
                dueAt,
                createdAt,
                updatedAt,
                claimedAt,
                completedAt,
                parentTaskId,
                tags,
                imageKeys,
                imageUrls,
                distanceKm
        );
    }
}
