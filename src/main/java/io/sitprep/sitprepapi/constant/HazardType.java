package io.sitprep.sitprepapi.constant;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The hazard vocabulary — ONE list, read by every writer.
 *
 * <p><b>Why this exists.</b> An audit on 2026-08-22 found FOUR independent
 * hazard vocabularies in this codebase, none of which imported another:</p>
 *
 * <table>
 *   <caption>Vocabularies found before consolidation</caption>
 *   <tr><th>Writer</th><th>Values</th></tr>
 *   <tr><td>{@code AskService.ALLOWED_HAZARDS}</td>
 *       <td>hurricane wildfire earthquake blizzard flood tornado <b>heat smoke</b></td></tr>
 *   <tr><td>{@code templates/alert-dispatch-templates.json}</td>
 *       <td>hurricane wildfire earthquake blizzard flood tornado <b>heat other</b></td></tr>
 *   <tr><td>{@code RiskProfileService.HAZARD_LABEL}</td>
 *       <td>hurricane wildfire earthquake blizzard flood tornado <b>extreme_heat</b></td></tr>
 *   <tr><td>FE {@code sitprepGuides.js}</td>
 *       <td>hurricane wildfire earthquake blizzard flood</td></tr>
 * </table>
 *
 * <p>The core six agree. <b>The divergence is entirely at the edges, and one of
 * those edges is a live defect:</b> RiskProfileService says {@code extreme_heat}
 * and AskService accepts only {@code heat}, so a heat hazard travelling from one
 * to the other is silently dropped by {@code normalizeHazardSet} — during a heat
 * wave, the exact moment heat questions should pin to the top.</p>
 *
 * <p>That is the ResourceCategory failure again: two writers defining one
 * vocabulary independently, agreeing on the common cases, and diverging on the
 * rare one nobody tested. The fix is the same — one list in Java that every
 * writer reads, rather than a database CHECK the second writer cannot see.</p>
 *
 * <h2>Canonical spelling and aliases</h2>
 *
 * <p>{@link #HEAT} is canonically {@code heat}. {@code extreme_heat} parses to
 * it as an ALIAS rather than being renamed away, because RiskProfileService has
 * been emitting that string and rows may carry it. Aliasing costs one map entry
 * and makes the consolidation non-breaking; renaming would need a migration
 * plus a coordinated FE change for a value with no storage.</p>
 *
 * <p>{@link #OTHER} exists because the dispatch templates ship a catch-all
 * template. It is a real value on the wire and pretending otherwise would make
 * the dispatcher's own config invalid against this enum.</p>
 *
 * <h2>Unknown values are DROPPED, never coerced</h2>
 *
 * <p>{@link #parse} returns empty for anything unrecognised, and
 * {@link #normalize} filters. It deliberately does NOT fall back to
 * {@link #OTHER}: a typo silently becoming a valid hazard is how a vocabulary
 * rots, and "we do not know what this is" is different from "the issuer said
 * other".</p>
 */
public enum HazardType {
    HURRICANE("hurricane"),
    WILDFIRE("wildfire"),
    EARTHQUAKE("earthquake"),
    BLIZZARD("blizzard"),
    FLOOD("flood"),
    TORNADO("tornado"),
    /** Canonically {@code heat}. {@code extreme_heat} is an accepted alias. */
    HEAT("heat"),
    /** Air quality / wildfire smoke. Ask-authored today. */
    SMOKE("smoke"),
    /** The dispatch templates' catch-all. A real wire value, not a fallback. */
    OTHER("other");

    private final String wire;

    HazardType(String wire) {
        this.wire = wire;
    }

    /** The lowercase string that travels on the wire and sits in the database. */
    public String wire() {
        return wire;
    }

    /**
     * Alternate spellings that resolve to a canonical value.
     *
     * <p>Keep this map SMALL and keep every entry justified in a comment. An
     * alias map is how a vocabulary absorbs history; it is also how a
     * vocabulary becomes unreadable if anything may be added to it.</p>
     */
    private static final Map<String, HazardType> ALIASES = Map.of(
            // RiskProfileService.HAZARD_LABEL has emitted this since it shipped.
            "extreme_heat", HEAT,
            "extreme-heat", HEAT,
            // NWS phrasing that reaches the dispatcher's template matcher.
            "excessive_heat", HEAT,
            "winter_storm", BLIZZARD,
            "air_quality", SMOKE);

    private static final Map<String, HazardType> BY_WIRE =
            java.util.Arrays.stream(values())
                    .collect(Collectors.toUnmodifiableMap(HazardType::wire, h -> h));

    /** Every canonical wire value. */
    public static Set<String> wireValues() {
        return BY_WIRE.keySet();
    }

    /**
     * Parse one hazard string. Empty for null, blank, or unrecognised input —
     * never a coerced {@link #OTHER}.
     */
    public static Optional<HazardType> parse(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return Optional.empty();
        HazardType direct = BY_WIRE.get(k);
        if (direct != null) return Optional.of(direct);
        return Optional.ofNullable(ALIASES.get(k));
    }

    /**
     * Normalize a mixed collection to canonical wire values, dropping anything
     * unrecognised. Insertion-ordered so a caller's priority survives.
     */
    public static Set<String> normalize(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String s : raw) {
            parse(s).ifPresent(h -> out.add(h.wire()));
        }
        return out;
    }
}
