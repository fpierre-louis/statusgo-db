package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.util.PublicCdn;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wire shape for tasks. Image keys are turned into delivery URLs server-side
 * so the frontend just renders. {@code distanceKm} is populated only on
 * community-discover responses; null elsewhere.
 */
public record TaskDto(
        Long id,
        String groupId,
        String requesterEmail,
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

    public static TaskDto fromEntity(Task t, Double distanceKm) {
        List<String> keys = t.getImageKeys() == null ? List.of() : t.getImageKeys();
        List<String> urls = keys.stream()
                .map(PublicCdn::toPublicUrl)
                .collect(Collectors.toList());
        return new TaskDto(
                t.getId(),
                t.getGroupId(),
                t.getRequesterEmail(),
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
}
