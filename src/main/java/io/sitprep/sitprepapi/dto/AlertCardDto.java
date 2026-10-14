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
         * {@code active} | {@code updated} | {@code superseded} | {@code expired}
         * | {@code cancelled} | {@code no_longer_listed}, derived at serve time.
         *
         * <p>The first four come from the alert itself — CAP {@code messageType},
         * {@code response} and {@code expires} (see
         * {@code AlertDerivations.lifecycleState}). The live feed also says
         * {@code superseded} or {@code cancelled} when the message replacing
         * this one is in the same snapshot — or {@code expired}, when that
         * message's NWS VTEC action for this event is {@code EXP}: the issuer
         * let it lapse rather than calling it off (S2 C2, rule 8; see
         * {@link #lifecycleInfo} for the evidence). History adds what only a record can
         * know: a stored Update or Cancel naming this alert, and
         * {@code no_longer_listed} for an alert a later successful poll no longer
         * carried — which is never claimed as {@code cancelled}, because the
         * issuer never said so.</p>
         *
         * <p>Only {@code active} and {@code updated} are in force. Every other
         * value is a record: no SitPrep guidance, no current directive.</p>
         *
         * <p><b>{@code active} is the absence of a chip</b> on the surface — a
         * chip on every card spends the slot that makes the exceptional states
         * readable.</p>
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

        Detail detail,

        /**
         * Shared safety decision used by feed rendering and dispatch. Added at
         * the end so the positional record does not silently shift existing
         * same-typed fields.
         */
        Safety safety,

        // ── S2 C2 additions (contract §3). Every one is nullable and NON_NULL,
        //    appended so the positional record does not shift an existing
        //    field; the 21-argument constructor below keeps every existing
        //    caller compiling and leaves them all null. ──

        /** {@code SOURCE:upstreamId} — {@code AlertIdentity.dedupeKey}, the FE's {@code alertRecordKey}. */
        String dedupeKey,

        /** {@code dedupeKey@fp12} — this exact source revision ({@code AlertSourceFingerprint}). */
        String sourceRevisionRef,

        /** The interpretation snapshot id. Null until persistence (C3) lands. */
        String interpretationRef,

        /** {@code AlertInterpreter.VERSION}: bumped by hand when the interpretation rules change. */
        String presentationVersion,

        SourceIdentity sourceIdentity,

        LifecycleInfo lifecycleInfo,

        /**
         * PRESENTATION ONLY: a key a surface may use to sit related alerts
         * together ("three Flood Watches for neighbouring zones"). It never
         * suppresses, ends or replaces anything; lifecycle lives in
         * {@link #lifecycleInfo} and nowhere else.
         */
        String clusterKey,

        Interpreter interpreter,

        Presentation presentation,

        /** Semantic actions, each with a route an older client can follow. */
        List<Action> actions,

        OfficialArea officialArea,

        ViewerContext viewerContext,

        CommunityThreads communityThreads
) {

    /**
     * The pre-S2 shape: every C2 addition absent. Kept so callers that build a
     * card by hand (tests, fixtures) compile unchanged.
     */
    public AlertCardDto(String id, String source, String eventType, String eventLabel,
                        String tier, boolean isLifeThreatening, boolean evacuationRelated,
                        String headline, String whatToDo, List<String> precautions,
                        String precautionsSource, String lifecycleState,
                        List<String> replacesAlertIds, SupersededBy supersededBy,
                        Official official, Location location, int radiusMi,
                        String effectiveAt, String expiresAt, String sourceAttribution,
                        Detail detail, Safety safety) {
        this(id, source, eventType, eventLabel, tier, isLifeThreatening, evacuationRelated,
                headline, whatToDo, precautions, precautionsSource, lifecycleState,
                replacesAlertIds, supersededBy, official, location, radiusMi,
                effectiveAt, expiresAt, sourceAttribution, detail, safety,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

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
            /**
             * USGS's oceanic-region flag, as sent. NOT a tsunami warning: USGS
             * says it does not indicate whether a tsunami exists or will exist.
             * Tsunami threats arrive as NWS alerts. Never render this as
             * "tsunami threat" and never derive tone or guidance from it.
             */
            boolean tsunami
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Disaster(
            String disasterNumber,
            String declaredAt,
            /** e.g. {@code ["Individual Assistance", "Public Assistance"]}. */
            List<String> programsAvailable
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Safety(
            String dispatchMode,
            String guidanceMode,
            String compatibility,
            List<String> protectiveActions,
            /**
             * `none` for ordinary hazard guidance, or an official movement
             * directive such as `evacuate` / `shelter_in_place` when the
             * issuer or product semantics support that stronger claim.
             */
            String movementDirective,
            String reason,
            /**
             * What the alert said while it was live — the record of the order,
             * never the instruction in force. Equal to {@link #movementDirective}
             * on a live card. On a record (ended, cancelled, superseded, no
             * longer listed, or any history card) {@code movementDirective} is
             * {@code none} and this keeps what was issued, so a surface can say
             * "an evacuation order was issued" without presenting one. Null only
             * on a Safety built without a policy decision.
             */
            String asIssuedMovementDirective
    ) {
        /** Without an as-issued record — for callers that build a Safety by hand. */
        public Safety(String dispatchMode,
                      String guidanceMode,
                      String compatibility,
                      List<String> protectiveActions,
                      String movementDirective,
                      String reason) {
            this(dispatchMode, guidanceMode, compatibility, protectiveActions,
                    movementDirective, reason, null);
        }
    }

    // ------------------------------------------------------------------
    // S2 C2 nested records (contract §3)
    // ------------------------------------------------------------------

    /**
     * Who the alert is from. {@code key}, {@code name} and {@code official}
     * come from {@code TrustedSourceRegistry} — the adapter that fetched the
     * alert, never a payload string. {@code issuedBy} is the issuing office as
     * the issuer names itself ({@code senderName}), shown beside the verified
     * name and never instead of it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceIdentity(
            String key,
            String name,
            boolean official,
            String issuedBy,
            /** warning | watch | advisory | statement | information | earthquake_information | disaster_declaration */
            String recordType,
            /** Always null until permission to show an agency mark is documented. */
            String logoKey
    ) {}

    /**
     * Where the alert is in its life, with the evidence for it.
     *
     * <p>{@code state} equals the card's {@code lifecycleState} except where
     * the issuer's VTEC says more than the shared CAP derivation can: an own
     * {@code CAN}/{@code EXP} on an Update, a replacing message's {@code EXP},
     * a {@code CON} on a CAP Alert, or a history row "no longer listed" whose
     * own VTEC already ended it. {@code lifecycleState} stays the shared
     * {@code AlertDerivations} answer that the dispatcher and the safety
     * resolvers act on; {@code state} is never MORE current than it.
     * {@code verified} says whether the issuer stated it — a VTEC action, a CAP
     * reference, or the alert's own times — as opposed to SitPrep inferring it
     * from a poll that no longer listed the alert, which is never claimed as
     * verified.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LifecycleInfo(
            String state,
            /** {@code state} is active or updated. A record card still says so; see {@link Presentation#current}. */
            boolean current,
            boolean verified,
            /** vtec_&lt;action&gt; | cap_update | cap_cancel | all_clear | expired | replaced | not_listed, or null. */
            String reason,
            /** {@code VTEC:<event key>}, else {@code CAP:<root identifier>}, else the dedupeKey. */
            String threadKey,
            List<Reference> references,
            /** The dedupeKey of the message that replaced this one, when it is in hand. */
            String replacedBy,
            String updatedAt,
            String endedAt
    ) {}

    /** One CAP {@code references} entry; {@code resolved} when that message is in hand. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reference(String sender, String identifier, String sent, boolean resolved) {}

    /**
     * What SitPrep says about the alert, decided once by the safety policy and
     * shaped by {@code AlertInterpreter}. Never re-derived from the title,
     * severity or wording.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Interpreter(
            /** SitPrep's reviewed headline; null when guidance is not allowed or the card is not current. */
            String summary,
            /** Null on every non-current card, and when there is nothing to say. */
            PrimaryAction primaryAction,
            /** == {@code precautions} on a current card; null otherwise. */
            List<String> steps,
            /** == {@code precautionsSource}, with {@code steps}. */
            String stepsSource,
            /** sitprep | issuer | none */
            String author,
            String guidanceMode,
            String compatibility,
            String templateKey,
            Integer templateReviewVersion,
            String templateApprovedAt,
            List<Evidence> evidence
    ) {}

    /**
     * The one thing to do now. {@code reviewed_guidance} is SitPrep's
     * {@code whatToDo}; {@code official_instruction} is the issuer's CAP
     * instruction VERBATIM — never shortened here; a client clamps.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrimaryAction(String text, String kind, String attribution) {}

    /** A public source the reviewed template cites. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Evidence(String agency, String title, String url) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Presentation(
            /**
             * life_safety | urgent | prepare | info | record. Below life_safety
             * it follows the record type: warning → urgent, watch → prepare,
             * anything else (advisory, statement, information, and USGS / FEMA
             * records of fact) → info.
             */
            String priority,
            /** danger | warning | attention | neutral | all_clear — never from severity. */
            String tone,
            String lifecycleLabel,
            /**
             * SAFETY eligibility for an interruptive surface: current and
             * {@code critical_push}. {@code PushPolicyService} still decides
             * delivery.
             */
            boolean interruptiveEligible,
            /** In force AND not a record. A history card is never current. */
            boolean current
    ) {}

    /**
     * A semantic action. {@code fallbackRoute} is what a client that does not
     * know {@code type} should open. Navigation only: none of these starts an
     * activation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String type, java.util.Map<String, Object> params, String fallbackRoute) {}

    /** The issuer's area, distinct from where the viewer is ({@link ViewerContext}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OfficialArea(
            /** Always OFFICIAL_ALERT_AREA. */
            String role,
            /** polygon | zone | declared | none */
            String geometryKind,
            /** [south, west, north, east], or null when the alert carries no geometry. */
            List<Double> bbox,
            List<String> zones,
            String areaDesc,
            /** exact | region | none — the existing geometry confidence. */
            String confidence,
            String provenance
    ) {}

    /**
     * Where the viewer stands relative to the alert. {@code matchType} is the
     * relevance match (how the alert reached this feed) and is NOT
     * containment; {@code containment} is the only "inside" answer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ViewerContext(
            /** CURRENT_LOCATION | HOUSEHOLD_LOCATION */
            String role,
            String matchType,
            /** inside | outside | unknown | zone */
            String containment,
            String coordinateCapturedAt,
            Boolean coordinateStale
    ) {}

    /** Readable dispatched community posts for this alert's revision chain. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommunityThreads(List<String> postIds) {}
}
