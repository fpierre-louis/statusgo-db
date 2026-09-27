package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-2 (V83): what one location fix derives — source, accuracy, "At &lt;place&gt;"
 * with its arrival time, and the "last seen near" label.
 */
class LocationPresenceServiceTest {

    private static final String ME = "ann@x.com";
    // School at a fixed point; offsets below are measured north of it.
    private static final double S_LAT = 40.0;
    private static final double S_LNG = -111.0;
    /** ~1 m of latitude, in degrees. */
    private static final double M = 1.0 / 111_195.0;

    private UserSavedLocationRepo repo;
    private NominatimGeocodeService geocode;
    private LocationPresenceService service;
    private UserInfo ann;

    @BeforeEach
    void setUp() {
        repo = mock(UserSavedLocationRepo.class);
        geocode = mock(NominatimGeocodeService.class);
        service = new LocationPresenceService(repo, geocode, null);
        ann = new UserInfo();
        ann.setUserEmail(ME);
        ann.setLastKnownZip("84043"); // zip already known: only the label drives geocoding below
    }

    private static UserSavedLocation place(long id, String name, String kind, double lat, double lng,
                                           int radiusM, boolean share) {
        UserSavedLocation p = new UserSavedLocation();
        p.setId(id);
        p.setOwnerEmail(ME);
        p.setName(name);
        p.setKind(kind);
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setRadiusM(radiusM);
        p.setSharePresence(share);
        return p;
    }

    // ── normalisation ──────────────────────────────────────────────────────

    @Test
    void sourceIsOneOfThreeOrNull() {
        assertThat(LocationPresenceService.normalizeSource(" Watch ")).isEqualTo("watch");
        assertThat(LocationPresenceService.normalizeSource("phone")).isEqualTo("phone");
        assertThat(LocationPresenceService.normalizeSource("web")).isEqualTo("web");
        assertThat(LocationPresenceService.normalizeSource("toaster")).isNull();
        assertThat(LocationPresenceService.normalizeSource("")).isNull();
        assertThat(LocationPresenceService.normalizeSource(null)).isNull();
    }

    @Test
    void accuracyIsAPositiveClampedInt() {
        assertThat(LocationPresenceService.normalizeAccuracyM(12.6)).isEqualTo(13);
        assertThat(LocationPresenceService.normalizeAccuracyM(250_000)).isEqualTo(100_000);
        assertThat(LocationPresenceService.normalizeAccuracyM(0)).isNull();
        assertThat(LocationPresenceService.normalizeAccuracyM(-5)).isNull();
        assertThat(LocationPresenceService.normalizeAccuracyM(0.2)).isNull();
        assertThat(LocationPresenceService.normalizeAccuracyM(Double.NaN)).isNull();
        assertThat(LocationPresenceService.normalizeAccuracyM(null)).isNull();
    }

    @Test
    void placeKindIsValidatedAndRadiusClamped() {
        assertThat(LocationPresenceService.normalizePlaceKind("School")).isEqualTo("school");
        assertThat(LocationPresenceService.normalizePlaceKind(" ")).isNull();
        assertThatThrownBy(() -> LocationPresenceService.normalizePlaceKind("gym"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(LocationPresenceService.clampRadiusM(null)).isEqualTo(150);
        assertThat(LocationPresenceService.clampRadiusM(10)).isEqualTo(50);
        assertThat(LocationPresenceService.clampRadiusM(9000)).isEqualTo(2000);
    }

    // ── match radius + accuracy ────────────────────────────────────────────

    @Test
    void insideTheRadiusMatches() {
        var school = place(1, "Lincoln Elementary", "school", S_LAT, S_LNG, 150, true);
        assertThat(LocationPresenceService.matchPlace(List.of(school), S_LAT + 140 * M, S_LNG, null))
                .isSameAs(school);
        assertThat(LocationPresenceService.matchPlace(List.of(school), S_LAT + 200 * M, S_LNG, null))
                .isNull();
    }

    @Test
    void accuracyWidensTheMatchByAtMost100m() {
        var school = place(1, "Lincoln Elementary", "school", S_LAT, S_LNG, 150, true);
        // 200 m out: radius 150 + accuracy 60 = 210 → in
        assertThat(LocationPresenceService.matchPlace(List.of(school), S_LAT + 200 * M, S_LNG, 60))
                .isSameAs(school);
        // 240 m out with a 5 km cell fix: slack capped at 100 → 250 → in
        assertThat(LocationPresenceService.matchPlace(List.of(school), S_LAT + 240 * M, S_LNG, 5000))
                .isSameAs(school);
        // 300 m out with the same fix: 150 + 100 = 250 < 300 → out. A town-sized
        // uncertainty circle must not put someone "At school".
        assertThat(LocationPresenceService.matchPlace(List.of(school), S_LAT + 300 * M, S_LNG, 5000))
                .isNull();
    }

    @Test
    void onlyOptedInPlacesMatchAndTheNearestWins() {
        var privateHome = place(1, "Home", "home", S_LAT, S_LNG, 2000, false);
        var work = place(2, "Office", "work", S_LAT + 100 * M, S_LNG, 500, true);
        var gym = place(3, "Gym", "other", S_LAT + 20 * M, S_LNG, 500, true);
        // The unshared home is the closest and is never matched.
        assertThat(LocationPresenceService.matchPlace(List.of(privateHome, work, gym), S_LAT, S_LNG, null))
                .isSameAs(gym);
    }

    @Test
    void radiusIsClampedToTheMinimumAtMatchTime() {
        var tiny = place(1, "Locker", "other", S_LAT, S_LNG, 10, true); // stored below the floor
        assertThat(LocationPresenceService.matchPlace(List.of(tiny), S_LAT + 45 * M, S_LNG, null))
                .isSameAs(tiny);
    }

    // ── since-preservation ─────────────────────────────────────────────────

    @Test
    void sinceIsTheArrivalTimeAndSurvivesLaterPings() {
        var school = place(1, "Lincoln Elementary", "school", S_LAT, S_LNG, 150, true);
        var work = place(2, "Office", "work", S_LAT + 0.01, S_LNG, 150, true);
        when(repo.findByOwnerEmailIgnoreCaseAndSharePresenceTrue(ME)).thenReturn(List.of(school, work));
        Instant t0 = Instant.parse("2026-09-27T14:05:00Z");

        service.applyFix(ann, S_LAT, S_LNG, "phone", 10, t0);
        assertThat(ann.getCurrentPlaceId()).isEqualTo(1L);
        assertThat(ann.getCurrentPlaceSince()).isEqualTo(t0);

        service.applyFix(ann, S_LAT + 30 * M, S_LNG, "watch", 8, t0.plusSeconds(3600));
        assertThat(ann.getCurrentPlaceSince()).as("same place → arrival time kept").isEqualTo(t0);
        assertThat(ann.getLocationSource()).isEqualTo("watch");
        assertThat(ann.getLocationAccuracyM()).isEqualTo(8);

        service.applyFix(ann, S_LAT + 0.005, S_LNG, null, null, t0.plusSeconds(7200));
        assertThat(ann.getCurrentPlaceId()).as("between places → cleared").isNull();
        assertThat(ann.getCurrentPlaceSince()).isNull();
        assertThat(ann.getLocationSource()).as("no source on this fix → null, not the previous one").isNull();
        assertThat(ann.getLocationAccuracyM()).isNull();

        Instant t3 = t0.plusSeconds(9000);
        service.applyFix(ann, S_LAT + 0.01, S_LNG, "phone", null, t3);
        assertThat(ann.getCurrentPlaceId()).isEqualTo(2L);
        assertThat(ann.getCurrentPlaceSince()).as("a different place → new arrival").isEqualTo(t3);
    }

    @Test
    void theFixItselfIsRecorded() {
        Instant t = Instant.parse("2026-09-27T14:05:00Z");
        service.applyFix(ann, 40.5, -111.5, "web", 25.4, t);
        assertThat(ann.getLastKnownLat()).isEqualTo(40.5);
        assertThat(ann.getLastKnownLng()).isEqualTo(-111.5);
        assertThat(ann.getLastKnownLocationAt()).isEqualTo(t);
        assertThat(ann.getLocationAccuracyM()).isEqualTo(25);
    }

    // ── last seen near ─────────────────────────────────────────────────────

    private static NominatimGeocodeService.Place label(String neighborhood) {
        return new NominatimGeocodeService.Place(neighborhood, "Lehi", null, "Utah", "US", "840", "84043");
    }

    @Test
    void labelIsResolvedOnceAndRefreshedOnlyPastTheAnchorThrottle() {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(label("Dry Creek"));

        service.applyFix(ann, 40.40, -111.85, null, null, Instant.now());
        assertThat(ann.getLastSeenNearLabel()).isEqualTo("Dry Creek");

        // Five small steps of 0.008° each — every step is far under the 0.03°
        // rule measured from the PREVIOUS fix, so a previous-fix throttle would
        // never refresh. Measured from where the label was resolved, the 4th
        // step (0.032°) crosses it — once — and becomes the new anchor.
        for (int i = 1; i <= 5; i++) {
            service.applyFix(ann, 40.40 + 0.008 * i, -111.85, null, null, Instant.now());
        }
        verify(geocode, times(2)).reverse(anyDouble(), anyDouble());
        assertThat(ann.getLastSeenNearLat()).isEqualTo(40.432, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aLabelThatNoLongerDescribesTheFixIsCleared() {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(label("Dry Creek"));
        service.applyFix(ann, 40.40, -111.85, null, null, Instant.now());

        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null); // Nominatim down
        service.applyFix(ann, 40.50, -111.85, null, null, Instant.now()); // ~7 mi away
        assertThat(ann.getLastSeenNearLabel()).isNull();
        assertThat(ann.getLastKnownZip()).as("zip keeps its existing best-effort rule").isEqualTo("84043");
    }

    @Test
    void noSharedPlacesMeansNoPresenceAndNoPlaceQueryResult() {
        when(repo.findByOwnerEmailIgnoreCaseAndSharePresenceTrue(anyString())).thenReturn(List.of());
        service.applyFix(ann, S_LAT, S_LNG, "phone", 5, Instant.now());
        assertThat(ann.getCurrentPlaceId()).isNull();
        assertThat(ann.getCurrentPlaceSince()).isNull();
        verify(repo, never()).findAll();
    }
}
