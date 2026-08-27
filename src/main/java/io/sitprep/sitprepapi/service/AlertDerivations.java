package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The four facts the alert surfaces need that the wire does not state directly.
 *
 * <h2>Why these are DERIVED and not columns</h2>
 *
 * <p>The design package proposed adding {@code geometry_confidence},
 * {@code area_label}, {@code lifecycle_state} and {@code superseded_by_id} to an
 * {@code alerts} table, and the frontend was told to render nothing until those
 * columns landed. <b>There is no alerts table.</b> Alerts are fetched live from
 * NWS, USGS and NIFC every session and never persisted — only the
 * {@code alert_post} junction record is, and that tracks auto-posts rather than
 * alerts.</p>
 *
 * <p>So the columns could never have landed as designed, and every one of the
 * four is computable from a field {@link NormalizedAlert} <em>already carries</em>.
 * Deriving them is not a shortcut around the schema: it is the schema. A stored
 * copy of a value computed from a live feed is a value that can go stale against
 * its own source.</p>
 *
 * <h2>Each rule, and what it refuses to claim</h2>
 *
 * <p>Every method here returns the weakest true answer rather than the most
 * useful one. That is the standing rule on this surface — a block with no field
 * renders nothing, and a qualifier that is always present stops meaning
 * anything.</p>
 */
public final class AlertDerivations {

    private AlertDerivations() {}

    /** {@code exact} — a real polygon. The only value allowed to claim containment. */
    public static final String CONFIDENCE_EXACT = "exact";
    /** {@code region} — zone or county codes: real, but coarser than the hazard. */
    public static final String CONFIDENCE_REGION = "region";
    /** {@code none} — no drawable extent at all. Still the majority case. */
    public static final String CONFIDENCE_NONE = "none";

    public static final String LIFECYCLE_ACTIVE = "active";
    public static final String LIFECYCLE_UPDATED = "updated";
    public static final String LIFECYCLE_SUPERSEDED = "superseded";
    public static final String LIFECYCLE_EXPIRED = "expired";

    /**
     * How precisely this alert knows where it applies.
     *
     * <p>Measured on the live feed 2026-08-22: <b>254 of 310 active alerts carry
     * no polygon, and 100% of those carry UGC zone codes.</b> That is not a
     * degraded state — NWS uses polygons for short-fuse storm-based products and
     * zone codes for long-duration area-wide ones, so a Red Flag Warning has no
     * polygon by design. Treating null geometry as "location unknown"
     * mislabelled every Extreme Heat, Red Flag and Flood Watch in the country.</p>
     *
     * <p>Three values, one field, no interpolation. The frontend's edge treatment
     * reads exactly this: a hard edge for {@code exact}, a dashed edge for
     * {@code region}, no edge at all for {@code none}.</p>
     */
    public static String geometryConfidence(NormalizedAlert a) {
        if (a == null) return CONFIDENCE_NONE;
        if (a.geometry() != null) return CONFIDENCE_EXACT;
        boolean hasZone = (a.ugc() != null && !a.ugc().isEmpty())
                || (a.same() != null && !a.same().isEmpty());
        return hasZone ? CONFIDENCE_REGION : CONFIDENCE_NONE;
    }

    /**
     * Whether this alert may claim that a point is INSIDE it.
     *
     * <p>Gated on {@code exact} and nothing else. Against a county extent the
     * strongest true sentence is "your home is in Uintah County"; against no
     * extent there is no sentence at all, because a radial with no edge cannot
     * contain anything.</p>
     */
    public static boolean canClaimContainment(NormalizedAlert a) {
        return CONFIDENCE_EXACT.equals(geometryConfidence(a));
    }

    /**
     * A human area name, or null.
     *
     * <p>NWS {@code areaDesc} is a semicolon-joined list that routinely runs to
     * a dozen counties — "Uintah; Duchesne; Daggett; Carbon; …". Rendering the
     * whole string in a one-line slot truncates to something that reads like one
     * place's name, which is the failure the label budget on the map is also
     * about. So: the FIRST named area, plus a count of the rest, and null rather
     * than a guess when the field is blank.</p>
     *
     * <p>This is deliberately not a "prettier" label. It is the same fact, cut
     * where a reader can see that it was cut.</p>
     */
    public static String areaLabel(NormalizedAlert a) {
        if (a == null || a.area() == null) return null;
        String area = a.area().trim();
        if (area.isEmpty()) return null;
        String[] parts = area.split(";");
        String lead = parts[0].trim();
        if (lead.isEmpty()) return null;
        int more = 0;
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].trim().isEmpty()) more++;
        }
        if (more == 0) return lead;
        return lead + " +" + more + (more == 1 ? " more area" : " more areas");
    }

    /**
     * Where this alert is in its own life.
     *
     * <p>Four values, and <b>{@code active} is the absence of a chip</b> on the
     * surface — a chip on every card spends the slot that makes the three
     * exceptional states readable.</p>
     *
     * <p>Read in priority order, because they are not mutually exclusive on the
     * wire: an expired Update is expired first. CAP gives us exactly what is
     * needed and no more — {@code messageType} distinguishes an Update from the
     * original, {@code response: AllClear} and {@code messageType: Cancel} both
     * mean called off, and {@code expires} in the past means over.</p>
     *
     * <p><b>Supersession is only as good as the wire.</b> CAP carries a
     * {@code references} field naming the message this one replaces, and ingest
     * does not currently keep it — so a superseded alert is recognised by its
     * own cancellation, never by the arrival of its replacement. That means the
     * "Replaced by …" link the design draws <b>cannot be built yet</b>, and the
     * honest thing is that this method never returns a title for it.</p>
     */
    public static String lifecycleState(NormalizedAlert a, Instant now) {
        if (a == null) return LIFECYCLE_ACTIVE;
        Instant when = now == null ? Instant.now() : now;

        Instant ends = parseInstant(a.endsAt());
        if (ends != null && ends.isBefore(when)) return LIFECYCLE_EXPIRED;

        String messageType = lower(a.messageType());
        String response = lower(a.response());
        if ("cancel".equals(messageType) || "allclear".equals(response)) {
            return LIFECYCLE_SUPERSEDED;
        }
        if ("update".equals(messageType)) return LIFECYCLE_UPDATED;
        return LIFECYCLE_ACTIVE;
    }

    /**
     * The supersession edge, in the only direction the wire supports.
     *
     * <h3>Measured before it was built</h3>
     *
     * <p>Live NWS feed, 2026-08-26, 252 active alerts:</p>
     * <ul>
     *   <li><b>73 carry {@code references}</b> (29%), and all 73 are
     *       {@code messageType: Update}.</li>
     *   <li><b>0 of the 99 referenced identifiers resolved inside the same
     *       response.</b></li>
     * </ul>
     *
     * <p>The zero is not a gap to close — it is structural.
     * {@code /alerts/active} returns only alerts that are still active, and an
     * alert that has been replaced is by definition no longer one. The old
     * message is gone from the feed the moment the new one arrives.</p>
     *
     * <h3>What that means for the design</h3>
     *
     * <p>The mock draws "Replaced by Flash flood warning" on the OLD, ended
     * card. That is the reverse edge, and it needs a card we do not have. The
     * forward edge — "this updates an earlier alert" on the message we DO have
     * — is the same fact, drawable today, and is what {@code replacesAlertIds}
     * carries.</p>
     *
     * <p>This index exists for the case where both ends ARE present, which is
     * rare but real: a snapshot assembled while a cancellation is still in the
     * active set. It returns an empty map the rest of the time, and an empty
     * map renders nothing. That is the honest state, not a failure.</p>
     *
     * <p><b>The title is JOINED, never stored</b> — {@code BLOCKED.md} §5 is
     * explicit that a stored {@code superseded_by_title} lets a retitled
     * successor leave a stale string behind. Here it is computed per response
     * from the live snapshot, so it cannot go stale.</p>
     *
     * @param alerts the snapshot being served
     * @return replaced-alert id -&gt; the alert that replaced it
     */
    public static Map<String, NormalizedAlert> supersessionIndex(List<NormalizedAlert> alerts) {
        if (alerts == null || alerts.isEmpty()) return Map.of();
        Map<String, NormalizedAlert> byId = new HashMap<>();
        for (NormalizedAlert a : alerts) {
            if (a != null && a.id() != null) byId.put(a.id(), a);
        }
        Map<String, NormalizedAlert> out = new HashMap<>();
        for (NormalizedAlert a : alerts) {
            if (a == null || a.references() == null) continue;
            for (String replacedId : a.references()) {
                if (replacedId == null || replacedId.isBlank()) continue;
                // Only when the replaced alert is actually in this snapshot.
                // Naming an id we cannot resolve would put a link on screen
                // that goes nowhere.
                if (byId.containsKey(replacedId)) out.put(replacedId, a);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * A human label for the alert that replaced this one, or null.
     *
     * <p>Null both when nothing replaced it and when the replacement is not in
     * the snapshot. Those are different facts, and the surface treats them the
     * same way — no chip — because neither supports a claim.</p>
     */
    public static String supersededByTitle(NormalizedAlert replacedBy) {
        if (replacedBy == null) return null;
        String event = replacedBy.event();
        if (event != null && !event.isBlank()) return event;
        String headline = replacedBy.headline();
        return (headline == null || headline.isBlank()) ? null : headline;
    }

    /**
     * The attribution line for SitPrep-authored guidance.
     *
     * <p>Two claims in one sentence, and both are load-bearing: the steps are
     * OURS, and the issuing office did not write them. The office is still named
     * — it is the reason the alert exists — but as the source of the alert, not
     * of the advice.</p>
     */
    public static String guidanceAttribution(NormalizedAlert a) {
        String event = a == null ? null : a.event();
        String issuer = issuerOf(a);
        String subject = (event == null || event.isBlank()) ? "this alert" : event;
        if (issuer == null) {
            return "SitPrep guidance for " + subject + " · not the issuer's wording";
        }
        return "SitPrep guidance for " + subject + ", on an alert from " + issuer
                + " · not the issuer's wording";
    }

    /** The office or agency behind the alert, for the "powered by" line. */
    public static String issuerOf(NormalizedAlert a) {
        if (a == null) return null;
        String source = a.source() == null ? "" : a.source().toUpperCase(Locale.ROOT);
        return switch (source) {
            case "NWS" -> "the National Weather Service (NOAA)";
            case "USGS" -> "the U.S. Geological Survey";
            case "NIFC" -> "the National Interagency Fire Center";
            case "FEMA" -> "FEMA";
            default -> null;
        };
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(iso).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** Null-safe, immutable, and empty becomes null so the surface can branch on absence. */
    public static List<String> orNull(List<String> steps) {
        return (steps == null || steps.isEmpty()) ? null : List.copyOf(steps);
    }
}
