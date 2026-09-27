package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the server derives from ONE location fix — the single place that
 * turns {@code (lat, lng, source?, accuracyM?)} into the facts the roster shows.
 *
 * <p>Both write paths call {@link #applyFix}: the presence ping
 * ({@code PATCH /api/userinfo/me/location}) and live-location session points.
 * A future watch app calls the same presence endpoint with
 * {@code source = "watch"}; nothing downstream can tell a watch fix from a
 * phone fix except the recorded source. See "Watch client contract" in
 * {@code docs/epics/map-ideal-backend/EXEC.md}.</p>
 *
 * <p>What a fix sets on the user (the caller saves):</p>
 * <ul>
 *   <li>{@code lastKnownLat/Lng/LocationAt} — as before;</li>
 *   <li>{@code locationSource}, {@code locationAccuracyM} — normalised, null
 *       when absent or unrecognised (an older client must keep working);</li>
 *   <li>{@code currentPlaceId/Since} — the saved place the fix falls inside,
 *       among the writer's places with {@code sharePresence = true} only;</li>
 *   <li>{@code lastKnownZip} and {@code lastSeenNearLabel} — one reverse
 *       geocode, throttled at ~2 mi.</li>
 * </ul>
 *
 * <p>Nothing here decides who may SEE any of it. That is the roster's
 * {@code LocationSharing} gate, which nulls every one of these fields together
 * with the coordinates.</p>
 */
@Service
public class LocationPresenceService {

    /** The recorded sources. Anything else is stored as null, never rejected. */
    public static final Set<String> SOURCES = Set.of("phone", "watch", "web");

    /** Saved-place kinds. */
    public static final Set<String> PLACE_KINDS = Set.of("home", "work", "school", "other");

    public static final int DEFAULT_RADIUS_M = 150;
    public static final int MIN_RADIUS_M = 50;
    public static final int MAX_RADIUS_M = 2000;
    static final int MAX_ACCURACY_M = 100_000;
    /** The fix's own uncertainty widens a match by at most this much. */
    static final int MAX_ACCURACY_SLACK_M = 100;
    static final int MAX_LABEL_LENGTH = 160;

    private final UserSavedLocationRepo savedLocationRepo;
    private final NominatimGeocodeService geocode;
    private final NwsZoneService zones;

    public LocationPresenceService(UserSavedLocationRepo savedLocationRepo,
                                   NominatimGeocodeService geocode,
                                   NwsZoneService zones) {
        this.savedLocationRepo = savedLocationRepo;
        this.geocode = geocode;
        this.zones = zones;
    }

    /**
     * Queue a background NWS zone lookup for this fix so the next roster read
     * can answer {@code inAlertIds} from cache (BE-4). Non-blocking. Called on
     * the presence ping only — live-location points arrive every few seconds
     * while moving, and the roster read warms any point it misses anyway.
     */
    public void warmAlertZones(double lat, double lng) {
        if (zones != null) zones.warmPoint(lat, lng);
    }

    /** What a fix resolved to — the matched place, for a roster frame. Null when outside every shared place. */
    public record FixResult(UserSavedLocation place) {}

    /**
     * Apply one fix to {@code u}. Mutates the entity; the caller saves it.
     *
     * @param at when the fix was taken (the ping's server time, or a live
     *           point's capture time)
     */
    public FixResult applyFix(UserInfo u, double lat, double lng,
                              String rawSource, Number rawAccuracyM, Instant at) {
        Double prevLat = u.getLastKnownLat();
        Double prevLng = u.getLastKnownLng();
        Instant when = at == null ? Instant.now() : at;

        u.setLastKnownLat(lat);
        u.setLastKnownLng(lng);
        u.setLastKnownLocationAt(when);
        u.setLocationSource(normalizeSource(rawSource));
        Integer accuracyM = normalizeAccuracyM(rawAccuracyM);
        u.setLocationAccuracyM(accuracyM);

        UserSavedLocation place = matchPlace(sharedPlacesOf(u.getUserEmail()), lat, lng, accuracyM);
        applyPlace(u, place, when);

        refreshGeocodeLabels(u, prevLat, prevLng, lat, lng);
        return new FixResult(place);
    }

    // ── Normalisation ───────────────────────────────────────────────────────

    /** {@code "phone" | "watch" | "web"}, else null. Case- and whitespace-insensitive. */
    public static String normalizeSource(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return SOURCES.contains(s) ? s : null;
    }

    /** A positive number of metres, rounded, clamped to 100 000; else null. */
    public static Integer normalizeAccuracyM(Number raw) {
        if (raw == null) return null;
        double d = raw.doubleValue();
        if (!Double.isFinite(d) || d <= 0) return null;
        long rounded = Math.round(d);
        if (rounded < 1) return null;
        return (int) Math.min(rounded, MAX_ACCURACY_M);
    }

    /** {@code home | work | school | other}, null for absent/blank. Throws for anything else. */
    public static String normalizePlaceKind(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (!PLACE_KINDS.contains(k)) {
            throw new IllegalArgumentException("kind must be one of home, work, school, other");
        }
        return k;
    }

    /** Stored kind for reads — never lets an unexpected legacy value through. */
    public static String readPlaceKind(String stored) {
        if (stored == null) return null;
        String k = stored.trim().toLowerCase(Locale.ROOT);
        return PLACE_KINDS.contains(k) ? k : null;
    }

    /** 50–2000 m; null → the default. */
    public static int clampRadiusM(Integer raw) {
        if (raw == null) return DEFAULT_RADIUS_M;
        return Math.max(MIN_RADIUS_M, Math.min(MAX_RADIUS_M, raw));
    }

    /**
     * The roster's "At &lt;place&gt;" for a matched place, or null.
     *
     * <p>Re-checks everything at the moment of use, because
     * {@code current_place_id} is not a foreign key and the owner can flip
     * {@code sharePresence} off at any time: the place must exist, belong to
     * {@code ownerEmail}, still share presence, and carry a name. The label is
     * the owner's own name for it — never coordinates, never an address.</p>
     */
    public static GroupMemberViewDto.AtPlace atPlaceOf(UserSavedLocation place, String ownerEmail,
                                                      Instant since) {
        if (place == null || !place.isSharePresence()) return null;
        if (ownerEmail == null || place.getOwnerEmail() == null
                || !place.getOwnerEmail().trim().equalsIgnoreCase(ownerEmail.trim())) {
            return null;
        }
        String label = place.getName() == null ? null : place.getName().trim();
        if (label == null || label.isEmpty()) return null;
        return new GroupMemberViewDto.AtPlace(label, readPlaceKind(place.getKind()), since);
    }

    // ── Place match ─────────────────────────────────────────────────────────

    private List<UserSavedLocation> sharedPlacesOf(String email) {
        if (email == null || email.isBlank() || savedLocationRepo == null) return List.of();
        return savedLocationRepo.findByOwnerEmailIgnoreCaseAndSharePresenceTrue(
                email.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The nearest place with {@code sharePresence = true} whose haversine
     * distance is within {@code radiusM + min(accuracyM ?? 0, 100)}; null when
     * none.
     *
     * <p>The accuracy slack is capped at 100 m so a 5 km cell-tower fix cannot
     * claim someone is "At school" because the school is somewhere inside a
     * circle the size of a town.</p>
     */
    static UserSavedLocation matchPlace(List<UserSavedLocation> places,
                                        double lat, double lng, Integer accuracyM) {
        if (places == null || places.isEmpty()) return null;
        int slack = Math.min(accuracyM == null ? 0 : accuracyM, MAX_ACCURACY_SLACK_M);
        UserSavedLocation best = null;
        double bestD = Double.MAX_VALUE;
        for (UserSavedLocation p : places) {
            if (p == null || !p.isSharePresence()) continue;
            if (p.getLatitude() == null || p.getLongitude() == null || p.getId() == null) continue;
            double d = haversineM(lat, lng, p.getLatitude(), p.getLongitude());
            if (d <= clampRadiusM(p.getRadiusM()) + slack && d < bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * {@code since} is kept while the fix stays in the same place — it is the
     * time they ARRIVED, not the time of the latest ping. Cleared when outside
     * every shared place.
     */
    static void applyPlace(UserInfo u, UserSavedLocation place, Instant at) {
        if (place == null) {
            u.setCurrentPlaceId(null);
            u.setCurrentPlaceSince(null);
            return;
        }
        if (!Objects.equals(u.getCurrentPlaceId(), place.getId()) || u.getCurrentPlaceSince() == null) {
            u.setCurrentPlaceId(place.getId());
            u.setCurrentPlaceSince(at);
        }
    }

    // ── Reverse geocode: zip + "last seen near" ─────────────────────────────

    /**
     * One reverse geocode serves both the jurisdiction zip and the
     * "last seen near" label, throttled at ~2 mi (0.03°).
     *
     * <p>The zip keeps its existing rule (refresh when null or when this fix
     * moved ~2 mi from the previous one). The label is measured from where it
     * was RESOLVED (its anchor), not from the previous fix: against the previous
     * fix, a person moving in small steps never crosses the threshold between
     * two pings and the label would describe a place they left miles ago. A
     * label that no longer describes the fix is cleared rather than kept — "last
     * seen near X" about somewhere they are not is a false statement.</p>
     */
    void refreshGeocodeLabels(UserInfo u, Double prevLat, Double prevLng, double lat, double lng) {
        boolean zipDue = u.getLastKnownZip() == null || movedMeaningfully(prevLat, prevLng, lat, lng);
        boolean labelDue = u.getLastSeenNearLabel() == null
                || movedMeaningfully(u.getLastSeenNearLat(), u.getLastSeenNearLng(), lat, lng);
        if (!zipDue && !labelDue) return;

        NominatimGeocodeService.Place p = null;
        try {
            p = geocode == null ? null : geocode.reverse(lat, lng);
        } catch (Exception ignore) {
            // best-effort — never fails a location write
        }
        if (p == null) {
            // Unresolved. The zip stays (existing behaviour). A label anchored
            // ~2 mi away no longer describes this fix, so it goes; the anchor
            // stays unset so the next fix retries.
            if (labelDue && u.getLastSeenNearLabel() != null) {
                u.setLastSeenNearLabel(null);
                u.setLastSeenNearLat(null);
                u.setLastSeenNearLng(null);
            }
            return;
        }
        if (p.postcode() != null && !p.postcode().isBlank()) {
            u.setLastKnownZip(p.postcode().trim());
        }
        if (labelDue) {
            u.setLastSeenNearLabel(truncate(p.shortLabel()));
            u.setLastSeenNearLat(lat);
            u.setLastSeenNearLng(lng);
        }
    }

    /** ~0.03° ≈ 2 mi in either axis — the throttle the zip has always used. */
    static boolean movedMeaningfully(Double fromLat, Double fromLng, double lat, double lng) {
        if (fromLat == null || fromLng == null) return true;
        return Math.abs(fromLat - lat) > 0.03 || Math.abs(fromLng - lng) > 0.03;
    }

    private static String truncate(String label) {
        if (label == null) return null;
        String s = label.trim();
        if (s.isEmpty()) return null;
        return s.length() <= MAX_LABEL_LENGTH ? s : s.substring(0, MAX_LABEL_LENGTH);
    }

    /** Great-circle distance in metres. */
    static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
