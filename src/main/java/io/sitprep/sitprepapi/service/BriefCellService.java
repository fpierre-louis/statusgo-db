package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.LocationFreshness;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where daily briefs are made (EXEC-3B; gameplan §3.1).
 *
 * <p>A cell is a 0.1° grid square (about 11 km) that at least one user has a
 * stable location in: a saved Home place, a household address, a profile home
 * address, or a device fix from the last 14 days. These are the locations the
 * backend can read while the user's phone is offline, which a scheduler needs.
 * The feed's selected place lives only on the device and cannot be used.</p>
 *
 * <p><b>Privacy by construction.</b> A cell's anchor is the GRID POINT, the
 * same 0.1° snap ConditionsService takes readings on, never a centroid of the
 * members' homes. A one-member cell therefore cannot reveal that member's
 * address, and the queries behind this return coordinates only: no email, no
 * name, no count per person.</p>
 *
 * <p>Recomputed at most hourly; cells change when people move or sign up, not
 * minute to minute.</p>
 */
@Service
public class BriefCellService {

    /** A brief cell. {@code key} is stable ("40.4|-111.9"); lat/lng are the grid point. */
    public record Cell(String key, double lat, double lng) {}

    static final Duration REFRESH = Duration.ofHours(1);

    private final UserSavedLocationRepo savedLocations;
    private final GroupRepo groups;
    private final UserInfoRepo users;
    private final Clock clock;

    private volatile List<Cell> cached = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public BriefCellService(UserSavedLocationRepo savedLocations, GroupRepo groups, UserInfoRepo users) {
        this(savedLocations, groups, users, Clock.systemUTC());
    }

    BriefCellService(UserSavedLocationRepo savedLocations, GroupRepo groups, UserInfoRepo users, Clock clock) {
        this.savedLocations = savedLocations;
        this.groups = groups;
        this.users = users;
        this.clock = clock;
    }

    public static String keyFor(double lat, double lng) {
        return String.format(Locale.ROOT, "%.1f|%.1f", ConditionsService.snap(lat), ConditionsService.snap(lng));
    }

    /** Current cells, sorted by key so a tick visits them in a stable order. */
    public List<Cell> cells() {
        Instant now = clock.instant();
        if (cachedAt.plus(REFRESH).isAfter(now) && !cached.isEmpty()) return cached;

        Map<String, Cell> byKey = new TreeMap<>();
        // The house freshness rule (14 days by default), not a private copy of it.
        Instant freshSince = LocationFreshness.cutoff(now);
        addAll(byKey, savedLocations.findHomeCoordinates());
        addAll(byKey, groups.findHouseholdCoordinates());
        addAll(byKey, users.findHomeCoordinates());
        addAll(byKey, users.findFreshLocationCoordinates(freshSince));

        cached = List.copyOf(new ArrayList<>(byKey.values()));
        cachedAt = now;
        return cached;
    }

    private static void addAll(Map<String, Cell> byKey, Collection<Object[]> rows) {
        if (rows == null) return;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[0] instanceof Number) || !(row[1] instanceof Number)) continue;
            double lat = ((Number) row[0]).doubleValue();
            double lng = ((Number) row[1]).doubleValue();
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180 || (lat == 0 && lng == 0)) continue;
            double sLat = ConditionsService.snap(lat);
            double sLng = ConditionsService.snap(lng);
            String key = keyFor(sLat, sLng);
            byKey.putIfAbsent(key, new Cell(key, sLat, sLng));
        }
    }
}
