package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.ChatMessage;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.ChatMessageDtos.*;
import io.sitprep.sitprepapi.repo.ChatMessageRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Chat message lifecycle + delivery:
 * - Create / edit produces a {@link ChatMessageDto} enriched with author
 *   profile and broadcasts to {@code /topic/chat/{groupId}} after commit
 *   (mirrors the {@link PostService} / {@link CommentService} pattern).
 * - Reads are cursor-paginated (newest-first) so the client never pulls the
 *   full history; the {@code since} endpoint is for reconnect backfill.
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    /** Absolute cap on a single page regardless of client request. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final ChatMessageRepo repo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;

    public ChatMessageService(ChatMessageRepo repo, UserInfoRepo userInfoRepo,
                              WebSocketMessageSender ws) {
        this.repo = repo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    @Transactional
    public ChatMessageDto create(String groupId, CreateMessageRequest req) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        String author = Optional.ofNullable(req.authorEmail())
                .map(String::trim).map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("authorEmail is required"));

        ChatMessage msg = new ChatMessage();
        msg.setGroupId(groupId);
        msg.setAuthorEmail(author);
        msg.setContent(req.content());

        ChatMessage saved = repo.save(msg);
        ChatMessageDto dto = enrich(saved, req.tempId());

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendChatMessage(groupId, dto);
                } catch (Exception e) {
                    log.error("Chat WS broadcast failed for groupId={} id={}",
                            groupId, saved.getId(), e);
                }
            }
        });

        return dto;
    }

    // ---------------------------------------------------------------------
    // Edit
    // ---------------------------------------------------------------------

    @Transactional
    public ChatMessageDto edit(Long id, UpdateMessageRequest req) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }

        ChatMessage existing = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Message not found: " + id));

        // Minimum ownership guard consistent with Post/Comment: if actor is
        // supplied and doesn't match, reject. When auth is re-enabled this
        // becomes a hard server-side check.
        if (req.authorEmail() != null && !req.authorEmail().isBlank()
                && !req.authorEmail().trim().equalsIgnoreCase(existing.getAuthorEmail())) {
            throw new SecurityException("Not allowed to edit this message.");
        }

        existing.setContent(req.content());
        existing.setEditedAt(Instant.now());

        ChatMessage saved = repo.save(existing);
        ChatMessageDto dto = enrich(saved, null);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Same topic for create + edit; clients upsert by id.
                    ws.sendChatMessage(saved.getGroupId(), dto);
                } catch (Exception e) {
                    log.error("Chat WS broadcast failed for edit id={}", saved.getId(), e);
                }
            }
        });

        return dto;
    }

    // ---------------------------------------------------------------------
    // Delete (hard)
    // ---------------------------------------------------------------------

    @Transactional
    public void delete(Long id, String actorEmail) {
        if (id == null) return;
        ChatMessage msg = repo.findById(id).orElse(null);
        if (msg == null) return;

        if (actorEmail != null && !actorEmail.isBlank()
                && !actorEmail.trim().equalsIgnoreCase(msg.getAuthorEmail())) {
            throw new SecurityException("Not allowed to delete this message.");
        }

        String groupId = msg.getGroupId();
        repo.deleteById(id);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    ws.sendChatMessageDeletion(groupId, id);
                } catch (Exception e) {
                    log.error("Chat WS broadcast failed for delete id={}", id, e);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    /**
     * Paginated newest-first fetch. If {@code before} is null, returns the
     * most recent page. Use {@link MessagesPage#nextBefore()} as the next
     * {@code before} cursor to walk backwards through history.
     */
    @Transactional(readOnly = true)
    public MessagesPage getPage(String groupId, Instant before, Integer limit) {
        int size = clampLimit(limit);
        PageRequest pr = PageRequest.of(0, size);

        List<ChatMessage> rows = before == null
                ? repo.findByGroupIdOrderByCreatedAtDescIdDesc(groupId, pr)
                : repo.findByGroupIdAndCreatedAtBeforeOrderByCreatedAtDescIdDesc(groupId, before, pr);

        if (rows.isEmpty()) return new MessagesPage(List.of(), null);

        Map<String, UserInfo> authorByEmail = loadAuthors(rows, ChatMessage::getAuthorEmail);
        List<ChatMessageDto> dtos = rows.stream()
                .map(m -> toDto(m, authorByEmail.get(m.getAuthorEmail()), null))
                .collect(Collectors.toList());

        // There's likely more if the page was fully populated. Use the oldest
        // row's createdAt as the cursor for the next call. We don't try to
        // PROVE there's more (would need an extra query); the client simply
        // walks until it gets a shorter-than-requested page.
        Instant nextBefore = rows.size() >= size
                ? rows.get(rows.size() - 1).getCreatedAt()
                : null;

        return new MessagesPage(dtos, nextBefore);
    }

    /** Reconnect backfill: everything touched after {@code since}, oldest-first. */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> getSince(String groupId, Instant since) {
        if (since == null) return List.of();
        List<ChatMessage> rows = repo.findByGroupIdAndUpdatedAtAfterOrderByUpdatedAtAsc(groupId, since);
        if (rows.isEmpty()) return List.of();

        Map<String, UserInfo> authorByEmail = loadAuthors(rows, ChatMessage::getAuthorEmail);
        return rows.stream()
                .map(m -> toDto(m, authorByEmail.get(m.getAuthorEmail()), null))
                .toList();
    }

    // ---------------------------------------------------------------------
    // Mapping / helpers
    // ---------------------------------------------------------------------

    private ChatMessageDto enrich(ChatMessage msg, String tempId) {
        UserInfo author = msg.getAuthorEmail() == null ? null
                : userInfoRepo.findByUserEmail(msg.getAuthorEmail()).orElse(null);
        return toDto(msg, author, tempId);
    }

    private ChatMessageDto toDto(ChatMessage msg, UserInfo author, String tempId) {
        String firstName = author == null ? null : author.getUserFirstName();
        String lastName = author == null ? null : author.getUserLastName();
        String avatar = author == null ? null : author.getProfileImageURL();
        return new ChatMessageDto(
                msg.getId(),
                msg.getGroupId(),
                msg.getAuthorEmail(),
                firstName,
                lastName,
                avatar,
                msg.getContent(),
                msg.getCreatedAt(),
                msg.getEditedAt(),
                msg.getUpdatedAt(),
                tempId
        );
    }

    private Map<String, UserInfo> loadAuthors(List<ChatMessage> rows,
                                              Function<ChatMessage, String> emailOf) {
        Set<String> emails = rows.stream()
                .map(emailOf).filter(Objects::nonNull).collect(Collectors.toSet());
        if (emails.isEmpty()) return Map.of();
        return userInfoRepo.findByUserEmailIn(new ArrayList<>(emails)).stream()
                .collect(Collectors.toMap(UserInfo::getUserEmail, Function.identity(),
                        (a, b) -> a));
    }

    private int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
