package io.sitprep.sitprepapi.util;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * The SHA-256 fingerprint of one source revision of an alert, and the
 * {@code sourceRevisionRef} built from it (S2 contract §1).
 *
 * <p><b>What it is for.</b> NWS issues a new id per message, so its id alone
 * names a revision. USGS and FEMA do not: a quake's magnitude is revised under
 * the same id, and a declaration gains counties under the same string. The
 * fingerprint is what tells "the same payload polled again" (same ref, no new
 * row, no new notification) from "the issuer changed it" (new ref).</p>
 *
 * <p><b>What it covers</b> is the contract's list, in a fixed order: source,
 * upstream id, messageType, status, event, severity, urgency, certainty,
 * responseTypes (sorted), the official headline/description/instruction,
 * areaDesc, sorted UGC, sorted SAME, the timing fields, the geometry as
 * canonical JSON, the USGS magnitude/PAGER/tsunami, the FEMA declaration
 * fields and each reference's identifier (sorted). A field a source does not
 * have contributes {@code ""}.</p>
 *
 * <p><b>Deliberately left out:</b> {@code sent} and USGS {@code updated} (a
 * re-stamp with nothing changed is not a new revision), and every SitPrep-side
 * value (template, policy, copy) — those belong to the interpretation snapshot,
 * whose own fingerprint layers on top of this one.</p>
 *
 * <p>Each field is written as {@code name=<length>:<value>} on its own line,
 * so no value can be shifted into its neighbour by containing a separator.</p>
 */
public final class AlertSourceFingerprint {

    private AlertSourceFingerprint() {}

    /** Hex characters of the fingerprint kept in a revision ref. */
    public static final int REF_HEX_CHARS = 12;

    /** Sorted keys at every level, so upstream key order cannot change the hash. */
    private static final ObjectMapper CANONICAL_JSON = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    /** 64 lower-case hex characters; null for a null alert. */
    public static String fingerprint(NormalizedAlert a) {
        if (a == null) return null;
        return sha256Hex(canonical(a));
    }

    /**
     * {@code dedupeKey + "@" + fp12} — the first {@link #REF_HEX_CHARS} hex
     * characters of {@code fingerprint}. Null when either part is missing.
     */
    public static String revisionRef(String dedupeKey, String fingerprint) {
        if (dedupeKey == null || dedupeKey.isBlank() || fingerprint == null
                || fingerprint.length() < REF_HEX_CHARS) {
            return null;
        }
        return dedupeKey + "@" + fingerprint.substring(0, REF_HEX_CHARS);
    }

    /** {@link #revisionRef(String, String)} of an alert's own dedupe key and fingerprint. */
    public static String revisionRef(NormalizedAlert a) {
        if (a == null) return null;
        return revisionRef(AlertIdentity.dedupeKey(a.source(), a.id()), fingerprint(a));
    }

    /** The exact string hashed. Package-private so a test can see what changed. */
    static String canonical(NormalizedAlert a) {
        StringBuilder sb = new StringBuilder(1024);
        field(sb, "source", a.source() == null ? null : a.source().toUpperCase(java.util.Locale.ROOT));
        field(sb, "upstreamId", a.id());
        field(sb, "messageType", a.messageType());
        field(sb, "status", a.status());
        field(sb, "event", a.event());
        field(sb, "severity", a.severity());
        field(sb, "urgency", a.urgency());
        field(sb, "certainty", a.certainty());
        list(sb, "responseTypes", a.responseTypes());
        field(sb, "headline", a.headline());
        field(sb, "description", a.description());
        field(sb, "instruction", a.instruction());
        field(sb, "areaDesc", a.area());
        list(sb, "ugc", a.ugc());
        list(sb, "same", a.same());
        // Timing: the four raw CAP fields, plus the collapsed pair — the only
        // carrier of USGS origin time and the FEMA incident begin.
        field(sb, "effective", a.effective());
        field(sb, "onset", a.onset());
        field(sb, "expires", a.expires());
        field(sb, "ends", a.ends());
        field(sb, "startedAt", a.startedAt());
        field(sb, "endsAt", a.endsAt());
        field(sb, "geometry", geometryJson(a.geometry()));
        field(sb, "magnitude", a.magnitude() == null
                ? null
                : BigDecimal.valueOf(a.magnitude()).stripTrailingZeros().toPlainString());
        field(sb, "pagerAlert", a.pagerAlert());
        field(sb, "tsunami", a.tsunamiFlag() == null ? null : a.tsunamiFlag().toString());
        field(sb, "declaredAt", a.declaredAt());
        field(sb, "incidentEndsAt", a.incidentEndsAt());
        list(sb, "references", referenceIds(a));
        return sb.toString();
    }

    /** Identifiers from the tuples when present, else the identifiers-only view. */
    private static List<String> referenceIds(NormalizedAlert a) {
        if (a.referenceTuples() != null && !a.referenceTuples().isEmpty()) {
            List<String> ids = new ArrayList<>(a.referenceTuples().size());
            for (CapReference r : a.referenceTuples()) if (r != null) ids.add(r.identifier());
            return ids;
        }
        return a.references();
    }

    private static String geometryJson(Object geometry) {
        if (geometry == null) return null;
        try {
            return CANONICAL_JSON.writeValueAsString(geometry);
        } catch (Exception e) {
            // Unserialisable geometry is not a reason to fail an ingest tick;
            // its string form still distinguishes most changes.
            return String.valueOf(geometry);
        }
    }

    private static void field(StringBuilder sb, String name, String value) {
        String v = value == null ? "" : value;
        sb.append(name).append('=').append(v.length()).append(':').append(v).append('\n');
    }

    private static void list(StringBuilder sb, String name, Collection<String> values) {
        List<String> sorted = new ArrayList<>();
        if (values != null) for (String v : values) sorted.add(v == null ? "" : v);
        sorted.sort(null);
        StringBuilder joined = new StringBuilder();
        for (String v : sorted) joined.append(v.length()).append(':').append(v).append(';');
        field(sb, name, joined.toString());
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // every JRE ships it
        }
    }
}
