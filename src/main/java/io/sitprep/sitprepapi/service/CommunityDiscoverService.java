package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto.NearbyGroup;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto.Place;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * "What community is near me?" Returns public groups within a viewer's
 * radius, sorted by distance, plus the reverse-geocoded place label so
 * the page can render "Lahore, Punjab" without an extra round-trip.
 *
 * <p>Distance: Haversine in Java because Group stores lat/lng as String
 * (legacy) and converting to native numeric SQL would be a bigger schema
 * change. At SitPrep's scale (thousands of groups, not millions), an
 * in-memory pass over all public groups with non-null coords is fine.
 * When the public-group count gets large, add a zip-bucket pre-filter
 * on the JPQL side using the first 3 chars of {@code zipCode}.</p>
 */
@Service
public class CommunityDiscoverService {

    private static final Logger log = LoggerFactory.getLogger(CommunityDiscoverService.class);

    /** Mean Earth radius in km — Haversine. */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private static final int MAX_RESULTS = 50;
    private static final int VERSION = 1;

    private final GroupRepo groupRepo;
    private final NominatimGeocodeService geocode;

    public CommunityDiscoverService(GroupRepo groupRepo, NominatimGeocodeService geocode) {
        this.groupRepo = groupRepo;
        this.geocode = geocode;
    }

    @Transactional(readOnly = true)
    public CommunityDiscoverDto discover(Double lat, Double lng, double radiusKm) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) {
            throw new IllegalArgumentException("lat and lng are required");
        }
        if (radiusKm <= 0) radiusKm = 10.0;

        Place place = resolvePlace(lat, lng);

        List<Group> publicGroups = groupRepo.findAll().stream()
                .filter(g -> "public".equalsIgnoreCase(safe(g.getPrivacy())))
                .filter(g -> isParseable(g.getLatitude()) && isParseable(g.getLongitude()))
                .toList();

        List<NearbyGroup> withinRadius = new ArrayList<>();
        for (Group g : publicGroups) {
            double gLat = parseOrNaN(g.getLatitude());
            double gLng = parseOrNaN(g.getLongitude());
            double d = haversineKm(lat, lng, gLat, gLng);
            if (d > radiusKm) continue;
            withinRadius.add(toNearbyGroup(g, d));
        }

        withinRadius.sort(Comparator.comparingDouble(NearbyGroup::distanceKm));
        List<NearbyGroup> capped = withinRadius.size() > MAX_RESULTS
                ? withinRadius.subList(0, MAX_RESULTS)
                : withinRadius;

        return new CommunityDiscoverDto(
                place,
                capped,
                new CommunityDiscoverDto.MetaDto(
                        Instant.now(), VERSION, publicGroups.size(), capped.size()
                )
        );
    }

    private Place resolvePlace(double lat, double lng) {
        try {
            // Fully qualified — Place collides with our DTO Place above.
            NominatimGeocodeService.Place resolved = geocode.reverse(lat, lng);
            if (resolved == null) return null;
            return new Place(
                    resolved.city(), resolved.region(), resolved.state(),
                    resolved.country(), resolved.zipBucket()
            );
        } catch (Exception e) {
            log.warn("Reverse-geocode failed at lat={} lng={}: {}", lat, lng, e.getMessage());
            return null;
        }
    }

    private NearbyGroup toNearbyGroup(Group g, double distanceKm) {
        int memberCount = g.getMemberCount() != null ? g.getMemberCount()
                : (g.getMemberEmails() == null ? 0 : g.getMemberEmails().size());
        return new NearbyGroup(
                g.getGroupId(),
                g.getGroupName(),
                g.getGroupType(),
                g.getDescription(),
                memberCount,
                roundKm(distanceKm),
                g.getLatitude(),
                g.getLongitude(),
                g.getAddress(),
                g.getZipCode(),
                g.getAlert(),
                g.getPrivacy()
        );
    }

    /** Haversine — units in km. lat/lng in decimal degrees. */
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
        // 1 decimal — "2.3 km" reads cleaner than "2.347…"
        return Math.round(km * 10.0) / 10.0;
    }

    private static boolean isParseable(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            double d = Double.parseDouble(s.trim());
            return Double.isFinite(d);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double parseOrNaN(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
