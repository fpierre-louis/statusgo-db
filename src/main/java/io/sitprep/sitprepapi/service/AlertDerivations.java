package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.Vtec;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
 * alerts. ({@code alert_history}, added later, records each message's payload
 * for the history page; it is a log of what the wire said, not a home for these
 * derived values, which are still computed at serve time from that payload.)</p>
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
     * {@code cancelled} — a Cancel (or an all-clear) naming this alert exists.
     * Read off the replacing message, never off this one; see
     * {@link #endingStateOf}.
     */
    public static final String LIFECYCLE_CANCELLED = "cancelled";
    /**
     * {@code no_longer_listed} — history only: a successful poll since this
     * alert was last seen no longer carried it. The issuer did not say it was
     * cancelled, so neither do we.
     */
    public static final String LIFECYCLE_NO_LONGER_LISTED = "no_longer_listed";

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
     * Where this alert is in its own life, judged from <b>its own fields only</b>.
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
     * <p><b>Except where NWS says more in VTEC (P1, handoff §27).</b> NWS ships
     * the end of a warning as a CAP {@code Update} whose own P-VTEC is
     * {@code CAN} or {@code EXP}, still carrying the original end time — so by
     * CAP shape alone it read {@code updated}, and the safety readers kept it
     * live. {@link #vtecEndingState} reads that statement; it sits after the
     * CAP Cancel / AllClear answer (unchanged) and before {@code updated}.</p>
     *
     * <p><b>This is not the whole answer for a card.</b> An alert cannot see its
     * own replacement: CAP {@code references} live on the NEW message, naming
     * the old one. Ingest keeps that edge, and the callers that hold both ends
     * apply it on top of this — the live feed through {@link #supersessionIndex}
     * when the replacement shares the snapshot, history through
     * {@link #reverseEdgeIndex} over stored rows. Either one outranks this
     * method's {@code active}, and both read the replacing message through
     * {@link #endingStateOf}.</p>
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
        String vtecEnding = vtecEndingState(a);
        if (vtecEnding != null) return vtecEnding;
        if ("update".equals(messageType)) return LIFECYCLE_UPDATED;
        return LIFECYCLE_ACTIVE;
    }

    /**
     * VTEC actions that end, for that event, the event their entry names:
     * {@code CAN} cancels, {@code EXP} expires, and {@code UPG} — on the OLD
     * product's own entry — hands the event to the product that upgraded it.
     */
    static final Set<String> TERMINAL_VTEC = Set.of("CAN", "EXP", "UPG");

    /**
     * How this message's OWN P-VTEC ends it, or null when it does not.
     *
     * <p><b>Every entry must be terminal.</b> One NWS product can speak for
     * several events (segments, or an upgrade that also issues the new
     * warning: {@code UPG} on the watch plus {@code NEW} on the warning). While
     * any entry still carries an event on — {@code NEW}, {@code CON},
     * {@code EXT}, {@code EXA}, {@code EXB}, or anything else — the message is
     * still in force for that event, so this returns null and nothing changes.
     * A message with no operational VTEC (USGS, FEMA, most non-NWS products,
     * test-class strings, which {@link Vtec#parse} drops) also returns null.</p>
     *
     * <p>When every entry is terminal, the first one names the state —
     * {@code CAN} cancelled, {@code EXP} expired, {@code UPG} superseded — the
     * same entry {@code AlertInterpreter.primaryVtec} picks, so this and
     * {@code lifecycleInfo} give one answer.</p>
     */
    public static String vtecEndingState(NormalizedAlert a) {
        if (a == null || a.vtec() == null || a.vtec().isEmpty()) return null;
        Vtec first = null;
        for (Vtec v : a.vtec()) {
            if (v == null) continue;
            if (v.action() == null || !TERMINAL_VTEC.contains(v.action())) return null;
            if (first == null) first = v;
        }
        if (first == null) return null;
        return switch (first.action()) {
            case "CAN" -> LIFECYCLE_CANCELLED;
            case "EXP" -> LIFECYCLE_EXPIRED;
            default -> LIFECYCLE_SUPERSEDED;
        };
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
     * What a message that names an earlier alert in its {@code references} does
     * to that alert: {@code cancelled} when the naming message is a Cancel or
     * carries an all-clear, {@code superseded} otherwise.
     *
     * <p>Read off structured CAP fields only. A Cancel is the issuer calling the
     * alert off; any other replacement is the issuer saying "this is the current
     * version" — over, but not called off.</p>
     */
    public static String endingStateOf(NormalizedAlert replacedBy) {
        if (replacedBy == null) return LIFECYCLE_SUPERSEDED;
        if ("cancel".equals(lower(replacedBy.messageType()))) return LIFECYCLE_CANCELLED;
        if ("allclear".equals(lower(replacedBy.response()))) return LIFECYCLE_CANCELLED;
        if (replacedBy.responseTypes() != null) {
            for (String r : replacedBy.responseTypes()) {
                if ("allclear".equals(lower(r))) return LIFECYCLE_CANCELLED;
            }
        }
        return LIFECYCLE_SUPERSEDED;
    }

    /**
     * The reverse edge over a set of messages that need not share a snapshot.
     *
     * <p>The live index above can only join what one {@code /alerts/active}
     * response holds, which is almost nothing. History holds every message it
     * recorded, so there the edge is real: an Update recorded on Tuesday names
     * the Monday alert it replaced. This reads that edge from messages already
     * in memory — the caller's loaded rows plus the live snapshot — so it costs
     * no query at all.</p>
     *
     * <p>Only a real, public message may end another one. A drill naming a live
     * warning must not mark it cancelled; neither may an {@code Ack} or
     * {@code Error}, which are protocol, not issuer statements. When several
     * messages name the same alert, {@code cancelled} wins: once called off it
     * stays called off, whatever the order the rows came back in.</p>
     *
     * @return replaced-alert id -&gt; {@link #LIFECYCLE_CANCELLED} or
     *         {@link #LIFECYCLE_SUPERSEDED}
     */
    public static Map<String, String> reverseEdgeIndex(Collection<NormalizedAlert> messages) {
        if (messages == null || messages.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (NormalizedAlert m : messages) {
            if (m == null || m.references() == null || m.references().isEmpty()) continue;
            if (!mayEndAnother(m)) continue;
            String state = endingStateOf(m);
            for (String replacedId : m.references()) {
                if (replacedId == null || replacedId.isBlank()) continue;
                if (replacedId.equals(m.id())) continue;
                out.merge(replacedId, state,
                        (was, now) -> LIFECYCLE_CANCELLED.equals(was) ? was : now);
            }
        }
        return Map.copyOf(out);
    }

    private static boolean mayEndAnother(NormalizedAlert m) {
        String status = lower(m.status());
        if (status != null && !status.isEmpty() && !"actual".equals(status)) return false;
        String scope = lower(m.scope());
        if (scope != null && !scope.isEmpty() && !"public".equals(scope)) return false;
        String type = lower(m.messageType());
        return !"ack".equals(type) && !"error".equals(type);
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
