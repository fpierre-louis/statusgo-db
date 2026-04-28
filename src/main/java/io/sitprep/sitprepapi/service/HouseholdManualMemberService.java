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
            String photoUrl
    ) {}
}
