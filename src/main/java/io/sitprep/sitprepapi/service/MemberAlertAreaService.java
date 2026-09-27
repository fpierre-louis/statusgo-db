package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.GeoJsonArea;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which ACTIVE alerts a coordinate is inside — {@code MemberSummary.inAlertIds}
 * (map-ideal BE-4), so the map can say "2 of 4 inside the heat advisory" for a
 * zone-only product that has no polygon to test against client-side.
 *
 * <h2>The id</h2>
 * {@link NormalizedAlert#id()} — exactly what {@code AlertFeedService.toCard}
 * ships as {@code AlertCardDto.id} on {@code /api/alerts/feed}, from the same
 * {@link AlertIngestService#getSnapshot()}. The client joins on it directly.
 *
 * <h2>Inside, not near</h2>
 * The feed answers "relevant to this point" (first vertex within a radius,
 * zone match, even a state-prefix fallback). "Inside" is stricter, and follows
 * the feed's tier order — geometry beats zones:
 * <ol>
 *   <li>alert has a polygon → the point must be inside it. A storm-based
 *       warning's UGC lists every county it touches, so matching on zones too
 *       would put a whole county "inside" a polygon a few miles wide;</li>
 *   <li>alert has a non-area geometry (an earthquake point) → never inside;</li>
 *   <li>no geometry → its UGC codes intersect the point's zone codes.</li>
 * </ol>
 * "Active" is the feed's own filter (not suppressed by
 * {@link AlertSafetyPolicy}) plus a lifecycle of {@code active}/{@code updated}
 * — an expired or cancelled alert has no inside.
 *
 * <h2>No fan-out</h2>
 * Zone codes are read from {@link NwsZoneService}'s cache only. A miss queues a
 * background warm and answers {@code null} (unknown) for that point — never
 * {@code []}, which would claim "inside none" about a place we have not looked
 * up. The prepared alert list is rebuilt once per ingest snapshot, not per read.
 */
@Service
public class MemberAlertAreaService {

    private final AlertIngestService ingest;
    private final AlertDispatchService dispatch;
    private final NwsZoneService zones;

    private volatile Prepared prepared;

    public MemberAlertAreaService(AlertIngestService ingest, AlertDispatchService dispatch,
                                  NwsZoneService zones) {
        this.ingest = ingest;
        this.dispatch = dispatch;
        this.zones = zones;
    }

    /** One alert, pre-parsed for repeated point tests. */
    record AreaAlert(NormalizedAlert alert, GeoJsonArea area, boolean pointOnly, Set<String> ugc) {}

    private record Prepared(Snapshot snapshot, List<AreaAlert> alerts) {}

    /**
     * The alerts to test against, built once per snapshot. Call once per
     * roster read and pass the result to {@link #idsFor} for each member.
     */
    public List<AreaAlert> activeAreas() {
        Snapshot snap = ingest == null ? null : ingest.getSnapshot();
        if (snap == null || snap.alerts() == null) return List.of();
        Prepared p = prepared;
        if (p != null && p.snapshot() == snap) return p.alerts();

        List<AreaAlert> out = new ArrayList<>();
        for (NormalizedAlert a : snap.alerts()) {
            if (a == null || a.id() == null) continue;
            if (AlertSafetyPolicy.evaluate(a, dispatch == null ? null : dispatch.matchForAlert(a).orElse(null))
                    .dispatchMode() == AlertSafetyPolicy.DispatchMode.SUPPRESS) {
                continue; // the feed never shows it, so nobody is "inside" it
            }
            GeoJsonArea area = GeoJsonArea.parse(a.geometry());
            boolean pointOnly = area == null && a.geometry() != null;
            Set<String> ugc = new HashSet<>();
            if (a.ugc() != null) {
                for (String c : a.ugc()) {
                    if (c != null && !c.isBlank()) ugc.add(c.trim().toUpperCase(Locale.ROOT));
                }
            }
            out.add(new AreaAlert(a, area, pointOnly, Set.copyOf(ugc)));
        }
        List<AreaAlert> built = List.copyOf(out);
        prepared = new Prepared(snap, built);
        return built;
    }

    /**
     * Ids of the active alerts whose area contains the point: {@code []} when
     * inside none, {@code null} when the point's zone codes are not known yet
     * (a warm is queued).
     */
    public List<String> idsFor(List<AreaAlert> areas, double lat, double lng) {
        Optional<Set<String>> pointZones = zones == null
                ? Optional.empty()
                : zones.cachedZoneCodesForPoint(lat, lng);
        if (pointZones.isEmpty()) {
            if (zones != null) zones.warmPoint(lat, lng);
            return null;
        }
        return match(areas, lat, lng, pointZones.get(), Instant.now());
    }

    /** Pure matching — see the class doc for the rule. */
    static List<String> match(List<AreaAlert> areas, double lat, double lng,
                              Set<String> pointZones, Instant now) {
        List<String> ids = new ArrayList<>();
        if (areas == null) return ids;
        for (AreaAlert a : areas) {
            String life = AlertDerivations.lifecycleState(a.alert(), now);
            if (AlertDerivations.LIFECYCLE_EXPIRED.equals(life)
                    || AlertDerivations.LIFECYCLE_SUPERSEDED.equals(life)) {
                continue;
            }
            boolean inside;
            if (a.area() != null) {
                inside = a.area().contains(lat, lng);
            } else if (a.pointOnly()) {
                inside = false;
            } else {
                inside = false;
                for (String code : a.ugc()) {
                    if (pointZones.contains(code)) { inside = true; break; }
                }
            }
            if (inside) ids.add(a.alert().id());
        }
        return ids;
    }
}
