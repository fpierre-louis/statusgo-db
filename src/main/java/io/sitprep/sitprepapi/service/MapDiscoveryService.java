package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.Post.PostStatus;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MapDiscoveryDto;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Community Discovery Engine (docs/COMMUNITY_API_GAMEPLAN.md, Phase 1).
 *
 * <p>Given the visible viewport bounding box + zoom, returns a normalized,
 * deduped, capped {@link MapPoiDto} list fused from our PROPRIETARY data —
 * public groups (agencies + joinable) and mutual-aid community Posts. Uses the
 * V28 (latitude, longitude) composite indexes for an index range-scan of the
 * box instead of the legacy full-table scan.</p>
 *
 * <p>Structured to accept EXTERNAL sources (Overpass parks/amenities, FEMA
 * shelters) in Phase 2 — see the seam in {@link #discover}. Those merge into
 * the same {@code pois} list through the same normalize → dedupe → cap path.</p>
 *
 * <p>Progressive disclosure by zoom band (§1.2): agencies always; joinable
 * groups at z≥10; mutual-aid at z≥13. Per-band caps keep dense metros legible
 * and the payload small.</p>
 */
@Service
public class MapDiscoveryService {

    private final GroupRepo groupRepo;
    private final PostRepo postRepo;
    private final UserInfoRepo userInfoRepo;
    private final ExternalPoiCacheService externalPois;

    public MapDiscoveryService(GroupRepo groupRepo, PostRepo postRepo, UserInfoRepo userInfoRepo,
                               ExternalPoiCacheService externalPois) {
        this.groupRepo = groupRepo;
        this.postRepo = postRepo;
        this.userInfoRepo = userInfoRepo;
        this.externalPois = externalPois;
    }

    // Verified-publisher kinds that mark an OFFICIAL agency. Mirrors the small
    // classifier in CommunityDiscoverService (kept local so this endpoint is
    // self-contained; the two agree on the same set).
    private static final Set<String> AGENCY_KINDS = Set.of(
            "government", "public_safety", "publicsafety", "fire", "police",
            "law_enforcement", "ems", "emergency_management", "city", "county",
            "state", "municipal", "agency");

    // Community Post kinds that count as mutual aid on the map.
    private static final Set<String> AID_KINDS = Set.of("offer", "marketplace", "resource");

    public MapDiscoveryDto discover(double minLat, double minLng,
                                    double maxLat, double maxLng,
                                    int zoom, String viewerEmail) {
        double centerLat = (minLat + maxLat) / 2.0;
        double centerLng = (minLng + maxLng) / 2.0;
        int band = bandOf(zoom);
        String viewer = viewerEmail == null ? null : viewerEmail.toLowerCase();

        List<MapPoiDto> pois = new ArrayList<>();
        List<String> sources = new ArrayList<>();

        // ── Proprietary: public groups (agencies always; joinable at z≥10) ──
        List<Group> groups = groupRepo.findPublicInBounds(minLat, maxLat, minLng, maxLng);
        Map<String, UserInfo> owners = batchOwners(groups);
        sources.add("proprietary:group");
        for (Group g : groups) {
            Double lat = g.getLatitude();
            Double lng = g.getLongitude();
            if (lat == null || lng == null) continue;
            UserInfo owner = g.getOwnerEmail() == null ? null
                    : owners.get(g.getOwnerEmail().toLowerCase());
            boolean verified = owner != null && owner.isVerifiedPublisher();
            String verifiedKind = verified ? owner.getVerifiedPublisherKind() : null;
            boolean agency = isAgency(g, verified, verifiedKind);

            // Progressive disclosure: joinable (non-agency) groups only appear
            // once the viewer has zoomed into the city band.
            if (!agency && band < 1) continue;

            double dist = haversineKm(centerLat, centerLng, lat, lng);
            int memberCount = g.getMemberEmails() == null ? 0 : g.getMemberEmails().size();
            pois.add(new MapPoiDto(
                    "group:" + g.getGroupId(),
                    agency ? "agency" : "group",
                    "proprietary:group",
                    g.getGroupName(),
                    lat, lng, round1(dist),
                    verified, verifiedKind, memberCount,
                    viewerRoleOf(g, viewer),
                    agency && owner != null ? owner.getId() : null,
                    null, null, null, null,   // aid fields
                    null, null, null, null    // external fields
            ));
        }

        // ── Proprietary: mutual-aid Posts (z≥13 / neighborhood band) ──
        if (band >= 2) {
            List<Post> aid = postRepo.findAidInBounds(
                    PostStatus.OPEN, AID_KINDS, minLat, maxLat, minLng, maxLng);
            sources.add("proprietary:post");
            for (Post p : aid) {
                Double lat = p.getLatitude();
                Double lng = p.getLongitude();
                if (lat == null || lng == null) continue;
                double dist = haversineKm(centerLat, centerLng, lat, lng);
                pois.add(new MapPoiDto(
                        "post:" + p.getId(),
                        "aid",
                        "proprietary:post",
                        aidName(p),
                        lat, lng, round1(dist),
                        null, null, null, null, null,   // group/agency fields
                        p.getId(), p.getKind(), p.getDescription(), p.getPlaceLabel(),
                        null, null, null, null          // external fields
                ));
            }
        }

        // ── External POIs (Phase 2): parks / schools / civic amenities ──
        // Neighborhood band + only. Served from the tile cache with
        // stale-while-revalidate (never blocks on Overpass). The cache returns
        // the snapped-tile POIs (⊇ viewport); we filter to the actual box and
        // assign this request's distance.
        if (band >= 2) {
            List<MapPoiDto> ext = externalPois.getPois(minLat, minLng, maxLat, maxLng);
            boolean any = false;
            for (MapPoiDto p : ext) {
                if (p.lat() == null || p.lng() == null) continue;
                if (p.lat() < minLat || p.lat() > maxLat || p.lng() < minLng || p.lng() > maxLng) continue;
                pois.add(withDistance(p, round1(haversineKm(centerLat, centerLng, p.lat(), p.lng()))));
                any = true;
            }
            if (any) sources.add("overpass");
        }

        // Normalize → sort by distance → cap per band.
        pois.sort(Comparator.comparing(
                MapPoiDto::distanceKm, Comparator.nullsLast(Comparator.naturalOrder())));
        int cap = capFor(band);
        boolean capped = pois.size() > cap;
        if (capped) pois = new ArrayList<>(pois.subList(0, cap));

        return new MapDiscoveryDto(
                pois,
                new MapDiscoveryDto.MetaDto(Instant.now(), band, pois.size(), capped, sources)
        );
    }

    // ── Zoom bands + caps (§1.2) ────────────────────────────────────────
    private static int bandOf(int zoom) {
        if (zoom <= 9) return 0;   // regional
        if (zoom <= 12) return 1;  // city
        if (zoom <= 14) return 2;  // neighborhood
        return 3;                  // street
    }

    private static int capFor(int band) {
        return switch (band) {
            case 0 -> 40;
            case 1 -> 120;
            default -> 250;
        };
    }

    // ── Helpers ─────────────────────────────────────────────────────────
    private Map<String, UserInfo> batchOwners(List<Group> groups) {
        List<String> emails = groups.stream()
                .map(Group::getOwnerEmail)
                .filter(e -> e != null && !e.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.toList());
        Map<String, UserInfo> byEmail = new HashMap<>();
        if (!emails.isEmpty()) {
            for (UserInfo u : userInfoRepo.findByUserEmailIn(emails)) {
                if (u.getUserEmail() != null) byEmail.put(u.getUserEmail().toLowerCase(), u);
            }
        }
        return byEmail;
    }

    private static boolean isAgency(Group g, boolean verified, String verifiedKind) {
        if (g.isAgencyAuthorized()) return true;
        return verified && verifiedKind != null
                && AGENCY_KINDS.contains(verifiedKind.trim().toLowerCase().replace('-', '_'));
    }

    private static String viewerRoleOf(Group g, String viewerEmail) {
        if (viewerEmail == null) return "NONE";
        if (viewerEmail.equalsIgnoreCase(g.getOwnerEmail())) return "OWNER";
        if (contains(g.getAdminEmails(), viewerEmail)) return "ADMIN";
        if (contains(g.getMemberEmails(), viewerEmail)) return "MEMBER";
        if (contains(g.getPendingMemberEmails(), viewerEmail)) return "PENDING";
        return "NONE";
    }

    private static boolean contains(List<String> list, String needle) {
        if (list == null) return false;
        for (String e : list) {
            if (e != null && e.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private static String aidName(Post p) {
        if (p.getTitle() != null && !p.getTitle().isBlank()) return p.getTitle();
        String d = p.getDescription();
        if (d == null || d.isBlank()) return "Neighbor offer";
        String firstLine = d.strip().split("\\R", 2)[0];
        return firstLine.length() > 60 ? firstLine.substring(0, 57) + "…" : firstLine;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Copy a MapPoi with distanceKm filled in (records are immutable). */
    private static MapPoiDto withDistance(MapPoiDto p, Double distanceKm) {
        return new MapPoiDto(
                p.id(), p.family(), p.source(), p.name(),
                p.lat(), p.lng(), distanceKm,
                p.verified(), p.verifiedKind(), p.memberCount(), p.viewerRole(), p.ownerUserId(),
                p.postId(), p.kind(), p.description(), p.placeLabel(),
                p.category(), p.website(), p.externalMapUrl(), p.attribution());
    }

    /** Great-circle distance in kilometers. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
