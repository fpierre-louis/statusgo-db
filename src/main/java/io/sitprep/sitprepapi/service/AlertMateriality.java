package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.Vtec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * R14 materiality (S2 contract §5): is a new message in a thread worth a new
 * inbox row and a push, or does the thread's lifecycle just move on through
 * the live projection?
 *
 * <p>NWS re-issues a warning every time a forecaster touches it — a
 * {@code CON} with a new CAP identifier and the same everything, often more
 * than once an hour. Each used to be a brand-new alert to the dispatcher, so
 * each one pushed again. The rule here compares the new message with what the
 * thread last DELIVERED, field by field, and only these count:</p>
 * <ul>
 *   <li>the movement directive, the template tier, the dispatch mode;</li>
 *   <li>CAP severity or urgency;</li>
 *   <li>{@code ends} or {@code expires} moving by more than {@link #TIME_SLACK}
 *       (or appearing / disappearing);</li>
 *   <li>the area — the UGC set, or the geometry's fingerprint;</li>
 *   <li>a verified cancel or supersede by the issuer (VTEC {@code CAN}/{@code UPG},
 *       CAP {@code Cancel}, an {@code AllClear});</li>
 *   <li>USGS: magnitude by {@link #MAGNITUDE_STEP} or more, the PAGER level,
 *       the tsunami flag.</li>
 * </ul>
 *
 * <p><b>What never counts.</b> SitPrep's own copy — a template's headline,
 * body or steps — is not a fact about the hazard, so editing it can never push
 * anyone again: {@link Facts} does not read it. The decision fields come from
 * the policy, and the policy reads the template's reviewed metadata, not its
 * wording.</p>
 *
 * <p><b>USGS geometry is not "the area".</b> A quake's geometry is its
 * epicentre, and USGS refines it in place for hours. Counting that as an area
 * change would bring back the second push of one quake that the dispatcher's
 * once-per-quake rule closed on 2026-10-07; the magnitude, PAGER and tsunami
 * rules carry the USGS changes that matter to a person. (Contract §5 lists
 * the geometry fingerprint without a source caveat; this is the recorded
 * deviation.)</p>
 *
 * <p>Pure: no I/O, no clock. Nothing here is AI and nothing is inferred from
 * wording.</p>
 */
final class AlertMateriality {

    private AlertMateriality() {}

    /** How far {@code ends}/{@code expires} may move before it is news. */
    static final Duration TIME_SLACK = Duration.ofMinutes(15);

    /** The smallest USGS magnitude revision that is news. */
    static final double MAGNITUDE_STEP = 0.3;

    /** Ending kinds {@link #verifiedEndingOf} reports. */
    static final String ENDING_CANCELLED = "cancelled";
    static final String ENDING_SUPERSEDED = "superseded";

    /**
     * The facts about one message that materiality compares. Built only from
     * the issuer's structured fields and the policy's decision.
     *
     * @param dispatchMode      null when the delivered decision is not known —
     *                          then it is left out of the comparison rather
     *                          than guessed
     * @param movementDirective as {@code dispatchMode}
     * @param ending            {@link #ENDING_CANCELLED}, {@link #ENDING_SUPERSEDED} or null
     */
    record Facts(String source,
                 String tier,
                 String dispatchMode,
                 String movementDirective,
                 String severity,
                 String urgency,
                 Instant ends,
                 Instant expires,
                 Set<String> ugc,
                 String geometryFingerprint,
                 Double magnitude,
                 String pagerAlert,
                 Boolean tsunami,
                 String ending) {}

    /**
     * Facts of {@code a} under {@code decision}. {@code decision} may be null
     * (the delivered one was not recorded) — the decision fields are then
     * unknown and excluded from {@link #compare}.
     */
    static Facts of(NormalizedAlert a, DispatchTemplate tpl, AlertSafetyPolicy.Decision decision) {
        return of(a, tpl,
                decision == null || decision.dispatchMode() == null ? null : decision.dispatchMode().wire(),
                decision == null || decision.movementDirective() == null ? null : decision.movementDirective().wire());
    }

    /** As {@link #of(NormalizedAlert, DispatchTemplate, AlertSafetyPolicy.Decision)} from stored decision values. */
    static Facts of(NormalizedAlert a, DispatchTemplate tpl, String dispatchMode, String movementDirective) {
        if (a == null) return null;
        String source = a.source() == null ? null : a.source().trim().toUpperCase(Locale.ROOT);
        boolean usgs = "USGS".equals(source);
        Instant ends = instant(a.ends());
        Instant expires = instant(a.expires());
        // A payload from before the four raw timings (C1) holds only the
        // collapsed end; it stands in for `ends` so two such payloads compare.
        if (ends == null && expires == null) ends = instant(a.endsAt());
        return new Facts(
                source,
                AlertFeedService.tierOf(a, tpl),
                dispatchMode,
                movementDirective,
                trimToNull(a.severity()),
                trimToNull(a.urgency()),
                ends,
                expires,
                upperSet(a.ugc()),
                usgs ? null : geometryFingerprint(a.geometry()),
                usgs ? usgsMagnitude(a) : null,
                usgs ? trimToNull(a.pagerAlert()) : null,
                usgs ? a.tsunamiFlag() : null,
                verifiedEndingOf(a));
    }

    /** The verdict, and every reason for it (empty when not material). */
    record Verdict(boolean material, List<String> reasons) {
        static Verdict of(List<String> reasons) {
            return new Verdict(!reasons.isEmpty(), List.copyOf(reasons));
        }
    }

    /**
     * Is {@code next} material against what the thread last delivered?
     * Every listed change is checked, so the log can say all of them.
     */
    static Verdict compare(Facts delivered, Facts next) {
        List<String> reasons = new ArrayList<>();
        if (delivered == null || next == null) {
            reasons.add("no_delivered_basis");
            return Verdict.of(reasons);
        }
        if (next.ending() != null && !Objects.equals(delivered.ending(), next.ending())) {
            reasons.add("verified_" + next.ending());
        }
        if (known(delivered.movementDirective(), next.movementDirective())
                && !Objects.equals(delivered.movementDirective(), next.movementDirective())) {
            reasons.add("movement_directive");
        }
        if (!Objects.equals(delivered.tier(), next.tier())) reasons.add("tier");
        if (known(delivered.dispatchMode(), next.dispatchMode())
                && !Objects.equals(delivered.dispatchMode(), next.dispatchMode())) {
            reasons.add("dispatch_mode");
        }
        if (!equalsIgnoreCase(delivered.severity(), next.severity())) reasons.add("severity");
        if (!equalsIgnoreCase(delivered.urgency(), next.urgency())) reasons.add("urgency");
        if (moved(delivered.ends(), next.ends())) reasons.add("ends");
        if (moved(delivered.expires(), next.expires())) reasons.add("expires");
        if (!Objects.equals(delivered.ugc(), next.ugc())) reasons.add("area_zones");
        if (!Objects.equals(delivered.geometryFingerprint(), next.geometryFingerprint())) {
            reasons.add("area_geometry");
        }
        if (magnitudeMoved(delivered.magnitude(), next.magnitude())) reasons.add("magnitude");
        if (!equalsIgnoreCase(delivered.pagerAlert(), next.pagerAlert())) reasons.add("pager");
        if (!Objects.equals(delivered.tsunami(), next.tsunami())) reasons.add("tsunami");
        return Verdict.of(reasons);
    }

    /**
     * How the issuer itself ends a message, verified: VTEC {@code CAN} → cancelled,
     * VTEC {@code UPG} → superseded (every entry terminal, as
     * {@link AlertDerivations#vtecEndingState} reads it), CAP {@code Cancel} or
     * an {@code AllClear} response → cancelled. VTEC {@code EXP} is not here: an
     * expiry at the issuer's own time is the schedule running out, not news,
     * and the live projection already shows it.
     */
    static String verifiedEndingOf(NormalizedAlert a) {
        if (a == null) return null;
        if ("cancel".equalsIgnoreCase(trimToNull(a.messageType()))) return ENDING_CANCELLED;
        if ("allclear".equalsIgnoreCase(trimToNull(a.response()))) return ENDING_CANCELLED;
        if (a.responseTypes() != null) {
            for (String r : a.responseTypes()) {
                if ("allclear".equalsIgnoreCase(trimToNull(r))) return ENDING_CANCELLED;
            }
        }
        String vtec = AlertDerivations.vtecEndingState(a);
        if (AlertDerivations.LIFECYCLE_CANCELLED.equals(vtec)) return ENDING_CANCELLED;
        if (AlertDerivations.LIFECYCLE_SUPERSEDED.equals(vtec) && hasAction(a, "UPG")) return ENDING_SUPERSEDED;
        return null;
    }

    private static boolean hasAction(NormalizedAlert a, String action) {
        if (a.vtec() == null) return false;
        for (Vtec v : a.vtec()) {
            if (v != null && action.equals(v.action())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    private static boolean known(String a, String b) {
        return a != null && b != null;
    }

    private static boolean moved(Instant a, Instant b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;     // an end appearing or vanishing is news
        return Duration.between(a, b).abs().compareTo(TIME_SLACK) > 0;
    }

    private static boolean magnitudeMoved(Double a, Double b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        // Rounded to tenths first: USGS reports one decimal, and 6.2 - 5.9 is
        // 0.29999999999999982 in binary.
        long tenths = Math.abs(Math.round(a * 10) - Math.round(b * 10));
        return tenths >= Math.round(MAGNITUDE_STEP * 10);
    }

    /** The parsed magnitude, else the headline's ({@code "M 6.2 - ..."}) as dispatch reads it. */
    private static Double usgsMagnitude(NormalizedAlert a) {
        if (a.magnitude() != null) return a.magnitude();
        return AlertDispatchService.parseUsgsMagnitude(a.headline());
    }

    private static Set<String> upperSet(List<String> values) {
        TreeSet<String> out = new TreeSet<>();
        if (values == null) return out;
        for (String v : values) {
            String t = trimToNull(v);
            if (t != null) out.add(t.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CANONICAL_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** SHA-256 of the geometry's canonical JSON (map keys sorted), or null without one. */
    static String geometryFingerprint(Object geometry) {
        if (geometry == null) return null;
        String json;
        try {
            json = CANONICAL_JSON.writeValueAsString(geometry);
        } catch (Exception e) {
            json = String.valueOf(geometry);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(iso.trim());
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
