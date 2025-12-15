package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.RSEvent;
import io.sitprep.sitprepapi.domain.RSGroup;
import io.sitprep.sitprepapi.dto.RSEventDto;
import io.sitprep.sitprepapi.dto.RSEventMapper;
import io.sitprep.sitprepapi.repo.RSEventRepo;
import io.sitprep.sitprepapi.repo.RSGroupRepo;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RSEventService {

    private final RSEventRepo eventRepo;
    private final RSGroupRepo groupRepo;
    private final RSGroupService groupService;

    public RSEventService(RSEventRepo eventRepo, RSGroupRepo groupRepo, RSGroupService groupService) {
        this.eventRepo = eventRepo;
        this.groupRepo = groupRepo;
        this.groupService = groupService;
    }

    public List<RSEventDto> getEventsForGroup(String groupId) {
        return eventRepo.findByGroupIdHydrated(groupId)
                .stream()
                .map(RSEventMapper::toDto)
                .toList();
    }

    public Optional<RSEventDto> getById(String id) {
        return eventRepo.findByIdHydrated(id).map(RSEventMapper::toDto);
    }

    /**
     * MVP FEED:
     * - show PUBLIC events from PUBLIC groups
     * - plus ALL events (private or public) for groups where user is ACTIVE member
     */
    public List<RSEventDto> getFeed(String emailFallback) {
        String email = resolveEmail(emailFallback);

        // Public group ids
        List<RSGroup> publicGroups = groupRepo.findByIsPrivateFalseOrderByCreatedAtDesc();
        Set<String> publicGroupIds = publicGroups.stream().map(RSGroup::getId).collect(Collectors.toSet());

        // My group ids (active membership)
        Set<String> myGroupIds = new HashSet<>();
        if (email != null && !email.isBlank()) {
            List<RSGroup> myGroups = groupService.getGroupsForCurrentUserOrEmailFallback(email);
            for (RSGroup g : myGroups) myGroupIds.add(g.getId());
        }

        // union for fetch
        Set<String> union = new HashSet<>();
        union.addAll(publicGroupIds);
        union.addAll(myGroupIds);

        if (union.isEmpty()) return List.of();

        List<RSEvent> events = eventRepo.findByGroupIdInHydrated(new ArrayList<>(union));

        // Filter rule:
        // - events in my groups => always visible
        // - events in public groups => only visible if event.isPrivate == false
        List<RSEvent> filtered = events.stream()
                .filter(e -> {
                    if (myGroupIds.contains(e.getGroupId())) return true;
                    if (publicGroupIds.contains(e.getGroupId())) return !Boolean.TRUE.equals(e.getIsPrivate());
                    return false;
                })
                .toList();

        return filtered.stream().map(RSEventMapper::toDto).toList();
    }

    @Transactional
    public RSEventDto createEvent(RSEvent incoming, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        String groupId = requireNonBlank(incoming.getGroupId(), "groupId");
        RSGroup g = groupRepo.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + groupId));

        requireMemberOrPublic(g, actor);

        RSEvent e = new RSEvent();
        e.setGroupId(groupId);
        e.setTitle(requireNonBlank(incoming.getTitle(), "title"));
        e.setEventType(incoming.getEventType());
        e.setStartsAt(Objects.requireNonNull(incoming.getStartsAt(), "startsAt is required"));
        e.setEndsAt(incoming.getEndsAt());
        e.setLocationName(incoming.getLocationName());
        e.setAddress(incoming.getAddress());
        e.setLatitude(incoming.getLatitude());
        e.setLongitude(incoming.getLongitude());
        e.setRecurrenceRule(incoming.getRecurrenceRule());
        e.setNotes(incoming.getNotes());

        // ✅ privacy: default handled in @PrePersist, but allow explicit create value
        // If client omits isPrivate -> null -> @PrePersist defaults to false.
        e.setIsPrivate(incoming.getIsPrivate());

        e.setCreatedByEmail(actor);
        if (e.getAttendeeEmails() != null) e.getAttendeeEmails().add(actor);

        e.setCancelled(incoming.isCancelled());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());

        RSEvent saved = eventRepo.save(e);

        return eventRepo.findByIdHydrated(saved.getId())
                .map(RSEventMapper::toDto)
                .orElse(RSEventMapper.toDto(saved));
    }

    @Transactional
    public RSEventDto updateEvent(String eventId, RSEvent incoming, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSEvent existing = eventRepo.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("RSEvent not found: " + eventId));
        RSGroup g = groupRepo.findById(existing.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + existing.getGroupId()));

        requireOwnerAdminOrEventCreator(g, existing, actor);

        if (incoming.getTitle() != null && !incoming.getTitle().isBlank()) existing.setTitle(incoming.getTitle().trim());
        if (incoming.getEventType() != null) existing.setEventType(incoming.getEventType());
        if (incoming.getStartsAt() != null) existing.setStartsAt(incoming.getStartsAt());
        if (incoming.getEndsAt() != null) existing.setEndsAt(incoming.getEndsAt());
        if (incoming.getLocationName() != null) existing.setLocationName(incoming.getLocationName());
        if (incoming.getAddress() != null) existing.setAddress(incoming.getAddress());
        if (incoming.getLatitude() != null) existing.setLatitude(incoming.getLatitude());
        if (incoming.getLongitude() != null) existing.setLongitude(incoming.getLongitude());
        if (incoming.getRecurrenceRule() != null) existing.setRecurrenceRule(incoming.getRecurrenceRule());
        if (incoming.getNotes() != null) existing.setNotes(incoming.getNotes());

        // ✅ privacy: only update if client explicitly sent it
        if (incoming.getIsPrivate() != null) {
            existing.setIsPrivate(incoming.getIsPrivate());
        }

        existing.setCancelled(incoming.isCancelled());
        existing.setUpdatedAt(Instant.now());

        eventRepo.save(existing);

        return eventRepo.findByIdHydrated(eventId)
                .map(RSEventMapper::toDto)
                .orElse(RSEventMapper.toDto(existing));
    }

    @Transactional
    public void deleteEvent(String eventId, String emailFallback) {
        String actor = mustResolveEmail(emailFallback);

        RSEvent existing = eventRepo.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("RSEvent not found: " + eventId));
        RSGroup g = groupRepo.findById(existing.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + existing.getGroupId()));

        requireOwnerAdminOrEventCreator(g, existing, actor);
        eventRepo.deleteById(eventId);
    }

    @Transactional
    public RSEventDto toggleAttend(String eventId, String emailFallback, String attendeeEmailOverride) {
        String actor = mustResolveEmail(emailFallback);
        String attendee = normalize(attendeeEmailOverride != null ? attendeeEmailOverride : actor);

        RSEvent existing = eventRepo.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("RSEvent not found: " + eventId));
        RSGroup g = groupRepo.findById(existing.getGroupId())
                .orElseThrow(() -> new IllegalArgumentException("RSGroup not found: " + existing.getGroupId()));

        requireMemberOrPublic(g, actor);

        if (attendee != null && !attendee.isBlank()) {
            if (existing.getAttendeeEmails().contains(attendee)) {
                existing.getAttendeeEmails().remove(attendee);
            } else {
                existing.getAttendeeEmails().add(attendee);
            }
        }

        existing.setUpdatedAt(Instant.now());
        eventRepo.save(existing);

        return eventRepo.findByIdHydrated(eventId)
                .map(RSEventMapper::toDto)
                .orElse(RSEventMapper.toDto(existing));
    }

    // ---- permission helpers ----

    private void requireOwnerAdminOrEventCreator(RSGroup g, RSEvent e, String actor) {
        String a = normalize(actor);
        if (a == null) throw new IllegalArgumentException("Unauthorized");

        if (groupService.isOwnerOrAdmin(g.getId(), a)) return;

        if (a.equalsIgnoreCase(normalize(e.getCreatedByEmail()))) return;

        throw new IllegalArgumentException("Owner/admin/creator permission required.");
    }

    private void requireMemberOrPublic(RSGroup g, String actor) {
        String a = normalize(actor);
        if (a == null) throw new IllegalArgumentException("Unauthorized");
        if (!g.isPrivate()) return;

        if (!groupService.isActiveMember(g.getId(), a)) {
            throw new IllegalArgumentException("This group is private. Active membership required.");
        }
    }

    // ---- email helpers ----

    private String resolveEmail(String fallback) {
        try {
            String fromAuth = AuthUtils.getCurrentUserEmail();
            if (fromAuth != null && !fromAuth.isBlank()) return normalize(fromAuth);
        } catch (Exception ignored) {}
        return normalize(fallback);
    }

    private String mustResolveEmail(String fallback) {
        String e = resolveEmail(fallback);
        if (e == null || e.isBlank()) throw new IllegalArgumentException("User email required (auth or request).");
        return e;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}