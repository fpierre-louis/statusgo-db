package io.sitprep.sitprepapi.util;

import java.util.List;
import java.util.Locale;

/**
 * The one way to name an official alert across the backend (S2 contract §1).
 *
 * <p><b>Three spellings exist today and this is where they meet.</b> The
 * frontend's {@code alertRecordKey} and this {@link #dedupeKey} are
 * {@code SOURCE:id}; {@code AlertPost.alertId} and every notification
 * {@code referenceId} written by dispatch are {@code SOURCE-id} (see
 * {@link #toLegacyDispatchKey}); and the feed card, the snapshot and the
 * community fold carry the bare upstream id next to a separate source. The
 * canonical lookup, dispatch and the community fold all resolve through
 * {@link #resolve}, so a link written in any of the three forms finds the same
 * alert.</p>
 *
 * <p><b>The upstream id is kept verbatim</b> — case and content; only
 * surrounding whitespace is trimmed, as the frontend's {@code alertRecordKey}
 * does ({@code String(alert.id).trim()}), so both sides mint one key. NWS ids are
 * {@code urn:oid:2.49.0.1.840...} (they contain colons, which is why the source
 * prefix is only recognised before the FIRST colon), USGS ids are lower-case
 * ({@code us7000abc}), FEMA ids contain dashes ({@code DR-4932-WV}). Only the
 * source is normalised, to upper case.</p>
 */
public final class AlertIdentity {

    private AlertIdentity() {}

    /** The verified adapter sources, in the order ingest polls them. */
    public static final List<String> SOURCES = List.of("NWS", "USGS", "FEMA");

    /**
     * {@code upper(source) + ":" + upstreamId}, or null when either half is
     * blank or the source is not one of {@link #SOURCES}. Same formula as the
     * frontend's {@code alertRecordKey}.
     */
    public static String dedupeKey(String source, String upstreamId) {
        String s = knownSource(source);
        if (s == null || isBlank(upstreamId)) return null;
        return s + ":" + upstreamId.trim();
    }

    /**
     * {@code upper(source) + "-" + upstreamId}: the form dispatch persists in
     * {@code AlertPost.alertId} and notification {@code referenceId}. Kept so
     * those stored rows stay joinable; new identity should be the
     * {@link #dedupeKey}.
     */
    public static String toLegacyDispatchKey(String source, String upstreamId) {
        String s = knownSource(source);
        if (s == null || isBlank(upstreamId)) return null;
        return s + "-" + upstreamId.trim();
    }

    /** As {@link #toLegacyDispatchKey(String, String)} from a resolved ref; null unless resolved. */
    public static String toLegacyDispatchKey(Resolved r) {
        return r == null || !r.isResolved() ? null : toLegacyDispatchKey(r.source(), r.upstreamId());
    }

    /** What {@link #resolve} could make of a reference. */
    public enum Status {
        /** Source and upstream id are both known. */
        RESOLVED,
        /** A bare id with no source hint, or a prefix that contradicts the hint. Never guessed. */
        AMBIGUOUS,
        /** Blank, an empty id after a prefix, or an unknown source hint. */
        INVALID
    }

    /**
     * The outcome of {@link #resolve}. {@code source} and {@code upstreamId}
     * are null unless {@link Status#RESOLVED}.
     */
    public record Resolved(Status status, String source, String upstreamId) {
        public boolean isResolved() { return status == Status.RESOLVED; }

        /** {@link AlertIdentity#dedupeKey} of this ref, or null unless resolved. */
        public String dedupeKey() {
            return isResolved() ? AlertIdentity.dedupeKey(source, upstreamId) : null;
        }

        /** {@link AlertIdentity#toLegacyDispatchKey} of this ref, or null unless resolved. */
        public String legacyDispatchKey() {
            return AlertIdentity.toLegacyDispatchKey(this);
        }

        static Resolved of(String source, String upstreamId) {
            return new Resolved(Status.RESOLVED, source, upstreamId);
        }

        static Resolved ambiguous() { return new Resolved(Status.AMBIGUOUS, null, null); }

        static Resolved invalid() { return new Resolved(Status.INVALID, null, null); }
    }

    /**
     * Resolve a reference in any of the three forms.
     *
     * <ol>
     *   <li>{@code SOURCE:id} — the text before the first colon is a known
     *       source ({@code NWS:urn:oid:...}). A bare NWS id starts with
     *       {@code urn:}, which is not a source, so it is not mistaken for this
     *       form.</li>
     *   <li>{@code SOURCE-id} — the legacy dispatch prefix {@code NWS-},
     *       {@code USGS-} or {@code FEMA-}.</li>
     *   <li>A bare id plus {@code sourceHint}.</li>
     * </ol>
     *
     * <p>Source prefixes and hints match case-insensitively. A bare id with no
     * hint is {@link Status#AMBIGUOUS}: an NWS urn, a USGS event id and a FEMA
     * declaration string cannot be told apart safely by shape, and a wrong
     * guess would attach one alert's lifecycle to another. A prefixed ref
     * whose source disagrees with a supplied hint is ambiguous for the same
     * reason.</p>
     */
    public static Resolved resolve(String ref, String sourceHint) {
        if (isBlank(ref)) return Resolved.invalid();
        String trimmed = ref.trim();
        String hint = isBlank(sourceHint) ? null : knownSource(sourceHint);
        if (!isBlank(sourceHint) && hint == null) return Resolved.invalid();

        // 1. SOURCE:id
        int colon = trimmed.indexOf(':');
        if (colon > 0) {
            String prefixed = knownSource(trimmed.substring(0, colon));
            if (prefixed != null) {
                return withPrefix(prefixed, trimmed.substring(colon + 1), hint);
            }
        }

        // 2. SOURCE-id
        for (String source : SOURCES) {
            String dash = source + "-";
            if (trimmed.regionMatches(true, 0, dash, 0, dash.length())) {
                return withPrefix(source, trimmed.substring(dash.length()), hint);
            }
        }

        // 3. bare id
        if (hint == null) return Resolved.ambiguous();
        return Resolved.of(hint, trimmed);
    }

    private static Resolved withPrefix(String source, String upstreamId, String hint) {
        if (isBlank(upstreamId)) return Resolved.invalid();
        if (hint != null && !hint.equals(source)) return Resolved.ambiguous();
        return Resolved.of(source, upstreamId.trim());
    }

    /** {@code source} upper-cased when it is one of {@link #SOURCES}, else null. */
    public static String knownSource(String source) {
        if (isBlank(source)) return null;
        String upper = source.trim().toUpperCase(Locale.ROOT);
        return SOURCES.contains(upper) ? upper : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
