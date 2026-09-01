package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupTypingFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Service
public class GroupTypingService {

    private static final long TYPING_TTL_SECONDS = 4;

    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final WebSocketMessageSender ws;

    public GroupTypingService(
            GroupRepo groupRepo,
            UserInfoRepo userInfoRepo,
            WebSocketMessageSender ws
    ) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.ws = ws;
    }

    @Transactional(readOnly = true)
    public void relay(String groupId, String email, boolean typing) {
        String safeGroupId = groupId == null ? "" : groupId.trim();
        String actor = normalizeEmail(email);
        if (safeGroupId.isBlank() || actor == null) return;

        boolean member = groupRepo.findByGroupId(safeGroupId)
                .map(Group::getMemberEmails)
                .orElseGet(java.util.List::of)
                .stream()
                .filter(Objects::nonNull)
                .anyMatch(e -> e.equalsIgnoreCase(actor));
        if (!member) return;

        Instant now = Instant.now();
        Instant expiresAt = typing ? now.plusSeconds(TYPING_TTL_SECONDS) : now;
        GroupTypingFrame frame = new GroupTypingFrame(
                "typing",
                safeGroupId,
                actor,
                displayName(actor),
                typing,
                expiresAt,
                now
        );

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                ws.sendGroupTyping(safeGroupId, frame);
            }
        });
    }

    /**
     * The typing member's name, or {@code null} when the record does not hold one.
     *
     * <p><b>Null, not the email address.</b> This used to {@code .orElse(email)},
     * and the frame is rendered as "{name} is typing…" to everyone in the group —
     * so an address went on screen for any member who has not set a name. That is
     * the same defect as rendering an address where a name goes in a comment
     * thread, and {@code canViewEmail} on the group surfaces is explicit that a
     * plain member is not entitled to a peer's address.</p>
     *
     * <p>The client cannot fix this: it would have to guess whether a
     * {@code displayName} that looks like an address is a real name or this
     * fallback having fired. The server owns the resolution, so absence is
     * shipped as absence and the client says "Someone".</p>
     */
    private String displayName(String email) {
        return userInfoRepo.findByUserEmail(email)
                .map(u -> {
                    String first = trim(u.getUserFirstName());
                    String last = trim(u.getUserLastName());
                    String full = (first + " " + last).trim();
                    return full.isBlank() ? first : full;
                })
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
