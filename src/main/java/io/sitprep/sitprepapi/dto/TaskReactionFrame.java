package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * STOMP frame broadcast on {@code /topic/community/tasks/{zipBucket}} (and
 * {@code /topic/group/{groupId}/tasks} for group-scope tasks) when a viewer
 * adds or removes an emoji reaction on a task. The {@code type:"reaction"}
 * discriminator lets the task-list subscriber tell reaction frames apart
 * from full {@link TaskDto} broadcasts on the same topic.
 *
 * <p>Mirrors {@link PostReactionFrame} exactly (modulo postId → taskId,
 * groupId → routing context) so the eventual Post/Task entity merge
 * collapses both frames into one.</p>
 */
public record TaskReactionFrame(
        String type,        // always "reaction"
        Long taskId,
        /** Routing context: groupId for group-scope tasks, zipBucket for community-scope. */
        String groupId,
        String zipBucket,
        String emoji,
        String userEmail,
        String action,      // "add" | "remove"
        Instant at
) {
    public static TaskReactionFrame add(Long taskId, String groupId, String zipBucket,
                                        String emoji, String userEmail, Instant at) {
        return new TaskReactionFrame("reaction", taskId, groupId, zipBucket, emoji, userEmail, "add", at);
    }

    public static TaskReactionFrame remove(Long taskId, String groupId, String zipBucket,
                                           String emoji, String userEmail, Instant at) {
        return new TaskReactionFrame("reaction", taskId, groupId, zipBucket, emoji, userEmail, "remove", at);
    }
}
