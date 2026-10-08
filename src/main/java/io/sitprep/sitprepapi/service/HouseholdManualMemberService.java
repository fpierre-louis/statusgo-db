package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.CheckIn;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto.ManualStatus;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for manual household members (children/elders without app
 * accounts). Frontend mirror lives in
 * {@code Status Now/src/me/household/householdAccompaniments.js}.
 *
 * <p>Removing a manual member cascades to delete any accompaniment that
 * referenced them on either side, via {@link HouseholdAccompanimentService}.</p>
 *
 * <p><b>The plan's counts move here, not in the client</b> (V100). A create
 * fills a placeholder in the person's band or raises it; a delete lowers it; a
 * band change moves it — all through {@link HouseholdCompositionService}, in
 * the same transaction as the row. The FE must not also "bump" the demographic.</p>
 *
 * <p><b>Status set FOR them</b> (V103, household drawer gameplan §3.4). Owners
 * and admins only — the same rule as "Answer for" on an account
 * ({@link MemberActionPolicy#canSetOthersStatus}) and deliberately stricter
 * than the membership-only CRUD above. No push and no notification: the write
 * goes out on the manual-member socket and as a {@code status-set-for}
 * timeline row in this household only.</p>
 */
@Service
public class HouseholdManualMemberService {

    private final HouseholdManualMemberRepo repo;
    private final HouseholdAccompanimentService accompanimentService;
    private final WebSocketMessageSender ws;
    private final HouseholdCompositionService composition;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final HouseholdEventService events;

    public HouseholdManualMemberService(HouseholdManualMemberRepo repo,
                                        HouseholdAccompanimentService accompanimentService,
                                        WebSocketMessageSender ws,
                                        HouseholdCompositionService composition,
                                        GroupRepo groupRepo,
                                        UserInfoRepo userInfoRepo,
                                        HouseholdEventService events) {
        this.repo = repo;
        this.accompanimentService = accompanimentService;
        this.ws = ws;
        this.composition = composition;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.events = events;
    }

    public List<HouseholdManualMemberDto> list(String householdId) {
        if (householdId == null || householdId.isBlank()) return List.of();
        StatusContext ctx = statusContext(householdId);
        return repo.findByHouseholdIdOrderByCreatedAtAsc(householdId).stream()
                .map(m -> toDto(m, ctx))
                .toList();
    }

    @Transactional
    public HouseholdManualMemberDto add(String householdId, UpsertRequest body) {
        return add(householdId, body, null);
    }

    /**
     * Create, then fill-or-raise the person's band. {@code band} (when sent)
     * outranks {@code isAdult}/{@code age}: it is present when the person was
     * named from a placeholder row, and re-deriving from an age nobody typed
     * would count a named teenager as a kid.
     */
    @Transactional
    public HouseholdManualMemberDto add(String householdId, UpsertRequest body, String actorEmail) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        }

        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(body.id() == null || body.id().isBlank() ? UUID.randomUUID().toString() : body.id());
        m.setHouseholdId(householdId);
        m.setName(body.name().trim());
        m.setRelationship(body.relationship());
        m.setAge(body.age());
        HouseholdBand band = HouseholdBand.parse(body.band());
        // Explicit minor default when the caller omits — matches the locked
        // privacy decision in docs/MAP_SURFACES_REDESIGN_PLAN.md Phase 4. A
        // person named into the ADULT band is an adult unless told otherwise.
        m.setIsAdult(body.isAdult() != null ? body.isAdult() : band == HouseholdBand.ADULT);
        m.setBand(band != null ? band : HouseholdBand.derive(m.getIsAdult(), m.getAge()));
        m.setPhotoUrl(body.photoUrl());
        HouseholdManualMember saved = repo.save(m);
        composition.raiseToNamed(householdId, true, actorEmail);
        HouseholdManualMemberDto dto = toDto(saved, statusContext(householdId));
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberUpdate(householdId, dto));
        return dto;
    }

    @Transactional
    public HouseholdManualMemberDto update(String householdId, String id, UpsertRequest body) {
        HouseholdManualMember m = loadOr404(householdId, id);
        HouseholdBand before = m.effectiveBand();
        if (body.name() != null && !body.name().isBlank()) m.setName(body.name().trim());
        if (body.relationship() != null) m.setRelationship(body.relationship());
        if (body.age() != null) m.setAge(body.age());
        // Explicit-only update — null body.isAdult leaves the stored value
        // untouched so partial PATCHes don't accidentally flip an admin's
        // adult opt-in back to minor.
        if (body.isAdult() != null) m.setIsAdult(body.isAdult());
        if (body.photoUrl() != null) m.setPhotoUrl(body.photoUrl());
        // Band: an explicit band wins; otherwise an age / adult edit re-derives
        // it; a rename leaves it alone.
        HouseholdBand explicit = HouseholdBand.parse(body.band());
        if (explicit != null) {
            m.setBand(explicit);
        } else if (body.age() != null || body.isAdult() != null) {
            m.setBand(HouseholdBand.derive(m.getIsAdult(), m.getAge()));
        }
        HouseholdManualMember saved = repo.save(m);
        HouseholdBand after = saved.effectiveBand();
        if (after != before) {
            // The count moves with the person: the old band loses the slot
            // they held, the new band fills a placeholder or grows.
            repo.flush();
            composition.lowerBand(householdId, before);
            composition.raiseToNamed(householdId, true, null);
        }
        HouseholdManualMemberDto dto = toDto(saved, statusContext(householdId));
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberUpdate(householdId, dto));
        return dto;
    }

    @Transactional
    public void remove(String householdId, String id) {
        HouseholdManualMember m = loadOr404(householdId, id);
        HouseholdBand band = m.effectiveBand();
        repo.delete(m);
        repo.flush();
        // The household removed a person it had named: the plan stops counting them.
        composition.lowerBand(householdId, band);
        accompanimentService.cascadeManualMemberRemoval(householdId, id);
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberDeletion(householdId, id));
    }

    /**
     * An owner or admin answers for a manual member: SAFE | HELP | INJURED.
     * 403 for anyone else in the household, 404 when the member is not in it,
     * 400 for any other value.
     */
    @Transactional
    public HouseholdManualMemberDto setStatus(String householdId, String id, String status, String actorEmail) {
        Group g = requireCanSetStatus(householdId, actorEmail);
        HouseholdManualMember m = loadOr404(householdId, id);
        String value;
        try {
            value = UserInfoService.normalizeSelfStatus(status);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String actor = actorEmail.trim().toLowerCase(Locale.ROOT);
        m.setStatus(value);
        m.setStatusUpdatedAt(Instant.now());
        m.setStatusSetByEmail(actor);
        HouseholdManualMember saved = repo.save(m);
        events.recordStatusSetForManualMember(householdId, actor, saved.getId(), saved.getName(), value);
        HouseholdManualMemberDto dto = toDto(saved, statusContext(g));
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberUpdate(householdId, dto));
        return dto;
    }

    /** Clear a manual member's status. Same gate as {@link #setStatus}. */
    @Transactional
    public HouseholdManualMemberDto clearStatus(String householdId, String id, String actorEmail) {
        Group g = requireCanSetStatus(householdId, actorEmail);
        HouseholdManualMember m = loadOr404(householdId, id);
        boolean had = m.getStatus() != null;
        m.setStatus(null);
        m.setStatusUpdatedAt(null);
        m.setStatusSetByEmail(null);
        HouseholdManualMember saved = repo.save(m);
        if (had) {
            events.recordStatusSetForManualMember(householdId,
                    actorEmail.trim().toLowerCase(Locale.ROOT), saved.getId(), saved.getName(),
                    HouseholdEventService.MANUAL_STATUS_CLEARED);
        }
        HouseholdManualMemberDto dto = toDto(saved, statusContext(g));
        broadcastAfterCommit(() -> ws.sendHouseholdManualMemberUpdate(householdId, dto));
        return dto;
    }

    // ------------------------------------------------------------------

    private Group requireCanSetStatus(String householdId, String actorEmail) {
        Group g = groupRepo.findByGroupId(householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (actorEmail == null || !MemberActionPolicy.canSetOthersStatus(g, actorEmail)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the household's owner or admins can answer for someone");
        }
        return g;
    }

    /**
     * What a status needs to know about its household: whether a check-in is
     * running and its window. Read once per list, not per member.
     */
    private record StatusContext(boolean alertActive, Instant startedAt, Instant expiresAt,
                                 Instant now, Map<String, String> nameByEmail) {}

    private StatusContext statusContext(String householdId) {
        return statusContext(householdId == null ? null : groupRepo.findByGroupId(householdId).orElse(null));
    }

    private StatusContext statusContext(Group g) {
        boolean active = g != null && "Active".equalsIgnoreCase(g.getAlert());
        // The rollups' anchor (alertActivatedAt, else updatedAt for a check-in
        // opened before that column), so a row never shows a status the
        // counts call no response.
        Instant startedAt = active ? StatusRollups.anchorFor(g) : null;
        // The same end GroupViewService gives an account's row: V90's column,
        // else start + the configured window (pre-V90 check-ins carry no end).
        Instant expires = !active ? null
                : g.getAlertExpiresAt() != null ? g.getAlertExpiresAt()
                : g.getAlertActivatedAt() == null ? null
                : g.getAlertActivatedAt().plus(Duration.ofHours(checkInHours));
        return new StatusContext(active, startedAt, expires, Instant.now(), new HashMap<>());
    }

    @org.springframework.beans.factory.annotation.Value("${app.groupAlert.decayHours:48}")
    private int checkInHours = 48;

    private String nameOf(String email, StatusContext ctx) {
        if (email == null || email.isBlank()) return null;
        return ctx.nameByEmail().computeIfAbsent(email.toLowerCase(Locale.ROOT), e ->
                userInfoRepo.findByUserEmailIgnoreCase(e)
                        .map(UserInfo::getUserFirstName)
                        .filter(n -> n != null && !n.isBlank())
                        .map(String::trim)
                        .orElse(null));
    }

    /**
     * The status the roster shows, or null when unset or lapsed — the same
     * {@link CheckInState} rule as an account's row, with no ask (a manual
     * member cannot be asked).
     */
    private ManualStatus manualStatus(HouseholdManualMember m, StatusContext ctx) {
        if (m.getStatus() == null) return null;
        CheckIn c = CheckInState.of(null, m.getStatus(), m.getStatusUpdatedAt(), null,
                ctx.alertActive(), ctx.startedAt(), ctx.expiresAt(), ctx.now());
        if (!CheckInState.showsStatus(c)) return null;
        return new ManualStatus(
                c.value(),
                UserInfoService.defaultStatusColor(c.value()),
                m.getStatusUpdatedAt(),
                nameOf(m.getStatusSetByEmail(), ctx),
                c.showUntil());
    }

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

    private HouseholdManualMemberDto toDto(HouseholdManualMember m, StatusContext ctx) {
        return new HouseholdManualMemberDto(
                m.getId(),
                m.getHouseholdId(),
                m.getName(),
                m.getRelationship(),
                m.getAge(),
                // Defensive — older rows without the column populated yet
                // (immediately after the migration) read as Boolean false.
                Boolean.TRUE.equals(m.getIsAdult()),
                m.effectiveBand().name(),
                m.getPhotoUrl(),
                m.getCreatedAt(),
                m.getUpdatedAt(),
                manualStatus(m, ctx)
        );
    }

    public record UpsertRequest(
            String id,
            String name,
            String relationship,
            Integer age,
            Boolean isAdult,
            String photoUrl,
            /** ADULT | TEEN | KID | INFANT (case-insensitive; also adult/teen/kid/child/infant). Optional. */
            String band
    ) {
        public UpsertRequest(String id, String name, String relationship, Integer age,
                             Boolean isAdult, String photoUrl) {
            this(id, name, relationship, age, isAdult, photoUrl, null);
        }
    }
}
