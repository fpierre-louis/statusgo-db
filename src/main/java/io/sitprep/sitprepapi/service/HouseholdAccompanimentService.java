package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdAccompaniment;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdAccompanimentRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Manages "with me" claims inside a household. Each row is one supervisor
 * accompanying one other person; the unique constraint on the accompanied
 * tuple enforces the "you can only be with one supervisor at a time" rule
 * — claiming someone who's already with X moves them to the new supervisor.
 *
 * <p>Frontend mirror lives at
 * {@code Status Now/src/me/household/householdAccompaniments.js}. The DTO
 * shape here matches that file's local-cache shape one-to-one so the
 * call-site swap from cache to API is mechanical.</p>
 */
@Service
public class HouseholdAccompanimentService {

    private final HouseholdAccompanimentRepo repo;
    private final WebSocketMessageSender ws;
    private final HouseholdEventService events;
    private final GroupRepo groupRepo;

    public HouseholdAccompanimentService(HouseholdAccompanimentRepo repo,
                                         WebSocketMessageSender ws,
                                         HouseholdEventService events,
                                         GroupRepo groupRepo) {
        this.repo = repo;
        this.ws = ws;
        this.events = events;
        this.groupRepo = groupRepo;
    }

    public List<HouseholdAccompanimentDto> list(String householdId) {
        if (householdId == null || householdId.isBlank()) return List.of();
        List<HouseholdAccompaniment> rows = repo.findByHouseholdId(householdId);
        if (rows.isEmpty()) return List.of();
        CheckInLine line = lineFor(householdId);
        return rows.stream().map(a -> toDto(a, line)).toList();
    }

    /**
     * Claim or move an accompaniment. The unique (household, accompaniedKind,
     * accompaniedId) constraint means we either insert a new row or update
     * the existing one's supervisor. Manual members auto-confirm; user
     * targets are pending unless {@code crisisOverride} is set.
     */
    @Transactional
    public HouseholdAccompanimentDto claim(String householdId,
                                           String actorEmail,
                                           Ref supervisor,
                                           Ref accompanied,
                                           boolean crisisOverride) {
        validateRefs(supervisor, accompanied);
        if (Objects.equals(supervisor.normKey(), accompanied.normKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "supervisor and accompanied must differ");
        }

        HouseholdAccompaniment row = repo
                .findByHouseholdIdAndAccompaniedKindAndAccompaniedId(
                        householdId, accompanied.kind(), accompanied.normId())
                .orElseGet(HouseholdAccompaniment::new);

        boolean isInsert = row.getId() == null;
        if (isInsert) {
            row.setHouseholdId(householdId);
            row.setAccompaniedKind(accompanied.kind());
            row.setAccompaniedId(accompanied.normId());
        }
        row.setSupervisorKind(supervisor.kind());
        row.setSupervisorId(supervisor.normId());
        // Manual members are always confirmed; users default to pending
        // unless caller asserts a crisis override.
        boolean pending = "user".equals(accompanied.kind()) && !crisisOverride;
        row.setPending(pending);
        // A claim is "with me NOW": re-claiming someone already on the row
        // (same supervisor or a new one) restarts it. Without this a re-claim
        // during a check-in kept the pre-check-in `since` and stayed stale.
        row.setSince(Instant.now());

        HouseholdAccompaniment saved = repo.save(row);
        HouseholdAccompanimentDto dto = toDto(saved, lineFor(householdId));
        broadcastAfterCommit(householdId, dto);
        events.recordWithClaim(
                householdId, actorEmail,
                "user".equals(accompanied.kind()) ? accompanied.email() : null);
        return dto;
    }

    @Transactional
    public HouseholdAccompanimentDto confirm(String householdId,
                                             String accompaniedKind,
                                             String accompaniedId) {
        HouseholdAccompaniment row = repo
                .findByHouseholdIdAndAccompaniedKindAndAccompaniedId(
                        householdId, accompaniedKind, normalizeId(accompaniedKind, accompaniedId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "accompaniment not found"));
        if (!row.isPending()) return toDto(row);
        row.setPending(false);
        HouseholdAccompanimentDto dto = toDto(repo.save(row), lineFor(householdId));
        broadcastAfterCommit(householdId, dto);
        return dto;
    }

    @Transactional
    public void release(String householdId,
                        String actorEmail,
                        String accompaniedKind,
                        String accompaniedId) {
        String id = normalizeId(accompaniedKind, accompaniedId);
        int deleted = repo.deleteByTarget(householdId, accompaniedKind, id);
        if (deleted > 0) {
            broadcastDeletionAfterCommit(householdId, accompaniedKind, id);
            events.recordWithRelease(householdId, actorEmail,
                    "user".equals(accompaniedKind) ? id : null);
        }
    }

    /**
     * Cascade hook used by {@link HouseholdManualMemberService} when a
     * manual member is removed — drop any accompaniment that references
     * them on either side.
     */
    @Transactional
    public void cascadeManualMemberRemoval(String householdId, String manualMemberId) {
        if (householdId == null || manualMemberId == null) return;
        repo.deleteByManualMemberId(householdId, manualMemberId);
        broadcastListAfterCommit(householdId);
    }

    // ------------------------------------------------------------------

    private void broadcastAfterCommit(String householdId, HouseholdAccompanimentDto dto) {
        registerAfterCommit(() -> ws.sendHouseholdAccompanimentUpdate(householdId, dto));
    }

    private void broadcastDeletionAfterCommit(String householdId, String kind, String id) {
        registerAfterCommit(() -> ws.sendHouseholdAccompanimentRelease(
                householdId, Map.of("accompaniedKind", kind, "accompaniedId", id)));
    }

    private void broadcastListAfterCommit(String householdId) {
        registerAfterCommit(() -> ws.sendHouseholdAccompanimentReplaceAll(
                householdId, list(householdId)));
    }

    private static void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    private static void validateRefs(Ref supervisor, Ref accompanied) {
        if (supervisor == null || accompanied == null
                || supervisor.kind() == null || accompanied.kind() == null
                || supervisor.id() == null || accompanied.id() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "supervisorRef and accompaniedRef are required");
        }
        if (!isValidKind(supervisor.kind()) || !isValidKind(accompanied.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ref.kind must be 'user' or 'manual'");
        }
    }

    private static boolean isValidKind(String kind) {
        return "user".equals(kind) || "manual".equals(kind);
    }

    private static String normalizeId(String kind, String id) {
        if (id == null) return null;
        return "user".equals(kind) ? id.trim().toLowerCase(Locale.ROOT) : id.trim();
    }

    /** Whether a check-in is running in this household, and where it starts. */
    private record CheckInLine(boolean active, Instant anchor) {}

    private CheckInLine lineFor(String householdId) {
        Group g = groupRepo == null || householdId == null ? null
                : groupRepo.findById(householdId).orElse(null);
        if (g == null) return new CheckInLine(false, null);
        return new CheckInLine("Active".equalsIgnoreCase(g.getAlert()), StatusRollups.anchorFor(g));
    }

    private HouseholdAccompanimentDto toDto(HouseholdAccompaniment a, CheckInLine line) {
        return new HouseholdAccompanimentDto(
                a.getId(),
                refDto(a.getSupervisorKind(), a.getSupervisorId()),
                refDto(a.getAccompaniedKind(), a.getAccompaniedId()),
                a.getSince(),
                a.isPending(),
                !StatusRollups.accompanimentCounts(a.getSince(), line.active(), line.anchor())
        );
    }

    private static HouseholdAccompanimentDto.Ref refDto(String kind, String id) {
        if (id == null) return new HouseholdAccompanimentDto.Ref(kind, null, null);
        return "user".equals(kind)
                ? new HouseholdAccompanimentDto.Ref(kind, id, id)
                : new HouseholdAccompanimentDto.Ref(kind, id, null);
    }

    /** Inbound ref tuple used by the resource. Mirrors the FE's userRef/manualRef shape. */
    public record Ref(String kind, String id, String email) {
        public String normId() {
            return HouseholdAccompanimentService.normalizeId(kind, id != null ? id : email);
        }
        public String normKey() {
            return kind + ":" + normId();
        }
    }
}
