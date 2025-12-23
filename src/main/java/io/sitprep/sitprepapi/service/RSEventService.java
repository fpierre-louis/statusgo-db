package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.RSEventDto;
import io.sitprep.sitprepapi.dto.RSEventMapper;
import io.sitprep.sitprepapi.dto.RSEventUpsertRequest;
import io.sitprep.sitprepapi.repo.RSEventAttendanceRepo;
import io.sitprep.sitprepapi.repo.RSEventRepo;
import io.sitprep.sitprepapi.repo.RSGroupRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RSEventService {

    private static final List<RSAttendanceStatus> COUNTABLE_STATUSES =
            List.of(RSAttendanceStatus.GOING, RSAttendanceStatus.MAYBE);
    private static final Logger log = LoggerFactory.getLogger(RSEventService.class);
    private final RSEventRepo eventRepo;
    private final RSEventAttendanceRepo attendanceRepo;
    private final RSGroupRepo groupRepo;
    private final RSGroupService groupService;

    public RSEventService(
            RSEventRepo eventRepo,
            RSEventAttendanceRepo attendanceRepo,
            RSGroupRepo groupRepo,
            RSGroupService groupService
    ) {
        this.eventRepo = eventRepo;
        this.attendanceRepo = attendanceRepo;
        this.groupRepo = groupRepo;
        this.groupService = groupService;
    }

    /* =========================================================
       READS
       ========================================================= */

    public List<RSEventDto> getEventsForGroup(String groupId, String emailFallback) {
        String viewer = resolveEmail(emailFallback);

        RSGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found"));

        Set<String> myGroupIds = getMyActiveGroupIds(viewer);

        if (g.isPrivate()) {
            if (viewer == null) throw new IllegalArgumentException("Unauthorized");
            if (!myGroupIds.contains(groupId)) throw new IllegalArgumentException("PRIVATE_GROUP");
        }

        List<RSEvent> events = eventRepo.findByGroupIdHydrated(groupId);

        return enrichForViewer(
                filterByVisibilityForViewer(events, viewer, myGroupIds, Map.of(groupId, g)),
                viewer,
                myGroupIds
        );
    }

    public Optional<RSEventDto> getById(String id, String emailFallback) {
        String viewer = resolveEmail(emailFallback);

        RSEvent e = eventRepo.findByIdHydrated(id).orElse(null);
        if (e == null) return Optional.empty();

        RSGroup g = groupRepo.findById(e.getGroupId()).orElse(null);
        if (g == null) return Optional.empty();

        Set<String> myGroupIds = getMyActiveGroupIds(viewer);

        List<RSEvent> filtered = filterByVisibilityForViewer(
                List.of(e), viewer, myGroupIds, Map.of(g.getId(), g)
        );

        if (filtered.isEmpty()) return Optional.empty();

        return enrichForViewer(filtered, viewer, myGroupIds).stream().findFirst();
    }

    public List<RSEventDto> getAll(Integer limit) {
        int lim = Math.min(limit == null ? 50 : limit, 50);
        return eventRepo.findAllLatest(PageRequest.of(0, lim))
                .stream()
                .map(RSEventMapper::toDto)
                .toList();
    }

    public List<RSEventDto> getFeed(String emailFallback, Instant from, Instant to, Integer limit) {
        String viewer = resolveEmail(emailFallback);
        Set<String> myGroupIds = getMyActiveGroupIds(viewer);

        Instant now = Instant.now();
        Instant start = from != null ? from : now.minus(30, ChronoUnit.DAYS);
        Instant end = to != null ? to : now.plus(180, ChronoUnit.DAYS);

        Pageable page = PageRequest.of(0, limit == null ? 50 : limit);

        List<RSEvent> publicEvents =
                eventRepo.findLightByVisibilityBetween(
                        RSEventVisibility.DISCOVERABLE_PUBLIC, start, end, page
                );

        List<RSEvent> myGroupEvents =
                myGroupIds.isEmpty()
                        ? List.of()
                        : eventRepo.findLightByGroupIdsBetween(
                        new ArrayList<>(myGroupIds), start, end, page
                );

        Map<String, RSEvent> merged = new LinkedHashMap<>();
        publicEvents.forEach(e -> merged.put(e.getId(), e));
        myGroupEvents.forEach(e -> merged.put(e.getId(), e));

        return enrichForViewer(
                filterByVisibilityForViewer(
                        new ArrayList<>(merged.values()),
                        viewer,
                        myGroupIds,
                        null
                ),
                viewer,
                myGroupIds
        );
    }

    /* =========================================================
       WRITES
       ========================================================= */

    @Transactional
    public RSEventDto createEvent(RSEventUpsertRequest req, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSGroup g = groupRepo.findById(req.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found"));

        RSEvent e = new RSEvent();
        e.setGroupId(req.getGroupId());
        e.setTitle(req.getTitle());
        e.setEventType(req.getEventType());
        e.setStartsAt(req.getStartsAt());
        e.setEndsAt(req.getEndsAt());
        e.setLocationName(req.getLocationName());
        e.setAddress(req.getAddress());
        e.setLatitude(req.getLatitude());
        e.setLongitude(req.getLongitude());
        e.setVisibility(req.getVisibility() != null ? req.getVisibility() : RSEventVisibility.GROUP_ONLY);
        e.setCreatedByEmail(actor);
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());

        eventRepo.save(e);

        upsertAttendance(e.getId(), actor, RSAttendanceStatus.GOING, RSEventAttendeeRole.HOST);

        return getById(e.getId(), actor).orElseThrow();
    }

    @Transactional
    public RSEventDto updateEvent(String id, RSEventUpsertRequest req, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSEvent e = eventRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        if (req.getTitle() != null) e.setTitle(req.getTitle());
        if (req.getStartsAt() != null) e.setStartsAt(req.getStartsAt());
        if (req.getEndsAt() != null) e.setEndsAt(req.getEndsAt());
        if (req.getVisibility() != null) e.setVisibility(req.getVisibility());

        e.setUpdatedAt(Instant.now());
        eventRepo.save(e);

        return getById(id, actor).orElseThrow();
    }

    @Transactional
    public void deleteEvent(String id, String emailFallback) {
        mustResolveEmail(emailFallback);
        eventRepo.deleteById(id);
    }
    @Transactional
    public RSEventDto toggleAttend(String id, String emailFallback, String attendeeOverride) {
        String actor = mustResolveEmail(emailFallback);
        String attendee = normalize(attendeeOverride != null ? attendeeOverride : actor);

        log.info("toggleAttend: eventId={}, actor={}, attendee={}", id, actor, attendee);

        attendanceRepo.findByEventIdAndAttendeeEmailIgnoreCase(id, attendee)
                .ifPresentOrElse(
                        existing -> {
                            log.info("toggleAttend: removing existing attendance id={}, status={}", existing.getId(), existing.getStatus());
                            attendanceRepo.delete(existing);
                        },
                        () -> {
                            log.info("toggleAttend: creating GOING attendance for attendee={}", attendee);
                            upsertAttendance(id, attendee, RSAttendanceStatus.GOING, RSEventAttendeeRole.PLAYER);
                        }
                );

        long cnt = attendanceRepo.countByEventIdAndStatusIn(id, COUNTABLE_STATUSES);
        log.info("toggleAttend: countAfter={}, statuses={}", cnt, COUNTABLE_STATUSES);

        return getById(id, actor).orElseThrow();
    }

    /* =========================================================
       JOIN REQUEST MODERATION
       ========================================================= */

    @Transactional
    public RSEventDto approveJoinRequest(String eventId, String targetEmail, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSEventAttendance row = attendanceRepo
                .findByEventIdAndAttendeeEmailIgnoreCase(eventId, normalize(targetEmail))
                .orElseThrow(() -> new IllegalArgumentException("No pending request"));

        row.setStatus(RSAttendanceStatus.GOING);
        row.setUpdatedAt(Instant.now());
        attendanceRepo.save(row);

        return getById(eventId, actor).orElseThrow();
    }

    @Transactional
    public RSEventDto rejectJoinRequest(String eventId, String targetEmail, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        attendanceRepo
                .findByEventIdAndAttendeeEmailIgnoreCase(eventId, normalize(targetEmail))
                .ifPresent(attendanceRepo::delete);

        return getById(eventId, actor).orElseThrow();
    }

    /* =========================================================
       HELPERS
       ========================================================= */

    private void upsertAttendance(String eventId, String email,
                                  RSAttendanceStatus status,
                                  RSEventAttendeeRole role) {
        RSEventAttendance a = attendanceRepo
                .findByEventIdAndAttendeeEmailIgnoreCase(eventId, email)
                .orElseGet(() -> RSEventAttendance.builder()
                        .eventId(eventId)
                        .attendeeEmail(email)
                        .createdAt(Instant.now())
                        .build());

        a.setStatus(status);
        a.setRole(role);
        a.setUpdatedAt(Instant.now());
        attendanceRepo.save(a);
    }

    private Set<String> getMyActiveGroupIds(String viewer) {
        if (viewer == null) return Set.of();
        return groupService.getGroupsForCurrentUserOrEmailFallback(viewer)
                .stream().map(RSGroup::getId).collect(Collectors.toSet());
    }

    private String resolveEmail(String fallback) {
        try {
            String auth = AuthUtils.getCurrentUserEmail();
            if (auth != null) return auth.toLowerCase();
        } catch (Exception ignored) {}
        return fallback == null ? null : fallback.toLowerCase();
    }

    private String mustResolveEmail(String fallback) {
        String e = resolveEmail(fallback);
        if (e == null) throw new IllegalArgumentException("Email required");
        return e;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    /* =========================================================
       VISIBILITY + ENRICHMENT
       ========================================================= */

    private List<RSEvent> filterByVisibilityForViewer(
            List<RSEvent> events,
            String viewer,
            Set<String> myGroupIds,
            Map<String, RSGroup> groupById
    ) {
        if (events == null) return List.of();

        return events.stream()
                .filter(e -> {
                    if (e.getVisibility() == RSEventVisibility.DISCOVERABLE_PUBLIC) return true;
                    if (viewer == null) return false;
                    if (myGroupIds.contains(e.getGroupId())) return true;
                    return viewer.equalsIgnoreCase(e.getCreatedByEmail());
                })
                .toList();
    }

    private List<RSEventDto> enrichForViewer(
            List<RSEvent> events,
            String viewer,
            Set<String> myGroupIds
    ) {
        if (events == null || events.isEmpty()) return List.of();

        Set<String> ids = events.stream().map(RSEvent::getId).collect(Collectors.toSet());

        // Count GOING/MAYBE per event
        Map<String, Long> counts = attendanceRepo
                .countAcrossEvents(ids, COUNTABLE_STATUSES)
                .stream()
                .collect(Collectors.toMap(
                        RSEventAttendanceRepo.EventCountRow::getEventId,
                        r -> r.getCnt() == null ? 0L : r.getCnt()
                ));

        // ✅ Pull attendee emails per event (GOING/MAYBE only)
        Map<String, Set<String>> attendeeEmailsByEvent = new HashMap<>();
        attendanceRepo
                .findAttendeeEmailsAcrossEvents(ids, COUNTABLE_STATUSES)
                .forEach(row -> {
                    attendeeEmailsByEvent
                            .computeIfAbsent(row.getEventId(), k -> new LinkedHashSet<>())
                            .add(row.getAttendeeEmail());
                });

        // Optional: viewer status per event (helps UI, not required)
        Map<String, RSAttendanceStatus> viewerStatusByEvent = new HashMap<>();
        if (viewer != null && !viewer.isBlank()) {
            attendanceRepo.findForViewerAcrossEvents(viewer, ids).forEach(a -> {
                viewerStatusByEvent.put(a.getEventId(), a.getStatus());
            });
        }

        return events.stream()
                .map(e -> {
                    Set<String> attendeeEmails = attendeeEmailsByEvent.getOrDefault(e.getId(), Set.of());
                    Integer attendeeCount = counts.getOrDefault(e.getId(), 0L).intValue();

                    RSAttendanceStatus viewerStatus = viewerStatusByEvent.get(e.getId());

                    // viewerCanJoin / joinBlockReason can be computed later; keep null for now
                    return RSEventMapper.toDto(
                            e,
                            attendeeCount,
                            viewerStatus,
                            null,
                            null,
                            attendeeEmails
                    );
                })
                .toList();
    }
}