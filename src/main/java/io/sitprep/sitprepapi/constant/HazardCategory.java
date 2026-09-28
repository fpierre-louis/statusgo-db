package io.sitprep.sitprepapi.constant;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The preset hazard categories (hazard-reports gameplan, H-1…H-4).
 *
 * <p>Each carries what the map and the router need without a second lookup:
 * the label shown (and stored as the post title), whether a CONFIRMED report
 * BLOCKS a route or only WARNS, a default radius, and a lifetime after which a
 * report nobody re-confirms expires. Values are the gameplan's; change them
 * there first. The wire key is also the {@code ck_hazard_report_category}
 * CHECK list in V87 — adding one means widening that CHECK in a migration.</p>
 */
public enum HazardCategory {
    FIRE("fire", "Fire / smoke", true, 800, Duration.ofHours(12)),
    FLOOD("flood", "Flooding / water over road", true, 150, Duration.ofHours(12)),
    ROAD_CLOSED("road_closed", "Road blocked or closed", true, 50, Duration.ofHours(6)),
    ROAD_DAMAGE("road_damage", "Road damaged", true, 50, Duration.ofHours(72)),
    POWER_LINES("power_lines", "Downed power lines", true, 100, Duration.ofHours(12)),
    GAS_HAZMAT("gas_hazmat", "Gas leak / hazmat", true, 300, Duration.ofHours(6)),
    CRASH("crash", "Crash", false, 50, Duration.ofHours(2)),
    DEBRIS("debris", "Debris / fallen tree", false, 50, Duration.ofHours(12));

    private final String wire;
    private final String label;
    private final boolean blocksRoutes;
    private final int radiusM;
    private final Duration lifetime;

    HazardCategory(String wire, String label, boolean blocksRoutes, int radiusM, Duration lifetime) {
        this.wire = wire;
        this.label = label;
        this.blocksRoutes = blocksRoutes;
        this.radiusM = radiusM;
        this.lifetime = lifetime;
    }

    public String wire() { return wire; }
    public String label() { return label; }
    public boolean blocksRoutes() { return blocksRoutes; }
    public int radiusM() { return radiusM; }
    public Duration lifetime() { return lifetime; }

    public static Optional<HazardCategory> fromWire(String value) {
        if (value == null) return Optional.empty();
        String v = value.trim().toLowerCase();
        return Arrays.stream(values()).filter(c -> c.wire.equals(v)).findFirst();
    }
}
