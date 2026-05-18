package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.dto.ResourceListingDto;
import io.sitprep.sitprepapi.dto.SubmitResourceRequest;
import io.sitprep.sitprepapi.repo.ResourceListingRepo;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    public ResourceListingService(ResourceListingRepo repo) {
        this.repo = repo;
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
        return out;
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
        r.setLatitude(req.latitude());
        r.setLongitude(req.longitude());
        r.setSource(ResourceListing.Source.COMMUNITY);
        r.setStatus(ResourceListing.Status.APPROVED);
        r.setSubmittedByEmail(submitterEmail);
        return toDto(repo.save(r), null);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private ResourceListingDto toDto(ResourceListing r, Double distanceKm) {
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
                r.getCreatedAt()
        );
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeCategory(String c) {
        String t = trimOrNull(c);
        return t == null ? "other" : t.toLowerCase();
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
