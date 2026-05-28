package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto.NearbyGroup;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto.Place;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
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
    private final UserInfoRepo userInfoRepo;
    private final GroupPostRepo groupPostRepo;
    private final NominatimGeocodeService geocode;

    public CommunityDiscoverService(GroupRepo groupRepo,
                                    UserInfoRepo userInfoRepo,
                                    GroupPostRepo groupPostRepo,
                                    NominatimGeocodeService geocode) {
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.groupPostRepo = groupPostRepo;
        this.geocode = geocode;
    }

    /**
     * @param viewerEmail   optional — when provided, groups the viewer is
     *                      already a member/admin/owner/pending of are
     *                      filtered out (unless {@code includeMine} is true).
     *                      Always lowercased before comparison.
     * @param includeMine   if true, the viewer's existing groups are kept in
     *                      the response and tagged with {@code viewerIsMember=true}.
     *                      Default false.
     */
    @Transactional(readOnly = true)
    public CommunityDiscoverDto discover(Double lat, Double lng, double radiusKm,
                                         String viewerEmail, boolean includeMine) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) {
            throw new IllegalArgumentException("lat and lng are required");
        }
        if (radiusKm <= 0) radiusKm = 10.0;

        Place place = resolvePlace(lat, lng);

        String normalizedViewer = (viewerEmail == null || viewerEmail.isBlank())
                ? null : viewerEmail.trim().toLowerCase();

        List<Group> publicGroups = groupRepo.findAll().stream()
                .filter(g -> "public".equalsIgnoreCase(safe(g.getPrivacy())))
                .filter(g -> isParseable(g.getLatitude()) && isParseable(g.getLongitude()))
                .toList();

        // Pre-compute the radius hits before doing any owner / activity
        // lookups — keeps the batched queries scoped to just the groups
        // the viewer actually sees, not every public group on the
        // planet.
        List<Group> withinRadiusGroups = new ArrayList<>();
        Map<String, Double> distanceByGroupId = new HashMap<>();
        for (Group g : publicGroups) {
            double gLat = parseOrNaN(g.getLatitude());
            double gLng = parseOrNaN(g.getLongitude());
            double d = haversineKm(lat, lng, gLat, gLng);
            if (d > radiusKm) continue;
            boolean viewerIsMember = isViewerInGroup(g, normalizedViewer);
            if (viewerIsMember && !includeMine) continue;
            withinRadiusGroups.add(g);
            if (g.getGroupId() != null) distanceByGroupId.put(g.getGroupId(), d);
        }

        // The viewer's mutual-contact set — every email that shares at
        // least one group with the viewer (excluding the viewer). One
        // membership query, one in-memory union. Used to compute the
        // "N mutual" social signal on every Discover card.
        Set<String> mutualSet = buildMutualSet(normalizedViewer);

        // Verified-publisher status follows the OWNER's UserInfo, not
        // the group itself — one batched lookup over the unique owners
        // of the radius hits.
        Map<String, UserInfo> ownersByEmail = batchOwnerLookup(withinRadiusGroups);

        // Per-group most-recent-post timestamp — one batched query for
        // the freshness meta on Discover cards. Mirrors the
        // lastActivityFor() helper on MeService.
        Map<String, Instant> latestPostMap = batchLatestPostMap(withinRadiusGroups);

        List<NearbyGroup> withinRadius = new ArrayList<>(withinRadiusGroups.size());
        for (Group g : withinRadiusGroups) {
            double d = distanceByGroupId.getOrDefault(g.getGroupId(), 0.0);
            boolean viewerIsMember = isViewerInGroup(g, normalizedViewer);
            UserInfo owner = g.getOwnerEmail() == null ? null
                    : ownersByEmail.get(g.getOwnerEmail().toLowerCase());
            boolean verified = owner != null && owner.isVerifiedPublisher();
            String verifiedKind = verified ? owner.getVerifiedPublisherKind() : null;
            int mutuals = countMutuals(g, mutualSet);
            Instant activity = lastActivityFor(g, latestPostMap);
            withinRadius.add(toNearbyGroup(g, d, viewerIsMember, verified, verifiedKind, mutuals, activity));
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

    /** True if {@code viewerEmail} is in admin/member/pending lists, or is the owner. */
    private static boolean isViewerInGroup(Group g, String viewerEmail) {
        if (viewerEmail == null) return false;
        if (viewerEmail.equalsIgnoreCase(g.getOwnerEmail())) return true;
        if (containsCaseInsensitive(g.getAdminEmails(), viewerEmail)) return true;
        if (containsCaseInsensitive(g.getMemberEmails(), viewerEmail)) return true;
        if (containsCaseInsensitive(g.getPendingMemberEmails(), viewerEmail)) return true;
        return false;
    }

    private static boolean containsCaseInsensitive(List<String> list, String needle) {
        if (list == null || list.isEmpty()) return false;
        for (String e : list) {
            if (e != null && e.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private Place resolvePlace(double lat, double lng) {
        try {
            // Fully qualified — Place collides with our DTO Place above.
            NominatimGeocodeService.Place resolved = geocode.reverse(lat, lng);
            if (resolved == null) return null;
            return new Place(
                    resolved.neighborhood(),
                    resolved.city(), resolved.region(), resolved.state(),
                    resolved.country(), resolved.zipBucket()
            );
        } catch (Exception e) {
            log.warn("Reverse-geocode failed at lat={} lng={}: {}", lat, lng, e.getMessage());
            return null;
        }
    }

    private NearbyGroup toNearbyGroup(Group g, double distanceKm, boolean viewerIsMember,
                                      boolean verified, String verifiedKind,
                                      int mutuals, Instant lastActivityAt) {
        // Accurate count from the member list (the denormalized
        // Group.memberCount drifts and isn't kept in sync on join/leave).
        int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
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
                g.getPrivacy(),
                viewerIsMember,
                verified,
                verifiedKind,
                mutuals,
                lastActivityAt
        );
    }

    /**
     * Every email that shares at least one group with {@code viewer}
     * (viewer's own email excluded). Single pass over the viewer's
     * memberships — each Group's memberEmails is EAGER-loaded so this
     * is one DB call + one in-memory union.
     */
    private Set<String> buildMutualSet(String viewer) {
        if (viewer == null) return Collections.emptySet();
        List<Group> viewersGroups;
        try {
            viewersGroups = groupRepo.findByMemberEmail(viewer);
        } catch (Exception e) {
            log.warn("buildMutualSet failed for {}: {}", viewer, e.getMessage());
            return Collections.emptySet();
        }
        if (viewersGroups == null || viewersGroups.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (Group g : viewersGroups) {
            if (g.getMemberEmails() == null) continue;
            for (String e : g.getMemberEmails()) {
                if (e == null) continue;
                String norm = e.toLowerCase();
                if (norm.equals(viewer)) continue;
                out.add(norm);
            }
        }
        return out;
    }

    /**
     * Batched owner lookup over the unique owner emails of the radius
     * hits. Result map is keyed by lowercased email. Returns an empty
     * map (not null) on any failure so callers can keep going.
     */
    private Map<String, UserInfo> batchOwnerLookup(List<Group> groups) {
        if (groups == null || groups.isEmpty()) return Collections.emptyMap();
        Set<String> emails = new LinkedHashSet<>();
        for (Group g : groups) {
            if (g.getOwnerEmail() != null && !g.getOwnerEmail().isBlank()) {
                emails.add(g.getOwnerEmail().toLowerCase());
            }
        }
        if (emails.isEmpty()) return Collections.emptyMap();
        Map<String, UserInfo> out = new HashMap<>();
        try {
            for (UserInfo u : userInfoRepo.findByUserEmailIn(new ArrayList<>(emails))) {
                if (u.getUserEmail() != null) out.put(u.getUserEmail().toLowerCase(), u);
            }
        } catch (Exception e) {
            log.warn("batchOwnerLookup failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * One batched MAX(timestamp) query across every radius hit. Groups
     * with no posts simply don't show up in the map and fall back to
     * Group.updatedAt in {@link #lastActivityFor}.
     */
    private Map<String, Instant> batchLatestPostMap(List<Group> groups) {
        if (groups == null || groups.isEmpty()) return Collections.emptyMap();
        List<String> ids = new ArrayList<>();
        for (Group g : groups) if (g.getGroupId() != null) ids.add(g.getGroupId());
        if (ids.isEmpty()) return Collections.emptyMap();
        Map<String, Instant> out = new HashMap<>();
        try {
            for (var p : groupPostRepo.findLatestPostsByGroupIds(ids)) {
                if (p.getGroupId() == null || p.getTimestamp() == null) continue;
                Instant cur = out.get(p.getGroupId());
                if (cur == null || p.getTimestamp().isAfter(cur)) {
                    out.put(p.getGroupId(), p.getTimestamp());
                }
            }
        } catch (Exception e) {
            log.warn("batchLatestPostMap failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * How many of {@code g}'s members are in the viewer's mutual set.
     * Set lookups are O(1), so even a 200-member group is a 200-step
     * loop with no DB hits.
     */
    private static int countMutuals(Group g, Set<String> mutualSet) {
        if (mutualSet.isEmpty() || g.getMemberEmails() == null) return 0;
        int n = 0;
        for (String e : g.getMemberEmails()) {
            if (e != null && mutualSet.contains(e.toLowerCase())) n++;
        }
        return n;
    }

    /**
     * Most-recent activity instant: latest post → updatedAt →
     * createdAt → now. Mirrors MeService.lastActivityFor() so the
     * Discover and My-circles surfaces speak the same dialect.
     */
    private static Instant lastActivityFor(Group g, Map<String, Instant> latestPostMap) {
        Instant post = g.getGroupId() == null ? null : latestPostMap.get(g.getGroupId());
        if (post != null) return post;
        if (g.getUpdatedAt() != null) return g.getUpdatedAt();
        if (g.getCreatedAt() != null) return g.getCreatedAt();
        return Instant.now();
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
