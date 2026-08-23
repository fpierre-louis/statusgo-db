package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One alert, shaped for a card.
 *
 * <p>The handoff artifact for the frontend hazard redesign, and the place the
 * whole Part-1 remediation surfaces. Every field here exists because Phase 1
 * found the frontend deriving it badly, or not at all, from raw wire text.</p>
 *
 * <h2>The one rule that governs the shape</h2>
 *
 * <p><b>{@link #headline} and {@link #whatToDo} are ours; {@link #official} is
 * theirs, and they never mix.</b> The plain-language copy is authored in
 * {@code alert-dispatch-templates.json}, measured at Flesch-Kincaid grade
 * 0.3–7.6, and routed through {@code sanitizeSlots}. The official block is the
 * NWS wire text verbatim — product codes, issuing office, teletype line-breaks
 * and all — carried deliberately so a reader can see the source, and never
 * used as a fallback for the plain copy. That last part is not hypothetical:
 * the first version of {@code sanitizeSlots} fell back to the wire headline
 * and had to be corrected (P0-10).</p>
 *
 * <h2>Nothing here is re-derived</h2>
 *
 * <p>{@code tier}, {@code isLifeThreatening} and {@code evacuationRelated} are
 * passed through from what the dispatch pipeline already computed in P0-2 and
 * P0-3. Recomputing them at the DTO boundary would be a fifth place that knows
 * how to tell a Watch from a Warning, and the epic exists because there were
 * already too many.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AlertCardDto(

        String id,

        /** {@code nws} | {@code usgs} | {@code fema}. Lowercase on the wire. */
        String source,

        /**
         * The upstream product name, verbatim — {@code "Extreme Heat Warning"}.
         * A stable key the client can branch on; not for display.
         */
        String eventType,

        /**
         * What to call it on screen — our template headline
         * ({@code "Dangerous heat"}) when one exists, else {@link #eventType}.
         */
        String eventLabel,

        /**
         * {@code warning} | {@code watch} | {@code advisory} | {@code statement}
         * | {@code information}.
         *
         * <p>Drives the visual language. Phase 1's P0-3 was a text-matching bug
         * <i>precisely because</i> this distinction lived only inside a raw
         * title string; it is a field now so the frontend never has to read a
         * headline to find out.</p>
         */
        String tier,

        /** True only for warning-tier alerts that passed the P0-3 shape check. */
        boolean isLifeThreatening,

        /**
         * True for the civil products that carry an evacuation or
         * shelter-in-place instruction. Coverage is inconsistent by
         * jurisdiction — see {@code AlertFeedResponse.Meta#coverageCaveat}.
         */
        boolean evacuationRelated,

        /** Our plain-language headline. Never the wire headline. */
        String headline,

        /** Our plain-language action copy. Never the wire text. */
        String whatToDo,

        Official official,

        Location location,

        /** The match radius, from the single {@code AppConfig} value. */
        int radiusMi,

        String effectiveAt,

        /**
         * When the alert stops being in effect, or <b>null when the issuer did
         * not say</b>.
         *
         * <p>Null is a distinct state from expired and must stay that way.
         * USGS earthquakes have no end; so do some group-authored alerts.
         * Collapsing null to an empty string or epoch-zero would make "we don't
         * know when this ends" indistinguishable from "this ended in 1970",
         * and the expiry gate (P0-1) reads exactly this field.</p>
         */
        String expiresAt,

        /**
         * Attribution required by the source's licence, ready to render.
         * Phase 1's P2-4 found NIFC and Overpass credited nowhere.
         */
        String sourceAttribution,

        Detail detail
) {

    /**
     * The upstream text, verbatim and unprocessed.
     *
     * <p><b>Never populated from anything routed through
     * {@code sanitizeSlots}.</b> This block's whole value is being the raw
     * record — the frontend puts it behind an "official wording" disclosure.
     * If our processing leaked into it there would be no raw record left.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Official(
            String headline,
            String description,
            String instruction,
            /** Issuing office, e.g. {@code "NWS Phoenix AZ"}. Null when unknown. */
            String issuedBy
    ) {}

    /** Where this applies, and how confidently we know that. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(
            /** Human-readable area, e.g. {@code "Cuyahoga, Geauga, Lake"}. */
            String label,
            /**
             * {@code polygon} | {@code zone} | {@code state_prefix} |
             * {@code broadcast} — how the alert was matched to the viewer.
             *
             * <p>Not decoration. A polygon match covers your street; a
             * state_prefix match means the zone lookup failed and this is
             * "somewhere in your state"; broadcast means we could not tell at
             * all. Rendering the three identically is how a nationwide FEMA
             * row reads as local news, which is what Phase 1 measured.</p>
             */
            String matchType,
            /** A specific place when the source names one, e.g. USGS's epicentre. */
            String place
    ) {}

    /** Source-specific extras. Absent unless the source provides them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(Earthquake earthquake, Disaster disaster) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Earthquake(
            Double magnitude,
            Double depthKm,
            /** USGS PAGER impact colour: green | yellow | orange | red. */
            String pagerLevel,
            boolean tsunami
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Disaster(
            String disasterNumber,
            String declaredAt,
            /** e.g. {@code ["Individual Assistance", "Public Assistance"]}. */
            List<String> programsAvailable
    ) {}
}
