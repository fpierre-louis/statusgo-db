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

        /**
         * The WHAT TO DO steps: two or three short imperatives, action first,
         * rendered as a numbered list.
         *
         * <p><b>Ours, and the surface says so</b> — see {@link #precautionsSource}.
         * Sourced from {@code alert-dispatch-templates.json}, matched on the
         * exact NWS product name. <b>Null when no template covers the product</b>,
         * and null renders nothing: generic safety advice reads as specific to
         * the incident, which is worse than an honest gap. The issuing office's
         * own instruction stays verbatim in {@link Official#instruction} under
         * its own heading.</p>
         */
        List<String> precautions,

        /**
         * The attribution line for {@link #precautions} — who wrote the steps,
         * and who issued the alert they are about. Null exactly when
         * {@code precautions} is null, so a caller cannot render one without
         * the other.
         */
        String precautionsSource,

        /**
         * {@code active} | {@code updated} | {@code superseded} | {@code expired},
         * derived at serve time from CAP {@code messageType}, {@code response}
         * and {@code expires}.
         *
         * <p><b>{@code active} is the absence of a chip</b> on the surface. Four
         * values, three chips — a chip on every card spends the slot that makes
         * the three exceptional states readable.</p>
         */
        String lifecycleState,

        /**
         * CAP {@code references} — the identifiers of the alerts this message
         * REPLACES. Empty for the original of a series and for every non-NWS
         * source.
         *
         * <p>This is the forward edge, and it is the one the wire actually
         * supports. Measured 2026-08-26: 29% of live alerts carry it, and 0%
         * of the identifiers resolve inside the same response — because
         * {@code /alerts/active} returns only active alerts and a replaced one
         * is no longer active. So a client can honestly say <i>"this updates an
         * earlier alert"</i>; it cannot resolve that earlier alert's title
         * without a history this service does not keep.</p>
         */
        List<String> replacesAlertIds,

        /**
         * The alert that replaced THIS one, when both ends happen to be in the
         * same snapshot — {@code {id, title}} — and null otherwise.
         *
         * <p>Rare but real: a snapshot assembled while a cancellation is still
         * in the active set. Null the rest of the time, and null renders
         * nothing rather than a link that goes nowhere.</p>
         *
         * <p>The title is <b>joined per response, never stored</b>: a stored
         * copy lets a retitled successor leave a stale string behind.</p>
         */
        SupersededBy supersededBy,

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
    /** The replacement edge, resolved. Both fields non-null or the record is. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupersededBy(String alertId, String title) {}

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

            /**
             * How precisely the ALERT knows its own extent:
             * {@code exact} | {@code region} | {@code none}.
             *
             * <p>Distinct from {@link #matchType}, and the difference matters.
             * {@code matchType} is how this alert matched <em>you</em>;
             * {@code confidence} is what the alert knows about <em>itself</em>.
             * An alert can carry a real polygon and still have matched you by
             * zone. The map's edge treatment reads this field: a hard edge for
             * {@code exact}, a dashed edge for {@code region}, no edge for
             * {@code none}.</p>
             */
            String confidence,

            /**
             * A human area name, cut where a reader can see it was cut —
             * "Uintah +4 more areas". Null rather than a guess when the wire
             * says nothing.
             */
            String areaLabel,
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
