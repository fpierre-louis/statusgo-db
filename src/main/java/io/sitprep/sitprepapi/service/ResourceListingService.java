package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GeoUtil;
import io.sitprep.sitprepapi.util.OpeningHours;
import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.dto.MapConfirmationDtos;
import io.sitprep.sitprepapi.dto.ResourceListingDto;
import io.sitprep.sitprepapi.dto.SubmitResourceRequest;
import io.sitprep.sitprepapi.repo.ResourceListingRepo;
import jakarta.transaction.Transactional;
import io.sitprep.sitprepapi.constant.ResourceCategory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Community resource board — read + write. Reads return national
 * listings plus the geo-pinned listings within a default radius of the
 * viewer; writes record a resident's submission (auto-approved during
 * closed beta).
 */
@Service
public class ResourceListingService {

    /**
     * Default board radius. The resource board is not the community
     * post feed (whose radius the user tunes) — per the geo policy it
     * uses a backend-set default.
     */
    private static final double DEFAULT_RADIUS_KM = 40.0;
    private static final double EARTH_RADIUS_KM = 6371.0;

    private final ResourceListingRepo repo;
    private final MapConfirmationService confirmations;

    public ResourceListingService(ResourceListingRepo repo, MapConfirmationService confirmations) {
        this.repo = repo;
        this.confirmations = confirmations;
    }

    /**
     * Board contents for a viewer. National listings (null coords)
     * always appear first as stable anchors; geo-pinned listings
     * follow, nearest first, filtered to {@code radiusKm}. When the
     * viewer has no location only the national listings come back —
     * we can't place a geo-pinned listing without a viewer point.
     */
    public List<ResourceListingDto> board(Double lat, Double lng, Double radiusKm) {
        double radius = (radiusKm != null && radiusKm > 0) ? radiusKm : DEFAULT_RADIUS_KM;
        boolean hasViewer = lat != null && lng != null;

        List<ResourceListing> all =
                repo.findByStatusOrderByCreatedAtDesc(ResourceListing.Status.APPROVED);

        List<ResourceListingDto> national = new ArrayList<>();
        List<ResourceListingDto> nearby = new ArrayList<>();

        for (ResourceListing r : all) {
            boolean geoPinned = r.getLatitude() != null && r.getLongitude() != null;
            if (!geoPinned) {
                national.add(toDto(r, null));
                continue;
            }
            if (!hasViewer) continue;
            double d = haversineKm(lat, lng, r.getLatitude(), r.getLongitude());
            if (d <= radius) nearby.add(toDto(r, d));
        }

        nearby.sort(Comparator.comparing(
                ResourceListingDto::distanceKm,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<ResourceListingDto> out = new ArrayList<>(national.size() + nearby.size());
        out.addAll(national);
        out.addAll(nearby);
        return withConfirmations(out);
    }

    /** "Still here?" counts for the whole board in one query (V85). */
    private List<ResourceListingDto> withConfirmations(List<ResourceListingDto> rows) {
        if (confirmations == null || rows.isEmpty()) return rows;
        Map<String, MapConfirmationDtos.ConfirmationSummary> byId = confirmations.summaries("resource",
                rows.stream().map(d -> String.valueOf(d.id())).toList(), Instant.now());
        if (byId.isEmpty()) return rows;
        List<ResourceListingDto> out = new ArrayList<>(rows.size());
        for (ResourceListingDto d : rows) {
            var c = byId.get(String.valueOf(d.id()));
            out.add(c == null ? d : new ResourceListingDto(d.id(), d.title(), d.description(), d.category(),
                    d.latitude(), d.longitude(), d.address(), d.contact(), d.source(), d.distanceKm(),
                    d.createdAt(), d.hours(), d.openNow(), d.closesAt(), d.opensAt(), c));
        }
        return out;
    }

    public Optional<ResourceListingDto> findPublicPreview(Long id) {
        if (id == null) return Optional.empty();
        return repo.findByIdAndStatus(id, ResourceListing.Status.APPROVED)
                .map(r -> toDto(r, null))
                .map(d -> withConfirmations(List.of(d)).get(0));
    }

    /** Record a resident's submission. Auto-approved for closed beta. */
    @Transactional
    public ResourceListingDto submit(SubmitResourceRequest req, String submitterEmail) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A resource needs a title");
        }
        ResourceListing r = new ResourceListing();
        r.setTitle(req.title().trim());
        r.setDescription(trimOrNull(req.description()));
        r.setCategory(normalizeCategory(req.category()));
        r.setAddress(trimOrNull(req.address()));
        r.setContact(trimOrNull(req.contact()));
        GeoUtil.requireValidLatLng(req.latitude(), req.longitude());
        r.setLatitude(req.latitude());
        r.setLongitude(req.longitude());
        r.setSource(ResourceListing.Source.COMMUNITY);
        r.setStatus(ResourceListing.Status.APPROVED);
        r.setSubmittedByEmail(submitterEmail);
        r.setHoursJson(req.hours() == null ? null : validHours(req.hours()).toJson());
        return toDto(repo.save(r), null);
    }

    /**
     * Set or clear a listing's hours — {@code PATCH /api/resources/{id}} with
     * {@code {"hours": {...}}} or {@code {"hours": null}}.
     *
     * <p>Only the resident who submitted the listing may change it. OFFICIAL
     * and imported rows carry no submitter and are therefore not editable
     * here — they change through their seeder / importer.</p>
     */
    @Transactional
    public ResourceListingDto updateHours(Long id, Map<String, Object> body, String callerEmail) {
        if (body == null || !body.containsKey("hours")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Body must carry an \"hours\" field");
        }
        ResourceListing r = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (r.getSubmittedByEmail() == null || callerEmail == null
                || !r.getSubmittedByEmail().trim().equalsIgnoreCase(callerEmail.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the person who added this resource can change its hours");
        }
        Object hours = body.get("hours");
        r.setHoursJson(hours == null ? null : validHours(hours).toJson());
        return withConfirmations(List.of(toDto(repo.save(r), null))).get(0);
    }

    private static OpeningHours validHours(Object raw) {
        try {
            return OpeningHours.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private ResourceListingDto toDto(ResourceListing r, Double distanceKm) {
        return toDto(r, distanceKm, Instant.now());
    }

    ResourceListingDto toDto(ResourceListing r, Double distanceKm, Instant now) {
        // Hours are re-read through the validator: a row that somehow no longer
        // parses reports NO hours and NO open state — never a guess.
        OpeningHours hours = null;
        if (r.getHoursJson() != null) {
            try {
                hours = OpeningHours.parse(r.getHoursJson());
            } catch (IllegalArgumentException ignored) {
                hours = null;
            }
        }
        OpeningHours.Status status = hours == null ? OpeningHours.Status.UNKNOWN : hours.statusAt(now);
        return new ResourceListingDto(
                r.getId(),
                r.getTitle(),
                r.getDescription(),
                r.getCategory(),
                r.getLatitude(),
                r.getLongitude(),
                r.getAddress(),
                r.getContact(),
                r.getSource() == null ? null : r.getSource().name(),
                distanceKm,
                r.getCreatedAt(),
                hours == null ? null : hours.toJson(),
                status.openNow(),
                status.closesAt(),
                status.opensAt(),
                null
        );
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Trim + lowercase, then validate against {@link ResourceCategory} — the
     * same set {@code ResourceSeeder} writes from.
     *
     * <p>An unrecognized category is <b>rejected with 400</b> rather than
     * coerced to {@code other}. The category comes from a fixed chip set, so
     * any other value is a client defect; coercing would hide it and quietly
     * recreate the free-text drift this vocabulary exists to end. Same call,
     * and the same reasoning, as {@code PostService.create} rejecting an
     * unknown {@code authoredAsGroupId} with 400 instead of silently
     * stripping the attribution.</p>
     *
     * <p>Absent or blank stays permissive — {@code other} — because omitting
     * the field is not a defect.</p>
     */
    private static String normalizeCategory(String c) {
        String normalized = ResourceCategory.normalize(c);
        if (!ResourceCategory.isValid(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown resource category: " + normalized);
        }
        return normalized;
    }

    /** Great-circle distance in km between two lat/lng points. */
    private static double haversineKm(double lat1, double lng1,
                                      double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
