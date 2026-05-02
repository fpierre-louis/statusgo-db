package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.TaskComment;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.TaskCommentDto;
import io.sitprep.sitprepapi.repo.TaskCommentRepo;
import io.sitprep.sitprepapi.repo.TaskRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Comments on tasks (community-feed posts). Mirrors {@link CommentService}
 * exactly so the eventual Post/Task entity merge collapses both surfaces
 * with no semantic drift.
 *
 * <ul>
 *   <li>Creates / updates / deletes comments</li>
 *   <li>Broadcasts EXACTLY ONCE on commit (afterCommit synchronization)</li>
 *   <li>Enriches author profile fields (name, avatar) so the FE renders
 *       without a separate batch profile lookup</li>
 *   <li>Fires a presence-aware notification to the task author when someone
 *       else comments on their post</li>
 * </ul>
 *
 * <p>Replies use the quote-prefix content convention from {@code PostComments}
 * ({@code "> Replying to {name}:\n> {snippet}\n\n{content}"}). No
 * {@code parentCommentId} column — see {@link TaskComment} class doc.</p>
 */
@Service
public class TaskCommentService {

    private static final Logger log = LoggerFactory.getLogger(TaskCommentService.class);

    private final TaskCommentRepo commentRepo;
    private final TaskRepo taskRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;
    private final NotificationService notificationService;

    public TaskCommentService(TaskCommentRepo commentRepo,
                              TaskRepo taskRepo,
                              UserInfoRepo userInfoRepo,
                              WebSocketMessageSender ws,
                              NotificationService notificationService) {
        this.commentRepo = commentRepo;
        this.taskRepo = taskRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
        this.notificationService = notificationService;
    }

    // --------------------------------------------------------------------------------------------
    // Create
    // --------------------------------------------------------------------------------------------
    @Transactional
    public TaskCommentDto createCommentFromDto(TaskCommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("TaskCommentDto is null");
        if (dto.getTaskId() == null) throw new IllegalArgumentException("taskId is required");
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("content is required");
        }
        if (dto.getAuthor() == null || dto.getAuthor().trim().isEmpty()) {
            // Match CommentService's lenient default — the resource layer
            // overwrites with the verified token email anyway, so this is a
            // belt-and-suspenders for non-resource paths.
            dto.setAuthor("anonymous@sitprep");
        }

        TaskComment c = new TaskComment();
        c.setTaskId(dto.getTaskId());
        c.setAuthor(dto.getAuthor().trim());
        c.setContent(dto.getContent());
        // @CreatedDate / @LastModifiedDate auditing populates timestamp/updatedAt

        TaskComment saved = commentRepo.save(c);

        TaskCommentDto out = toDto(saved);
        out.setTempId(dto.getTempId()); // preserve optimistic correlation
        enrichAuthor(out);

        // Broadcast AFTER COMMIT to avoid duplicates on rollback
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendNewTaskComment(saved.getTaskId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for new task comment id={}", saved.getId(), e);
                }
                try {
                    notifyTaskAuthorOnNewComment(saved, out);
                } catch (Exception e) {
                    log.error("Notification fan-out failed for new task comment id={}", saved.getId(), e);
                }
            }
        });

        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Update
    // --------------------------------------------------------------------------------------------
    @Transactional
    public TaskCommentDto updateCommentFromDto(TaskCommentDto dto) {
        if (dto == null) throw new IllegalArgumentException("TaskCommentDto is null");
        if (dto.getId() == null) throw new IllegalArgumentException("id is required for update");

        TaskComment existing = commentRepo.findById(dto.getId())
                .orElseThrow(() -> new IllegalArgumentException("TaskComment not found: " + dto.getId()));

        // Ownership check — same MVP shape as CommentService.
        if (dto.getAuthor() != null && !dto.getAuthor().isBlank()) {
            if (existing.getAuthor() != null &&
                    !existing.getAuthor().equalsIgnoreCase(dto.getAuthor().trim())) {
                throw new SecurityException("Not allowed to edit this comment.");
            }
        }

        if (dto.getContent() != null && !dto.getContent().trim().isEmpty()) {
            existing.setContent(dto.getContent());
        }
        existing.setEditedAt(Instant.now());

        TaskComment saved = commentRepo.save(existing);

        TaskCommentDto out = toDto(saved);
        out.setTempId(dto.getTempId());
        out.setEdited(true);
        enrichAuthor(out);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Reuse the same topic for create + edit deltas.
                    ws.sendNewTaskComment(saved.getTaskId(), out);
                } catch (Exception e) {
                    log.error("WS broadcast failed for edit task comment id={}", saved.getId(), e);
                }
            }
        });

        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Delete (REST path: look up taskId from the comment)
    // --------------------------------------------------------------------------------------------
    @Transactional
    public void deleteCommentByIdAndBroadcast(Long id) {
        if (id == null) return;
        TaskComment c = commentRepo.findById(id).orElse(null);
        if (c == null) return;

        Long taskId = c.getTaskId();
        commentRepo.deleteById(id);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendTaskCommentDeletion(taskId, id);
                } catch (Exception e) {
                    log.error("WS broadcast failed for delete task comment id={}", id, e);
                }
            }
        });
    }

    // --------------------------------------------------------------------------------------------
    // Queries
    // --------------------------------------------------------------------------------------------
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<TaskCommentDto> getCommentsByTaskId(Long taskId) {
        if (taskId == null) return List.of();

        List<TaskComment> rows = commentRepo.findByTaskIdOrderByTimestampAsc(taskId);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(TaskComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return rows.stream().map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<TaskCommentDto> getCommentsSince(Long taskId, Instant since) {
        if (taskId == null || since == null) return List.of();

        List<TaskComment> rows = commentRepo.findByTaskIdAndUpdatedAtAfterOrderByUpdatedAtAsc(taskId, since);
        if (rows.isEmpty()) return List.of();

        Set<String> emails = rows.stream()
                .map(TaskComment::getAuthor).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, UserInfo> userByEmail = userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))
                .stream().collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity()));

        return rows.stream().map(c -> toDto(c, userByEmail)).collect(Collectors.toList());
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Optional<TaskCommentDto> getCommentById(Long id) {
        if (id == null) return Optional.empty();
        return commentRepo.findById(id).map(c -> {
            TaskCommentDto d = toDto(c);
            enrichAuthor(d);
            return d;
        });
    }

    /**
     * Per-task comment count for a list of task ids. Used by
     * {@code TaskService.withEngagement} to fold {@code commentsCount}
     * onto every {@code TaskDto} in one query rather than N. Tasks with no
     * comments are simply absent from the returned map; callers default to
     * 0 for missing keys.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public Map<Long, Integer> loadCountsByTaskIds(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return Map.of();
        List<Object[]> rows = commentRepo.countByTaskIdIn(taskIds);
        Map<Long, Integer> out = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            if (row == null || row.length < 2) continue;
            Long taskId = (Long) row[0];
            Long count = (Long) row[1];
            if (taskId != null && count != null) {
                out.put(taskId, count.intValue());
            }
        }
        return out;
    }

    // --------------------------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------------------------
    private TaskCommentDto toDto(TaskComment c) {
        TaskCommentDto d = new TaskCommentDto();
        d.setId(c.getId());
        d.setTaskId(c.getTaskId());
        d.setAuthor(c.getAuthor());
        d.setContent(c.getContent());
        d.setTimestamp(c.getTimestamp());
        d.setUpdatedAt(c.getUpdatedAt());
        d.setEditedAt(c.getEditedAt());
        d.setEdited(c.getEditedAt() != null);
        return d;
    }

    private TaskCommentDto toDto(TaskComment c, Map<String, UserInfo> userByEmail) {
        TaskCommentDto d = toDto(c);
        if (c.getAuthor() != null) {
            UserInfo u = userByEmail.get(c.getAuthor());
            if (u != null) {
                d.setAuthorFirstName(u.getUserFirstName());
                d.setAuthorLastName(u.getUserLastName());
                d.setAuthorProfileImageURL(u.getProfileImageURL());
            }
        }
        return d;
    }

    private void enrichAuthor(TaskCommentDto d) {
        if (d == null || d.getAuthor() == null) return;
        userInfoRepo.findByUserEmail(d.getAuthor()).ifPresent(u -> {
            d.setAuthorFirstName(u.getUserFirstName());
            d.setAuthorLastName(u.getUserLastName());
            d.setAuthorProfileImageURL(u.getProfileImageURL());
        });
    }

    /**
     * Notify the task author when someone else comments on their post.
     * Mirrors the post-comment notification but routes to the community
     * task detail page rather than the group post page.
     */
    private void notifyTaskAuthorOnNewComment(TaskComment savedComment, TaskCommentDto enrichedDto) {
        Long taskId = savedComment.getTaskId();
        if (taskId == null) return;

        Optional<Task> taskOpt = taskRepo.findById(taskId);
        if (taskOpt.isEmpty()) return;

        Task task = taskOpt.get();
        String taskAuthorEmail = task.getRequesterEmail();
        if (taskAuthorEmail == null) return;

        // Don't notify the author about their own comment.
        if (savedComment.getAuthor() != null &&
                savedComment.getAuthor().equalsIgnoreCase(taskAuthorEmail)) {
            return;
        }

        Optional<UserInfo> ownerOpt = userInfoRepo.findByUserEmail(taskAuthorEmail);
        if (ownerOpt.isEmpty()) return;

        UserInfo owner = ownerOpt.get();

        String commenterName = enrichedDto.getAuthorFirstName() != null
                ? enrichedDto.getAuthorFirstName()
                : "Someone";

        // Title leans on the task title when the kind has one (ask, marketplace,
        // etc.); for body-only kinds (post, tip) where title is null, we just
        // say "your post" so the surface stays generic.
        String title = (task.getTitle() != null && !task.getTitle().isBlank())
                ? "New comment on \"" + snippet(task.getTitle(), 60) + "\""
                : "New comment on your post";

        String body = commenterName + " commented: " + snippet(enrichedDto.getContent(), 80);
        String iconUrl = enrichedDto.getAuthorProfileImageURL();
        String targetUrl = "/community/tasks/" + task.getId();

        notificationService.deliverPresenceAware(
                owner.getUserEmail(),
                title,
                body,
                commenterName,
                iconUrl,
                "comment_on_task",
                String.valueOf(task.getId()),
                targetUrl,
                null,
                owner.getFcmtoken()
        );
    }

    private String snippet(String content, int maxLen) {
        if (content == null) return "";
        String trimmed = content.trim();
        if (trimmed.length() <= maxLen) return trimmed;
        return trimmed.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
