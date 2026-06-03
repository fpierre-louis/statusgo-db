package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for manual household members (children/elders without app
 * accounts). Frontend mirror lives in
 * {@code Status Now/src/me/household/householdAccompaniments.js}.
 *
 * <p>Removing a manual member cascades to delete any accompaniment that
 * referenced them on either side, via {@link HouseholdAccompanimentService}.</p>
 */
@Service
public class HouseholdManualMemberService {

    private final HouseholdManualMemberRepo repo;
    private final HouseholdAccompanimentService accompanimentService;
    private final WebSocketMessageSender ws;

    public HouseholdManualMemberService(HouseholdManualMemberRepo repo,
                                        HouseholdAccompanimentService accompanimentService,
                                        WebSocketMessageSender ws) {
        this.repo = repo;
        this.accompanimentService = accompanimentService;
        this.ws = ws;
    }

    public List<HouseholdManualMemberDto> list(String householdId) {
        if (householdId == null || householdId.isBlank()) return List.of();
        return repo.findByHouseholdIdOrderByCreatedAtAsc(householdId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public HouseholdManualMemberDto add(String householdId, UpsertRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }

        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(body.id() == null || body.id().isBlank() ? UUID.randomUUID().toString() : body.id());
        m.setHouseholdId(householdId);
        m.setName(body.name().trim());
        m.setRelationship(body.relationship());
        m.setAge(body.age());
        // Explicit minor default when the caller omits — matches the locked
        // privacy decision in docs/MAP_SURFACES_REDESIGN_PLAN.md Phase 4.
        m.setIsAdult(Boolean.TRUE.equals(body.isAdult()));
        m.setPhotoUrl(body.photoUrl());
        HouseholdManualMember saved = repo.save(m);
        HouseholdManualMemberDto dto = toDto(saved);
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberUpdate(householdId, dto));
        return dto;
    }

    @Transactional
    public HouseholdManualMemberDto update(String householdId, String id, UpsertRequest body) {
        HouseholdManualMember m = loadOr404(householdId, id);
        if (body.name() != null && !body.name().isBlank()) m.setName(body.name().trim());
        if (body.relationship() != null) m.setRelationship(body.relationship());
        if (body.age() != null) m.setAge(body.age());
        // Explicit-only update — null body.isAdult leaves the stored value
        // untouched so partial PATCHes don't accidentally flip an admin's
        // adult opt-in back to minor.
        if (body.isAdult() != null) m.setIsAdult(body.isAdult());
        if (body.photoUrl() != null) m.setPhotoUrl(body.photoUrl());
        HouseholdManualMember saved = repo.save(m);
        HouseholdManualMemberDto dto = toDto(saved);
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberUpdate(householdId, dto));
        return dto;
    }

    @Transactional
    public void remove(String householdId, String id) {
        HouseholdManualMember m = loadOr404(householdId, id);
        repo.delete(m);
        accompanimentService.cascadeManualMemberRemoval(householdId, id);
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberDeletion(householdId, id));
    }

    // ------------------------------------------------------------------

    private HouseholdManualMember loadOr404(String householdId, String id) {
        HouseholdManualMember m = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!householdId.equals(m.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return m;
    }

    private static void broadcastAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    private HouseholdManualMemberDto toDto(HouseholdManualMember m) {
        return new HouseholdManualMemberDto(
                m.getId(),
                m.getHouseholdId(),
                m.getName(),
                m.getRelationship(),
                m.getAge(),
                // Defensive — older rows without the column populated yet
                // (immediately after the migration) read as Boolean false.
                Boolean.TRUE.equals(m.getIsAdult()),
                m.getPhotoUrl(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }

    public record UpsertRequest(
            String id,
            String name,
            String relationship,
            Integer age,
            Boolean isAdult,
            String photoUrl
    ) {}
}
