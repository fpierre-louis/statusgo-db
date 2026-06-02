package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupReadState;
import io.sitprep.sitprepapi.dto.GroupPostReceiptFrame;
import io.sitprep.sitprepapi.repo.GroupReadStateRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Locale;

@Service
public class GroupPostReadReceiptService {

    private final GroupReadStateRepo groupReadStateRepo;
    private final WebSocketMessageSender ws;

    public GroupPostReadReceiptService(
            GroupReadStateRepo groupReadStateRepo,
            WebSocketMessageSender ws
    ) {
        this.groupReadStateRepo = groupReadStateRepo;
        this.ws = ws;
    }

    @Transactional
    public void markGroupRead(String groupId, String email) {
        if (groupId == null || groupId.isBlank()) return;
        String reader = normalizeEmail(email);
        if (reader == null) return;

        Instant readAt = Instant.now();
        GroupReadState state = groupReadStateRepo
                .findByUserEmailIgnoreCaseAndGroupId(reader, groupId)
                .orElseGet(() -> {
                    GroupReadState ns = new GroupReadState();
                    ns.setUserEmail(reader);
                    ns.setGroupId(groupId);
                    return ns;
                });
        state.setLastReadAt(readAt);
        groupReadStateRepo.save(state);

        GroupPostReceiptFrame frame = GroupPostReceiptFrame.read(groupId, reader, readAt);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                ws.sendGroupPostReceipt(groupId, frame);
            }
        });
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
