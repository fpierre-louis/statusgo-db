package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.MemberPresenceFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class HouseholdPresenceService {

    private final GroupRepo groupRepo;
    private final WebSocketPresenceService presenceService;

    public HouseholdPresenceService(
            GroupRepo groupRepo,
            WebSocketPresenceService presenceService
    ) {
        this.groupRepo = groupRepo;
        this.presenceService = presenceService;
    }

    @Transactional(readOnly = true)
    public List<MemberPresenceFrame> snapshot(String householdId) {
        return groupRepo.findByGroupId(householdId)
                .filter(g -> HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType()))
                .map(Group::getMemberEmails)
                .orElse(List.of())
                .stream()
                .map(HouseholdPresenceService::normalizeEmail)
                .filter(email -> !email.isBlank())
                .distinct()
                .map(email -> new MemberPresenceFrame(
                        email,
                        presenceService.isUserOnline(email),
                        presenceService.getOnlineCount(email),
                        Instant.now()
                ))
                .toList();
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
