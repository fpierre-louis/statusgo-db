package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.MemberPresenceFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class WebSocketPresenceBroadcastService {

    private final WebSocketPresenceService presenceService;
    private final GroupRepo groupRepo;
    private final WebSocketMessageSender ws;

    public WebSocketPresenceBroadcastService(
            WebSocketPresenceService presenceService,
            GroupRepo groupRepo,
            WebSocketMessageSender ws
    ) {
        this.presenceService = presenceService;
        this.groupRepo = groupRepo;
        this.ws = ws;
    }

    @Transactional
    public void addSession(String sessionId, String email) {
        WebSocketPresenceService.PresenceChange change =
                presenceService.addSession(sessionId, email);
        broadcastAfterCommit(change);
    }

    @Transactional
    public void removeSession(String sessionId) {
        WebSocketPresenceService.PresenceChange change =
                presenceService.removeSession(sessionId);
        broadcastAfterCommit(change);
    }

    private void broadcastAfterCommit(WebSocketPresenceService.PresenceChange change) {
        if (change == null || change.email() == null || change.email().isBlank()) return;

        final String email = change.email().trim().toLowerCase(Locale.ROOT);
        final MemberPresenceFrame frame = new MemberPresenceFrame(
                email,
                change.onlineCount() > 0,
                change.onlineCount(),
                Instant.now()
        );
        final List<String> householdIds = groupRepo.findByMemberEmail(email).stream()
                .filter(g -> HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType()))
                .map(Group::getGroupId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (householdIds.isEmpty()) return;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (String householdId : householdIds) {
                    ws.sendHouseholdPresence(householdId, frame);
                }
            }
        });
    }
}
