package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Task;
import io.sitprep.sitprepapi.domain.Task.TaskStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.TaskDto;
import io.sitprep.sitprepapi.repo.TaskRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Task / request-for-help service. Three scopes (group / community-personal /
 * group-claimed-community) share one {@link Task} entity. Scope is implicit
 * from groupId / claimedByGroupId nullability — see entity Javadoc.
 *
 * <p>This service does the create + lifecycle transitions (claim, complete,
 * cancel, reopen) plus the queries each surface needs:</p>
 * <ul>
 *   <li>Group feed — by groupId</li>
 *   <li>Community feed — by lat/lng/radius (Haversine in Java; zip-bucket pre-filter via repo)</li>
 *   <li>My tasks — by requester or claimer email</li>
 * </ul>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** Mean Earth radius in km — matches CommunityDiscoverService. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * Authorized post kinds — see Task.kind Javadoc + the spec
     * {@code docs/MARKETPLACE_AND_FEED_CALM.md} "Feed: post types
     * beyond Asks". Lowercased. Adding a new kind is a one-line
     * change here; no schema change since the column is free-form
     * length 32.
     */
    private static final Set<String> AUTHORIZED_KINDS = Set.of(
            "ask", "offer", "tip", "recommendation",
            "lost-found", "alert-update", "blog-promo", "marketplace"
    );

    private final TaskRepo taskRepo;
    private final UserInfoRepo userInfoRepo;
    private final NominatimGeocodeService geocode;
    private final WebSocketMessageSender ws;
    private final AlertModeService alertModeService;

    public TaskService(TaskRepo taskRepo, UserInfoRepo userInfoRepo,
                       NominatimGeocodeService geocode,
                       WebSocketMessageSender ws,
                       AlertModeService alertModeService) {
        this.taskRepo = taskRepo;
        this.userInfoRepo = userInfoRepo;
        this.geocode = geocode;
        this.ws = ws;
        this.alertModeService = alertModeService;
    }

    /**
     * Apply mode-aware sponsored suppression per
     * {@code docs/SPONSORED_AND_ALERT_MODE.md} "Suppression rules in
     * detail":
     * <ul>
     *   <li>{@code calm} — show everything (organic + sponsored).</li>
     *   <li>{@code attention} / {@code alert} — drop sponsored UNLESS
     *       {@code crisisRelevant=true} (those still show, with FE-side
     *       "Verified service nearby" lane labeling).</li>
     *   <li>{@code crisis} — drop ALL sponsored regardless of
     *       crisisRelevant. Verified-publisher organic content gets
     *       the focus instead.</li>
     * </ul>
     *
     * <p>Run after the radius filter + distance sort but before the
     * cap-50 trim, so suppressed sponsored doesn't take a slot a real
     * organic ask could occupy.</p>
     */
    private List<TaskDto> applySponsoredSuppression(List<TaskDto> tasks, String mode) {
        if (mode == null || AlertModeService.CALM.equalsIgnoreCase(mode)) return tasks;
        boolean isCrisis = AlertModeService.CRISIS.equalsIgnoreCase(mode);
        List<TaskDto> out = new ArrayList<>(tasks.size());
        for (TaskDto t : tasks) {
            if (!t.sponsored()) {
                out.add(t);
                continue;
            }
            if (isCrisis) continue; // hide all sponsored
            if (t.crisisRelevant()) out.add(t); // keep crisis-relevant in attention/alert
            // else: drop sponsored, non-crisis-relevant
        }
        return out;
    }

    /**
     * Batch-fold author profile fields into a list of TaskDto. Honors
     * the codebase principle "backend shapes the data, frontend just
     * displays" — feed surfaces render the standard post anatomy
     * (avatar + name + 3-dot menu) without fanning out a separate
     * /userinfo/profiles/batch round trip per page-load.
     *
     * <p>One DB call total via {@code findByUserEmailIn}. Tasks whose
     * requesterEmail can't be resolved (deleted account, anon) flow
     * through unchanged with null author fields — the FE handles that
     * by falling back to email-as-name + initials.</p>
     */
    private List<TaskDto> withAuthors(List<TaskDto> dtos) {
        if (dtos == null || dtos.isEmpty()) return dtos;
        List<String> emails = dtos.stream()
                .map(TaskDto::requesterEmail)
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
        if (emails.isEmpty()) return dtos;
        Map<String, UserInfo> byEmail = userInfoRepo.findByUserEmailIn(emails).stream()
                .filter(u -> u.getUserEmail() != null)
                .collect(Collectors.toMap(
                        u -> u.getUserEmail().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a
                ));
        return dtos.stream()
                .map(d -> {
                    if (d.requesterEmail() == null) return d;
                    UserInfo u = byEmail.get(d.requesterEmail().toLowerCase(Locale.ROOT));
                    return (u == null) ? d : d.withAuthor(u);
                })
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // Create
    // ---------------------------------------------------------------------

    @Transactional
    public TaskDto create(Task incoming, String requesterEmail) {
        if (requesterEmail == null || requesterEmail.isBlank()) {
            throw new IllegalArgumentException("requesterEmail required");
        }
        if (incoming.getTitle() == null || incoming.getTitle().isBlank()) {
            throw new IllegalArgumentException("title required");
        }

        Task t = new Task();
        t.setRequesterEmail(requesterEmail.trim().toLowerCase());
        t.setGroupId(incoming.getGroupId()); // null = community/personal scope
        t.setTitle(incoming.getTitle().trim());
        t.setDescription(incoming.getDescription());
        t.setStatus(incoming.getStatus() != null ? incoming.getStatus() : TaskStatus.OPEN);
        t.setPriority(incoming.getPriority() != null ? incoming.getPriority() : Task.TaskPriority.MEDIUM);
        t.setLatitude(incoming.getLatitude());
        t.setLongitude(incoming.getLongitude());
        t.setDueAt(incoming.getDueAt());
        t.setParentTaskId(incoming.getParentTaskId());
        if (incoming.getTags() != null) t.getTags().addAll(incoming.getTags());
        if (incoming.getImageKeys() != null) t.getImageKeys().addAll(incoming.getImageKeys());

        // Kind validation — defaults to "ask" so legacy callers (the
        // existing CommunityTaskComposer) work unchanged. Non-default
        // kinds get the spec's authorized-set check before persist.
        String kind = incoming.getKind();
        if (kind == null || kind.isBlank()) {
            t.setKind("ask");
        } else {
            String k = kind.trim().toLowerCase();
            if (!AUTHORIZED_KINDS.contains(k)) {
                throw new IllegalArgumentException(
                        "kind must be one of " + AUTHORIZED_KINDS + ", got " + kind);
            }
            t.setKind(k);
        }

        // Marketplace fields. price + isFree are mutually exclusive
        // and only meaningful when kind="marketplace"; silently drop
        // them on other kinds rather than throw, since they may be
        // present in legacy payloads.
        if ("marketplace".equals(t.getKind())) {
            if (incoming.getPrice() != null && incoming.isFree()) {
                throw new IllegalArgumentException("Listing can be priced or free, not both");
            }
            t.setPrice(incoming.getPrice());
            t.setFree(incoming.isFree());
        }

        // Reverse-geocode to populate zipBucket so community-feed queries
        // can use it as a pre-filter. Safe to skip on group-scope tasks.
        if (t.getGroupId() == null && t.getLatitude() != null && t.getLongitude() != null) {
            try {
                NominatimGeocodeService.Place p = geocode.reverse(t.getLatitude(), t.getLongitude());
                if (p != null) t.setZipBucket(p.zipBucket());
            } catch (Exception e) {
                log.debug("Task zipBucket lookup failed: {}", e.getMessage());
            }
        }

        Task saved = taskRepo.save(t);
        TaskDto dto = TaskDto.fromEntity(saved);
        broadcastAfterCommit(dto);
        return dto;
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TaskDto> listByGroup(String groupId, TaskStatus status) {
        if (groupId == null || groupId.isBlank()) return List.of();
        List<Task> rows = (status == null)
                ? taskRepo.findByGroupIdOrderByCreatedAtDesc(groupId)
                : taskRepo.findByGroupIdAndStatusOrderByCreatedAtDesc(groupId, status);
        return withAuthors(rows.stream().map(TaskDto::fromEntity).collect(Collectors.toList()));
    }

    @Transactional(readOnly = true)
    public List<TaskDto> listRequestedBy(String email) {
        if (email == null || email.isBlank()) return List.of();
        return withAuthors(taskRepo.findByRequesterEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .map(TaskDto::fromEntity).collect(Collectors.toList()));
    }

    @Transactional(readOnly = true)
    public List<TaskDto> listClaimedBy(String email) {
        if (email == null || email.isBlank()) return List.of();
        return withAuthors(taskRepo.findByClaimedByEmailIgnoreCaseOrderByCreatedAtDesc(email).stream()
                .map(TaskDto::fromEntity).collect(Collectors.toList()));
    }

    /**
     * Community feed: open community-scope tasks within {@code radiusKm}
     * of the viewer's coords, sorted by distance, capped at 50.
     * {@code statuses} defaults to {OPEN, CLAIMED} so users still see
     * what's been picked up but is not yet done.
     */
    @Transactional(readOnly = true)
    public List<TaskDto> discoverCommunity(double lat, double lng, double radiusKm,
                                           Set<TaskStatus> statuses) {
        Set<TaskStatus> wanted = (statuses == null || statuses.isEmpty())
                ? EnumSet.of(TaskStatus.OPEN, TaskStatus.CLAIMED) : statuses;

        // No bucket pre-filter for now (we don't know the viewer's zip ahead
        // of the request). Service-layer Haversine + status filter is fine
        // at SitPrep scale; add bucket prefilter when row counts grow.
        List<Task> candidates = taskRepo.findCommunityCandidates(wanted, null);

        // Geo-less tasks are community-wide by construction — the requester
        // didn't pin them to a place, so they belong in every viewer's feed
        // regardless of radius. We tag them with null distanceKm so the FE
        // can render a "Wider community" indicator instead of a "X mi" pill.
        // The previous behavior (drop unconditionally) made geo-less tasks
        // invisible in every feed, even at the "Anywhere" radius — bug fix.
        List<TaskDto> within = new ArrayList<>();
        for (Task t : candidates) {
            if (t.getLatitude() == null || t.getLongitude() == null) {
                within.add(TaskDto.fromEntity(t, null));
                continue;
            }
            double d = haversineKm(lat, lng, t.getLatitude(), t.getLongitude());
            if (d > radiusKm) continue;
            within.add(TaskDto.fromEntity(t, roundKm(d)));
        }
        // null distance sorts last (after all geo-tagged within-radius tasks),
        // which matches the FE proximity-score expectation: nearby first,
        // then community-wide.
        within.sort(Comparator.comparingDouble(d -> d.distanceKm() == null ? Double.MAX_VALUE : d.distanceKm()));

        // Apply mode-aware sponsored suppression BEFORE the cap-50 trim
        // so a hidden sponsored row doesn't take a slot a real organic
        // ask could occupy. Mode lookup is cheap (single indexed
        // findById) and doesn't recompute — the cron tick keeps the
        // cell's row current, the FE's request just reads the latest.
        String cellMode;
        try {
            cellMode = alertModeService.getForLatLng(lat, lng).getState();
        } catch (Exception e) {
            // If mode lookup fails, default to calm so we don't
            // accidentally over-suppress on a misbehaving Nominatim or
            // DB blip.
            log.debug("TaskService: mode lookup failed for ({}, {}): {}", lat, lng, e.getMessage());
            cellMode = AlertModeService.CALM;
        }
        List<TaskDto> filtered = applySponsoredSuppression(within, cellMode);

        List<TaskDto> capped = filtered.size() > 50 ? filtered.subList(0, 50) : filtered;
        return withAuthors(capped);
    }

    // ---------------------------------------------------------------------
    // Lifecycle: claim, complete, cancel, reopen, patch, delete
    // ---------------------------------------------------------------------

    /**
     * A group leader claims a task on behalf of their group. The task must
     * currently be unclaimed and OPEN. Caller email must be admin/owner of
     * the claimer group — checked at the resource layer; this service trusts
     * the caller has been authorized.
     */
    @Transactional
    public TaskDto claim(Long taskId, String claimerGroupId, String claimerEmail) {
        Task t = mustExist(taskId);
        if (t.getStatus() != TaskStatus.OPEN || t.getClaimedByGroupId() != null) {
            throw new IllegalStateException("Task is not open for claim");
        }
        t.setClaimedByGroupId(claimerGroupId);
        t.setClaimedByEmail(claimerEmail == null ? null : claimerEmail.trim().toLowerCase());
        t.setStatus(TaskStatus.CLAIMED);
        t.setClaimedAt(Instant.now());
        return saveAndBroadcast(t);
    }

    /** Mark in-progress (claimer is actively working). */
    @Transactional
    public TaskDto markInProgress(Long taskId) {
        Task t = mustExist(taskId);
        if (t.getStatus() != TaskStatus.CLAIMED) {
            throw new IllegalStateException("Task must be claimed before marking in-progress");
        }
        t.setStatus(TaskStatus.IN_PROGRESS);
        return saveAndBroadcast(t);
    }

    /** Claimer marks complete. */
    @Transactional
    public TaskDto complete(Long taskId) {
        Task t = mustExist(taskId);
        if (t.getStatus() == TaskStatus.DONE || t.getStatus() == TaskStatus.CANCELLED) {
            throw new IllegalStateException("Task is already closed");
        }
        t.setStatus(TaskStatus.DONE);
        t.setCompletedAt(Instant.now());
        return saveAndBroadcast(t);
    }

    /** Requester cancels. Frees claimedBy state in case it was claimed. */
    @Transactional
    public TaskDto cancel(Long taskId) {
        Task t = mustExist(taskId);
        if (t.getStatus() == TaskStatus.DONE) {
            throw new IllegalStateException("Cannot cancel a completed task");
        }
        t.setStatus(TaskStatus.CANCELLED);
        return saveAndBroadcast(t);
    }

    /** Reopen a cancelled task — clears claimer state. */
    @Transactional
    public TaskDto reopen(Long taskId) {
        Task t = mustExist(taskId);
        if (t.getStatus() != TaskStatus.CANCELLED) {
            throw new IllegalStateException("Only cancelled tasks can be reopened");
        }
        t.setStatus(TaskStatus.OPEN);
        t.setClaimedByGroupId(null);
        t.setClaimedByEmail(null);
        t.setClaimedAt(null);
        return saveAndBroadcast(t);
    }

    /**
     * Author-only partial update — title, description, priority, dueAt,
     * tags, imageKeys, latitude/longitude. Lifecycle fields (status,
     * claimer*, claimedAt, completedAt) flow through dedicated methods.
     */
    @Transactional
    public TaskDto patch(Long taskId, Task patch) {
        Task t = mustExist(taskId);
        if (patch.getTitle() != null) t.setTitle(patch.getTitle());
        if (patch.getDescription() != null) t.setDescription(patch.getDescription());
        if (patch.getPriority() != null) t.setPriority(patch.getPriority());
        if (patch.getDueAt() != null) t.setDueAt(patch.getDueAt());
        if (patch.getTags() != null) {
            t.getTags().clear();
            t.getTags().addAll(patch.getTags());
        }
        if (patch.getImageKeys() != null) {
            t.getImageKeys().clear();
            t.getImageKeys().addAll(patch.getImageKeys());
        }
        if (patch.getLatitude() != null) t.setLatitude(patch.getLatitude());
        if (patch.getLongitude() != null) t.setLongitude(patch.getLongitude());
        return saveAndBroadcast(t);
    }

    @Transactional
    public void delete(Long taskId) {
        Task t = taskRepo.findById(taskId).orElse(null);
        if (t == null) return;
        String groupId = t.getGroupId();
        String zipBucket = t.getZipBucket();
        Long id = t.getId();
        taskRepo.delete(t);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ws.sendTaskDeletion(groupId, zipBucket, id);
                } catch (Exception e) {
                    log.error("WS task-delete broadcast failed for task {}", id, e);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Broadcast helpers — fire after commit so subscribers don't see a
    // pre-commit state if the transaction rolls back.
    // ---------------------------------------------------------------------

    private TaskDto saveAndBroadcast(Task t) {
        Task saved = taskRepo.save(t);
        TaskDto dto = TaskDto.fromEntity(saved);
        broadcastAfterCommit(dto);
        return dto;
    }

    private void broadcastAfterCommit(TaskDto dto) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    ws.sendTaskUpdate(dto);
                } catch (Exception e) {
                    log.error("WS task broadcast failed for task {}", dto.id(), e);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** For resource-layer ownership / role checks. */
    @Transactional(readOnly = true)
    public Optional<Task> findById(Long id) {
        return id == null ? Optional.empty() : taskRepo.findById(id);
    }

    private Task mustExist(Long id) {
        return taskRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    private static double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }
}
