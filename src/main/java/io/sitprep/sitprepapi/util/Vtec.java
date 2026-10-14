package io.sitprep.sitprepapi.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One NWS P-VTEC string, parsed — {@code /O.CON.KSLC.FF.W.0042.261013T1800Z-261014T0000Z/}.
 *
 * <p><b>Why it matters.</b> VTEC is how NWS itself links the messages of one
 * event: the same office + phenomena + significance + event tracking number
 * (ETN) in a given year is the same hazard, and the action code says what this
 * message does to it — {@code CON} continues, {@code EXT} extends, {@code CAN}
 * cancels, {@code EXP} expires, {@code UPG} upgrades. That is an issuer-stated
 * edge, so it counts as a <b>verified</b> lifecycle link (S2 contract §2),
 * unlike anything inferred from poll absence. Measured on the 2026-08-22 feed:
 * 277 of 310 alerts carry one, every one operational ({@code O}).</p>
 *
 * <p><b>Times are ISO-8601 strings, not {@link Instant}s.</b> This record rides
 * inside {@code NormalizedAlert}, which {@code AlertHistoryService} round-trips
 * through a plain {@code ObjectMapper} with no java.time module; an
 * {@code Instant} component would break every history write. VTEC's
 * {@code 000000T0000Z} ("not specified" — how a CON says "already in effect")
 * is null.</p>
 *
 * <p><b>Malformed input is ignored, never thrown.</b> A string that does not
 * parse yields {@link Optional#empty()}; a non-operational class ({@code T}est,
 * {@code E}xperimental, {@code X} experimental-in-operational) is skipped too,
 * because a test message must never become a verified lifecycle edge.</p>
 *
 * @param action       {@code NEW | CON | EXT | EXA | EXB | UPG | CAN | EXP | COR | ROU}
 * @param office       issuing office, e.g. {@code KSLC}
 * @param phenomena    two letters, e.g. {@code FF} (flash flood)
 * @param significance one letter: {@code W} warning, {@code A} watch, {@code Y} advisory, {@code S} statement, ...
 * @param etn          event tracking number, zero-padded as sent ({@code 0042})
 * @param beginsAt     ISO-8601, or null when VTEC says "not specified"
 * @param endsAt       ISO-8601, or null when VTEC says "until further notice"
 * @param year         the year the ETN belongs to — see {@link #parse(String, String)}
 */
public record Vtec(
        String action,
        String office,
        String phenomena,
        String significance,
        String etn,
        String beginsAt,
        String endsAt,
        Integer year
) {

    /** The seven-action vocabulary plus COR/ROU, per NWS Directive 10-1703. */
    private static final Pattern P_VTEC = Pattern.compile(
            "^/?([OTEX])\\.(NEW|CON|EXT|EXA|EXB|UPG|CAN|EXP|COR|ROU)\\."
                    + "([A-Z]{4})\\.([A-Z]{2})\\.([A-Z])\\.(\\d{4})\\."
                    + "(\\d{6}T\\d{4}Z)-(\\d{6}T\\d{4}Z)/?$");

    private static final String UNSPECIFIED = "000000T0000Z";

    /**
     * STRICT, so an impossible date (31 Feb, 29 Feb in a common year, minute
     * 60) is malformed rather than clamped to a nearby real one. STRICT reads
     * {@code yy} as year-of-era and needs an era, hence {@code uu}.
     */
    private static final DateTimeFormatter VTEC_TIME =
            DateTimeFormatter.ofPattern("uuMMdd'T'HHmm'Z'", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * {@code office.phenomena.significance.etn.year} — the key every message
     * of one NWS event shares, e.g. {@code KSLC.FF.W.0042.2026}. Null when the
     * year is unknown, because an ETN without its year is not unique.
     */
    public String eventKey() {
        if (year == null) return null;
        return office + "." + phenomena + "." + significance + "." + etn + "." + year;
    }

    /** As {@link #parse(String, String)} with no fallback year. */
    public static Optional<Vtec> parse(String raw) {
        return parse(raw, null);
    }

    /**
     * Parse one P-VTEC string.
     *
     * <p><b>The year.</b> An ETN is unique per office, phenomena and
     * significance within a calendar year. The begin time carries it when set;
     * a CON for an event already in effect codes the begin as
     * {@code 000000T0000Z}, so the year then comes from the message's own
     * {@code sentIso}, and failing that from the end time. Known limit: a CON
     * sent in January for an event issued the previous December gets the new
     * year — NWS publishes no other carrier of the issuance year on the
     * message.</p>
     */
    public static Optional<Vtec> parse(String raw, String sentIso) {
        if (raw == null) return Optional.empty();
        Matcher m = P_VTEC.matcher(raw.trim());
        if (!m.matches()) return Optional.empty();
        if (!"O".equals(m.group(1))) return Optional.empty();   // operational only — see class doc

        Instant begins = vtecTime(m.group(7));
        Instant ends = vtecTime(m.group(8));
        // A coded time that is not a real date (month 13, 31 Feb) is malformed.
        if ((begins == null && !UNSPECIFIED.equals(m.group(7)))
                || (ends == null && !UNSPECIFIED.equals(m.group(8)))) {
            return Optional.empty();
        }

        Integer year = yearOf(begins);
        if (year == null) year = yearOf(isoInstant(sentIso));
        if (year == null) year = yearOf(ends);

        return Optional.of(new Vtec(
                m.group(2), m.group(3), m.group(4), m.group(5), m.group(6),
                begins == null ? null : begins.toString(),
                ends == null ? null : ends.toString(),
                year));
    }

    /** Every parseable P-VTEC in {@code values}, in order; the rest are dropped. */
    public static List<Vtec> parseAll(Collection<String> values, String sentIso) {
        if (values == null || values.isEmpty()) return List.of();
        List<Vtec> out = new ArrayList<>(values.size());
        for (String v : values) parse(v, sentIso).ifPresent(out::add);
        return List.copyOf(out);
    }

    /** The distinct event keys of {@code vtec}, in order; entries with no year are skipped. */
    public static List<String> eventKeysOf(List<Vtec> vtec) {
        if (vtec == null || vtec.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(vtec.size());
        for (Vtec v : vtec) {
            String key = v == null ? null : v.eventKey();
            if (key != null && !out.contains(key)) out.add(key);
        }
        return List.copyOf(out);
    }

    private static Instant vtecTime(String coded) {
        if (coded == null || UNSPECIFIED.equals(coded)) return null;
        try {
            return LocalDateTime.parse(coded, VTEC_TIME).toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant isoInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return java.time.OffsetDateTime.parse(iso).toInstant(); }
        catch (Exception ignored) { }
        try { return Instant.parse(iso); }
        catch (Exception ignored) { return null; }
    }

    private static Integer yearOf(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC).getYear();
    }
}
