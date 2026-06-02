package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GroupPostReceiptFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GroupPostThreadPresenceService {

    private static final String TOPIC_PREFIX = "/topic/group-posts/";

    private record Subscription(String groupId, String email) {}

    private final GroupRepo groupRepo;
    private final WebSocketMessageSender ws;
    private final Map<String, Subscription> bySubscription = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> openByGroup = new ConcurrentHashMap<>();

    public GroupPostThreadPresenceService(GroupRepo groupRepo, WebSocketMessageSender ws) {
        this.groupRepo = groupRepo;
        this.ws = ws;
    }

    @Transactional(readOnly = true)
    public void addSubscription(
            String sessionId,
            String subscriptionId,
            String email,
            String destination
    ) {
        String groupId = parseGroupId(destination);
        String normalizedEmail = normalizeEmail(email);
        if (groupId == null || normalizedEmail == null) return;
        if (!isMember(groupId, normalizedEmail)) return;

        String key = key(sessionId, subscriptionId);
        if (key == null) return;
        Subscription previous = bySubscription.put(key, new Subscription(groupId, normalizedEmail));
        if (previous != null) decrement(previous.groupId(), previous.email());
        increment(groupId, normalizedEmail);

        GroupPostReceiptFrame frame =
                GroupPostReceiptFrame.delivered(groupId, normalizedEmail, Instant.now());
        registerAfterCommit(groupId, frame);
    }

    @Transactional(readOnly = true)
    public void removeSubscription(String sessionId, String subscriptionId) {
        String key = key(sessionId, subscriptionId);
        if (key == null) return;
        Subscription removed = bySubscription.remove(key);
        if (removed == null) return;
        decrement(removed.groupId(), removed.email());

        GroupPostReceiptFrame frame =
                GroupPostReceiptFrame.closed(removed.groupId(), removed.email(), Instant.now());
        registerAfterCommit(removed.groupId(), frame);
    }

    public int openRecipientCount(String groupId, String authorEmail) {
        if (groupId == null || groupId.isBlank()) return 0;
        String author = normalizeEmail(authorEmail);
        Map<String, Integer> rows = openByGroup.get(groupId);
        if (rows == null || rows.isEmpty()) return 0;
        return (int) rows.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                .filter(e -> author == null || !e.getKey().equalsIgnoreCase(author))
                .count();
    }

    private void increment(String groupId, String email) {
        openByGroup
                .computeIfAbsent(groupId, ignored -> new ConcurrentHashMap<>())
                .merge(email, 1, Integer::sum);
    }

    private void decrement(String groupId, String email) {
        Map<String, Integer> rows = openByGroup.get(groupId);
        if (rows == null) return;
        rows.compute(email, (ignored, count) -> {
            int next = count == null ? 0 : count - 1;
            return next <= 0 ? null : next;
        });
        if (rows.isEmpty()) openByGroup.remove(groupId);
    }

    private boolean isMember(String groupId, String email) {
        return groupRepo.findByGroupId(groupId)
                .map(Group::getMemberEmails)
                .orElseGet(java.util.List::of)
                .stream()
                .filter(Objects::nonNull)
                .anyMatch(e -> e.equalsIgnoreCase(email));
    }

    private void registerAfterCommit(String groupId, GroupPostReceiptFrame frame) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                ws.sendGroupPostReceipt(groupId, frame);
            }
        });
    }

    private static String parseGroupId(String destination) {
        if (destination == null || !destination.startsWith(TOPIC_PREFIX)) return null;
        String tail = destination.substring(TOPIC_PREFIX.length());
        if (tail.isBlank() || tail.contains("/")) return null;
        return tail;
    }

    private static String key(String sessionId, String subscriptionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (subscriptionId == null || subscriptionId.isBlank()) return null;
        return sessionId + ":" + subscriptionId;
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
