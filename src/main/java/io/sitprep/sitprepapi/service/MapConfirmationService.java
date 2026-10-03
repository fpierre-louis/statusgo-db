package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.gamification.TokenEvent;
import io.sitprep.sitprepapi.gamification.TokenEventPublisher;
import io.sitprep.sitprepapi.gamification.TokenEventType;

import io.sitprep.sitprepapi.domain.MapConfirmation;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.dto.MapConfirmationDtos.ConfirmationSummary;
import io.sitprep.sitprepapi.repo.MapConfirmationRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.ResourceListingRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * "Still here?" — people confirming a map place is still there (V85, BE-7).
 *
 * <p>Not {@code post_confirm} ("Me too" on a post). The two never share a row,
 * a table or a count.</p>
 */
@Service
public class MapConfirmationService {

    public static final Set<String> TARGET_TYPES = Set.of("resource", "post", "osm");
    /** A re-confirm of the same target by the same person inside this window is a 429. */
    public static final Duration COOLDOWN = Duration.ofMinutes(10);
    /** Reads count people who confirmed within this window. */
    public static final Duration WINDOW = Duration.ofDays(7);

    private static final Pattern NUMERIC_ID = Pattern.compile("^[1-9]\\d{0,18}$");
    private static final Pattern OSM_ID = Pattern.compile("^(node|way|relation)/[1-9]\\d{0,18}$");

    private final MapConfirmationRepo repo;
    private final TokenEventPublisher tokenEvents;
    private final ResourceListingRepo resources;
    private final PostRepo posts;

    public MapConfirmationService(MapConfirmationRepo repo, ResourceListingRepo resources, PostRepo posts,
                                  TokenEventPublisher tokenEvents) {
        this.tokenEvents = tokenEvents;
        this.repo = repo;
        this.resources = resources;
        this.posts = posts;
    }

    /**
     * Outcome of a confirm. {@code accepted = false} means the caller is inside
     * the cooldown; the summary is still current so the client can render it.
     */
    public record Outcome(boolean accepted, long count, Instant lastAt, long retryAfterSeconds) {}

    @Transactional
    public Outcome confirm(String rawType, String rawTargetId, String callerEmail, Instant now) {
        if (callerEmail == null || callerEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String type = normalizeType(rawType);
        String targetId = requireTarget(type, rawTargetId);
        String email = callerEmail.trim().toLowerCase(Locale.ROOT);

        Optional<MapConfirmation> existing = repo.findByTargetTypeAndTargetIdAndUserEmail(type, targetId, email);
        if (existing.isPresent() && existing.get().getConfirmedAt() != null
                && existing.get().getConfirmedAt().isAfter(now.minus(COOLDOWN))) {
            Instant allowedAt = existing.get().getConfirmedAt().plus(COOLDOWN);
            long retry = Math.max(1, Duration.between(now, allowedAt).toSeconds());
            ConfirmationSummary s = summaryOf(type, targetId, now);
            return new Outcome(false, s == null ? 0 : s.count(), s == null ? null : s.lastAt(), retry);
        }

        MapConfirmation c = existing.orElseGet(() -> {
            MapConfirmation n = new MapConfirmation();
            n.setTargetType(type);
            n.setTargetId(targetId);
            n.setUserEmail(email);
            return n;
        });
        c.setConfirmedAt(now);
        repo.save(c);
        repo.flush();
        tokenEvents.publishAfterCommit(TokenEvent.user(TokenEventType.MAP_CONFIRMED, email, type + ":" + targetId));

        ConfirmationSummary s = summaryOf(type, targetId, now);
        return new Outcome(true, s == null ? 1 : s.count(), s == null ? now : s.lastAt(), 0);
    }

    /** Recent confirmations for one target; null when nobody confirmed in the window. */
    public ConfirmationSummary summaryOf(String type, String targetId, Instant now) {
        return summaries(type, List.of(targetId), now).get(targetId);
    }

    /**
     * Recent confirmations for a batch of targets of one type, in ONE query.
     * Targets with nobody in the window are absent from the map — the DTO field
     * is then null, never {@code {count: 0}}.
     */
    public Map<String, ConfirmationSummary> summaries(String type, Collection<String> targetIds, Instant now) {
        if (repo == null || targetIds == null || targetIds.isEmpty()) return Map.of();
        List<String> ids = targetIds.stream().filter(id -> id != null && !id.isBlank()).distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) return Map.of();
        Map<String, ConfirmationSummary> out = new HashMap<>();
        for (Object[] row : repo.summarize(type, ids, now.minus(WINDOW))) {
            long count = ((Number) row[1]).longValue();
            if (count > 0) out.put((String) row[0], new ConfirmationSummary(count, (Instant) row[2]));
        }
        return out;
    }

    private static String normalizeType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!TARGET_TYPES.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetType must be resource, post or osm");
        }
        return t;
    }

    /**
     * The canonical target id, after checking the target is one the map can
     * actually show. 404 rather than 403 for a post that is not community-scope,
     * so this endpoint cannot be used to probe which group-post ids exist.
     */
    private String requireTarget(String type, String raw) {
        String id = raw == null ? "" : raw.trim();
        switch (type) {
            case "resource" -> {
                if (!NUMERIC_ID.matcher(id).matches()) throw badId();
                boolean visible = resources == null || resources
                        .findByIdAndStatus(Long.parseLong(id), ResourceListing.Status.APPROVED).isPresent();
                if (!visible) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            case "post" -> {
                if (!NUMERIC_ID.matcher(id).matches()) throw badId();
                Optional<Post> p = posts == null ? Optional.empty() : posts.findById(Long.parseLong(id));
                if (posts != null && (p.isEmpty() || p.get().getGroupId() != null)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                }
            }
            case "osm" -> {
                // OSM places live in the Overpass tile cache, not in a table we
                // can check against; the id form is all that is validated.
                if (!OSM_ID.matcher(id).matches()) throw badId();
            }
            default -> throw badId();
        }
        return id;
    }

    private static ResponseStatusException badId() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "targetId must be a numeric id (resource, post) or node/…, way/…, relation/… (osm)");
    }
}
