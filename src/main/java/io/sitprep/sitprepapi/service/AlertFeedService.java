package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.LocationFreshness;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Assembles {@link AlertFeedResponse} — the card-shaped feed for the hazard
 * surfaces.
 *
 * <p>This is a <b>mapping</b> layer, not a decision layer. Every judgment it
 * needs was already made upstream and is passed through:</p>
 *
 * <ul>
 *   <li>whether an alert applies here — {@code AlertIngestService.matchTypeFor}
 *       (P0-4);</li>
 *   <li>whether it is a warning or a watch — the template's {@code tier}
 *       (P0-2 / P0-3);</li>
 *   <li>what to say about it — the template body via {@code fillBody}
 *       (P0-5).</li>
 * </ul>
 *
 * <p>Re-deriving any of those here would create a fifth place that knows how to
 * tell a Watch from a Warning. There were already four, and that is what the
 * epic has spent its time undoing.</p>
 */
@Service
public class AlertFeedService {

    /** Beyond this, the snapshot is stale — poll cadence plus a tick of slack. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(11);

    /**
     * Licence-required attribution, ready to render (audit P2-4). NIFC and
     * Overpass were credited nowhere; Open-Meteo's CC-BY-4.0 attribution is a
     * licence condition satisfied only by a line in the Terms page. Shipping
     * the string with the data is what gets it onto the card.
     */
    private static final Map<String, String> ATTRIBUTION = Map.of(
            "NWS", "National Weather Service (NOAA)",
            "USGS", "U.S. Geological Survey",
            "FEMA", "FEMA OpenFEMA");

    /**
     * NWS products that carry an evacuation or shelter-in-place instruction.
     * Same list the dispatch templates cover; see
     * {@link AlertFeedResponse#COVERAGE_CAVEAT} for what their presence does
     * and does not promise.
     */
    private static final Set<String> EVACUATION_PRODUCTS = Set.of(
            "Evacuation Immediate",
            "Shelter In Place Warning",
            "Civil Danger Warning",
            "Local Area Emergency",
            "Radiological Hazard Warning",
            "Nuclear Power Plant Warning",
            "Hazardous Materials Warning");

    private final AlertIngestService ingest;
    private final AlertDispatchService dispatch;

    public AlertFeedService(AlertIngestService ingest, AlertDispatchService dispatch) {
        this.ingest = ingest;
        this.dispatch = dispatch;
    }

    /** The feed for a coordinate, with no claim about how old that coordinate is. */
    public AlertFeedResponse feedFor(double lat, double lng) {
        return feedFor(lat, lng, null);
    }

    /**
     * The feed for a coordinate captured at {@code fixedAt}.
     *
     * <p>{@code fixedAt} is optional and a null one is honestly reported as
     * "unknown" rather than quietly treated as fresh — see
     * {@link AlertFeedResponse.LocationAge}.</p>
     */
    public AlertFeedResponse feedFor(double lat, double lng, Instant fixedAt) {
        int radiusMi = AppConfigResource.alertsRadiusMi();
        double radiusKm = radiusMi * 1.609344;

        Snapshot snap = ingest.getSnapshot();
        Set<String> userZones = ingest.zoneCodesForPoint(lat, lng);
        Set<String> userStates = AlertIngestService.statePrefixesOf(userZones);

        // Built from the WHOLE snapshot, not from the matched subset: an alert
        // can be replaced by one that does not match this viewer's point, and
        // the edge is a fact about the alert rather than about the reader.
        Map<String, NormalizedAlert> supersededBy =
                AlertDerivations.supersessionIndex(snap.alerts());

        List<AlertCardDto> cards = new ArrayList<>();
        for (NormalizedAlert a : snap.alerts()) {
            // One template lookup and one policy decision per alert, handed to
            // everything below. The filter, the card and the life-threatening
            // flag used to ask separately — three answers that only agreed
            // because nothing had changed between the calls yet.
            Assessment assessed = assess(a);
            if (assessed.decision().dispatchMode() == AlertSafetyPolicy.DispatchMode.SUPPRESS) {
                continue;
            }
            MatchType match = AlertIngestService.matchTypeFor(
                    a, lat, lng, radiusKm, userZones, userStates);
            if (match == null) continue;
            cards.add(toCard(a, match, radiusMi, supersededBy.get(a.id()), assessed));
        }

        return new AlertFeedResponse(List.copyOf(cards), metaFor(snap, fixedAt));
    }

    AlertFeedResponse.Meta metaFor(Snapshot snap) {
        return metaFor(snap, null);
    }

    AlertFeedResponse.Meta metaFor(Snapshot snap, Instant fixedAt) {
        Instant last = snap == null ? null : snap.lastSuccessAt();
        boolean stale = last == null || last.isBefore(Instant.now().minus(STALE_AFTER));
        return new AlertFeedResponse.Meta(
                last == null ? null : last.toString(),
                stale,
                AlertFeedResponse.COVERAGE_CAVEAT,
                locationAgeFor(fixedAt));
    }

    /**
     * The location verdict, or null when the caller did not supply a timestamp.
     *
     * <p>Shared with the history endpoint so both surfaces answer "is this
     * coordinate still trustworthy" the same way, against the same constant the
     * push path enforces.</p>
     */
    static AlertFeedResponse.LocationAge locationAgeFor(Instant fixedAt) {
        if (fixedAt == null) return null;
        return new AlertFeedResponse.LocationAge(
                fixedAt.toString(),
                LocationFreshness.isStale(fixedAt, Instant.now()),
                LocationFreshness.maxAgeDays());
    }

    /**
     * One alert's template and policy decision, resolved once.
     *
     * <p>The unit the feed passes around instead of re-asking. Package-private
     * so a test can count how often it is built.</p>
     */
    record Assessment(DispatchTemplate template, AlertSafetyPolicy.Decision decision) {}

    Assessment assess(NormalizedAlert a) {
        DispatchTemplate tpl = dispatch.matchForAlert(a).orElse(null);
        return new Assessment(tpl, AlertSafetyPolicy.evaluate(a, tpl));
    }

    AlertCardDto toCard(NormalizedAlert a, MatchType match, int radiusMi) {
        return toCard(a, match, radiusMi, null);
    }

    AlertCardDto toCard(NormalizedAlert a, MatchType match, int radiusMi, NormalizedAlert replacedBy) {
        return toCard(a, match, radiusMi, replacedBy, assess(a));
    }

    /**
     * A live card.
     *
     * <p>When the message that replaced this one is in the same snapshot, the
     * card is a RECORD, not an instruction (D3): the alert's own fields still say
     * {@code active}, but the issuer has since said something newer, and two
     * live-looking cards for one hazard is how an old order reads as current.
     * So it carries {@code superseded} (or {@code cancelled}, when the newer
     * message calls it off), no SitPrep guidance, and no current directive —
     * the as-issued one survives in {@code safety}.</p>
     */
    AlertCardDto toCard(NormalizedAlert a, MatchType match, int radiusMi,
                        NormalizedAlert replacedBy, Assessment assessed) {
        if (replacedBy != null) {
            return card(a, match, radiusMi, replacedBy, assessed,
                    AlertDerivations.endingStateOf(replacedBy), true);
        }
        return card(a, match, radiusMi, null, assessed,
                AlertDerivations.lifecycleState(a, Instant.now()), false);
    }

    /**
     * A history card: always a RECORD (D3).
     *
     * <p>History says what happened, never what to do. A row here may be a
     * cancelled order, an alert replaced days ago, or one that is still live —
     * and the live one is already on the feed, carrying its instruction where
     * the instruction belongs. So no history card carries SitPrep guidance or a
     * current directive, whatever its lifecycle. {@code lifecycleState} is the
     * caller's: it is the only one holding the stored edges that decide it.</p>
     */
    AlertCardDto toRecordCard(NormalizedAlert a, MatchType match, int radiusMi, String lifecycleState) {
        return card(a, match, radiusMi, null, assess(a), lifecycleState, true);
    }

    private AlertCardDto card(NormalizedAlert a, MatchType match, int radiusMi,
                              NormalizedAlert replacedBy, Assessment assessed,
                              String lifecycleState, boolean record) {
        DispatchTemplate tpl = assessed.template();
        AlertSafetyPolicy.Decision decision = assessed.decision();
        boolean guidance = !record && decision.allowsSitPrepGuidance();

        // Plain-language copy — ours, sanitised. Absent when no template covers
        // this product, which is honest: we would rather say nothing than
        // present wire text as if we had written it. Absent on a record too:
        // it is present-tense advice, and a record is not present tense.
        String headline = guidance ? tpl.headline : null;
        String whatToDo = guidance ? AlertDispatchService.fillBody(tpl, a) : null;

        // The numbered WHAT TO DO steps, and their attribution, travel together
        // or not at all — a caller cannot render reviewed guidance without the
        // line saying whose it is.
        List<String> precautions = guidance
                ? AlertDerivations.orNull(tpl == null ? null : tpl.steps)
                : null;
        String precautionsSource = precautions == null ? null : AlertDerivations.guidanceAttribution(a);

        return new AlertCardDto(
                a.id(),
                a.source() == null ? null : a.source().toLowerCase(Locale.ROOT),
                a.event(),
                headline != null ? headline : a.event(),
                tierOf(a, tpl),
                !record && isLifeThreatening(a, tpl, decision),
                a.event() != null && EVACUATION_PRODUCTS.contains(a.event()),
                headline,
                whatToDo,
                precautions,
                precautionsSource,
                lifecycleState,
                // The FORWARD edge: what this message replaces. Real today —
                // 29% of live alerts carry it.
                a.references() == null || a.references().isEmpty()
                        ? List.of()
                        : List.copyOf(a.references()),
                // The REVERSE edge: what replaced this message. Only when both
                // ends are in the same snapshot, which is rare by construction
                // — /alerts/active drops an alert the moment it is replaced.
                replacedBy == null
                        ? null
                        : new AlertCardDto.SupersededBy(
                                replacedBy.id(),
                                AlertDerivations.supersededByTitle(replacedBy)),
                officialOf(a),
                new AlertCardDto.Location(
                        a.area(),
                        match.wire(),
                        AlertDerivations.geometryConfidence(a),
                        AlertDerivations.areaLabel(a),
                        placeOf(a)),
                radiusMi,
                a.startedAt(),
                a.endsAt(),                       // null stays null — see the DTO
                ATTRIBUTION.get(a.source()),
                detailOf(a),
                record ? recordSafetyOf(decision, lifecycleState) : safetyOf(decision));
    }

    private static AlertCardDto.Safety safetyOf(AlertSafetyPolicy.Decision decision) {
        if (decision == null) return null;
        return new AlertCardDto.Safety(
                decision.dispatchMode().wire(),
                decision.guidanceMode().wire(),
                decision.compatibility().wire(),
                decision.capActions().stream().map(Enum::name).toList(),
                decision.movementDirective().wire(),
                decision.reason(),
                asIssuedOf(decision));
    }

    /**
     * The safety block of a record: the same shape the policy gives an alert it
     * has lifecycle-blocked, so a client reading {@code safety} cannot tell a
     * record from an ended alert — because there is nothing to tell.
     *
     * <p>Nothing here may read as current: no dispatch, no SitPrep guidance
     * (a client that sees {@code supplement_official} is entitled to show
     * guidance), no protective actions, directive {@code none}. What the alert
     * said while live survives only as {@code asIssuedMovementDirective}.</p>
     */
    private static AlertCardDto.Safety recordSafetyOf(AlertSafetyPolicy.Decision decision,
                                                      String lifecycleState) {
        if (decision == null) return null;
        // Already blocked by the policy (ended, all-clear, ...): its own reason
        // is the more specific one, so keep it.
        boolean alreadyBlocked = decision.dispatchMode() == AlertSafetyPolicy.DispatchMode.SUPPRESS
                && decision.movementDirective() == AlertSafetyPolicy.MovementDirective.NONE
                && decision.capActions().isEmpty();
        return new AlertCardDto.Safety(
                AlertSafetyPolicy.DispatchMode.SUPPRESS.wire(),
                AlertSafetyPolicy.GuidanceMode.NO_GUIDANCE.wire(),
                decision.compatibility().wire(),
                List.of(),
                AlertSafetyPolicy.MovementDirective.NONE.wire(),
                alreadyBlocked ? decision.reason() : "record_" + lifecycleState,
                asIssuedOf(decision));
    }

    private static String asIssuedOf(AlertSafetyPolicy.Decision decision) {
        AlertSafetyPolicy.MovementDirective asIssued = decision.asIssuedMovementDirective();
        return asIssued == null ? null : asIssued.wire();
    }

    /**
     * The raw wire block, verbatim.
     *
     * <p>Deliberately does NOT touch {@code fillBody} or {@code sanitizeSlots}.
     * The value of this block is that it is unprocessed; running our copy
     * pipeline over it would leave no raw record to disclose.</p>
     */
    private static AlertCardDto.Official officialOf(NormalizedAlert a) {
        // `instruction` was a hardcoded null here until 2026-08-25 — the DTO
        // declared the field, the ingest dropped the source, and no client read
        // it, so the audit's "promote the instruction when whatToDo is absent"
        // fix rested on something that could never be non-null. It now carries
        // what NWS actually sent.
        //
        // STILL NOT PROMOTED, AND THAT IS DELIBERATE. This puts the raw wire
        // text where the existing "official wording" disclosure already shows
        // raw wire text, which is the contract this DTO's own javadoc
        // describes. Lifting it to the top level needs a rule for what it
        // REFUSES to lift — live reading grade runs 5.8 to 12.2 — and that is
        // an open product decision, not an implementation detail. See
        // docs/epics/map/map-view-revamp/PHASE-2-2026-08-25.md §3.
        return new AlertCardDto.Official(
                a.headline(), a.description(), a.instruction(), issuedByOf(a.headline()));
    }

    /** {@code "... by NWS Phoenix AZ"} -> {@code "NWS Phoenix AZ"}. */
    static String issuedByOf(String wireHeadline) {
        if (wireHeadline == null) return null;
        int idx = wireHeadline.lastIndexOf(" by ");
        if (idx < 0) return null;
        String office = wireHeadline.substring(idx + 4).trim();
        return office.isEmpty() ? null : office;
    }

    /**
     * Tier, from the template when one covers this product.
     *
     * <p>Falls back to the product-name suffix only for the products we have no
     * copy for — advisories, statements, marine. That fallback is a display
     * concern, not a re-derivation of the Watch/Warning decision: nothing that
     * reaches it is push-eligible, because push eligibility comes from the
     * template that is absent.</p>
     */
    static String tierOf(NormalizedAlert a, DispatchTemplate tpl) {
        if (tpl != null && tpl.tier != null) return tpl.tier;
        String event = a.event();
        if (event == null) return "information";
        if (event.endsWith("Warning")) return "warning";
        if (event.endsWith("Watch")) return "watch";
        if (event.endsWith("Advisory")) return "advisory";
        if (event.endsWith("Statement")) return "statement";
        return "information";
    }

    /**
     * Passed through from the dispatcher — <b>the same method that decides
     * whether to send a push</b>, not a second opinion.
     *
     * <p>This first shipped as a reimplementation here: warning tier AND still
     * in force AND shape-consistent. It looked equivalent and was not — it
     * omitted the dispatcher's "NWS only" clause, so a USGS earthquake
     * serialized as {@code isLifeThreatening: true} while the dispatcher would
     * never push it. The shaking has already happened; that is why the
     * dispatcher excludes quakes, and the DTO had quietly disagreed with it.
     * Exactly the fifth-opinion problem this epic exists to remove, reproduced
     * in the act of writing the DTO that was supposed to end it.</p>
     */
    static boolean isLifeThreatening(NormalizedAlert a, DispatchTemplate tpl,
                                     AlertSafetyPolicy.Decision decision) {
        return AlertDispatchService.isLifeThreatening(a, tpl, decision);
    }

    /** USGS names an epicentre; NWS does not. */
    private static String placeOf(NormalizedAlert a) {
        return "USGS".equalsIgnoreCase(a.source()) ? a.area() : null;
    }

    private static AlertCardDto.Detail detailOf(NormalizedAlert a) {
        if ("USGS".equalsIgnoreCase(a.source())) {
            // The wire magnitude when ingest read one; the headline parse only
            // for an alert built before it did (a rehydrated history payload).
            Double mag = a.magnitude() != null
                    ? a.magnitude()
                    : AlertDispatchService.parseUsgsMagnitude(a.headline());
            Double depth = depthFromGeometry(a.geometry());
            if (mag == null && depth == null) return null;
            // PAGER and tsunami straight from the USGS wire (audit P1-6, S2 C1).
            // `tsunami` is a primitive on the DTO, so "not sent" still reads
            // false there — as it always has; the record keeps the null.
            return new AlertCardDto.Detail(
                    new AlertCardDto.Earthquake(mag, depth, a.pagerAlert(),
                            Boolean.TRUE.equals(a.tsunamiFlag())), null);
        }
        if ("FEMA".equalsIgnoreCase(a.source())) {
            // declarationDate (R13). This was startedAt — the INCIDENT's begin,
            // which can be weeks before anything was declared.
            return new AlertCardDto.Detail(null, new AlertCardDto.Disaster(
                    a.id(), a.declaredAt(), List.of("Individual Assistance")));
        }
        return null;
    }

    /** USGS Point coordinates are {@code [lon, lat, depthKm]}. */
    @SuppressWarnings("rawtypes")
    private static Double depthFromGeometry(Object geom) {
        if (!(geom instanceof Map)) return null;
        Object coords = ((Map) geom).get("coordinates");
        if (!(coords instanceof List) || ((List) coords).size() < 3) return null;
        Object d = ((List) coords).get(2);
        return d instanceof Number ? ((Number) d).doubleValue() : null;
    }
}
