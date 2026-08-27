package io.sitprep.sitprepapi.service;

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
import java.util.Optional;
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

    /** The feed for a coordinate. */
    public AlertFeedResponse feedFor(double lat, double lng) {
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
            MatchType match = AlertIngestService.matchTypeFor(
                    a, lat, lng, radiusKm, userZones, userStates);
            if (match == null) continue;
            cards.add(toCard(a, match, radiusMi, supersededBy.get(a.id())));
        }

        return new AlertFeedResponse(List.copyOf(cards), metaFor(snap));
    }

    AlertFeedResponse.Meta metaFor(Snapshot snap) {
        Instant last = snap == null ? null : snap.lastSuccessAt();
        boolean stale = last == null || last.isBefore(Instant.now().minus(STALE_AFTER));
        return new AlertFeedResponse.Meta(
                last == null ? null : last.toString(),
                stale,
                AlertFeedResponse.COVERAGE_CAVEAT);
    }

    AlertCardDto toCard(NormalizedAlert a, MatchType match, int radiusMi) {
        return toCard(a, match, radiusMi, null);
    }

    AlertCardDto toCard(NormalizedAlert a, MatchType match, int radiusMi, NormalizedAlert replacedBy) {
        Optional<DispatchTemplate> tplOpt = dispatch.matchForAlert(a);
        DispatchTemplate tpl = tplOpt.orElse(null);

        // Plain-language copy — ours, sanitised. Absent when no template covers
        // this product, which is honest: we would rather say nothing than
        // present wire text as if we had written it.
        String headline = tpl != null ? tpl.headline : null;
        String whatToDo = tpl != null ? AlertDispatchService.fillBody(tpl, a) : null;

        // The numbered WHAT TO DO steps, and their attribution, travel together
        // or not at all — a caller cannot render reviewed guidance without the
        // line saying whose it is.
        List<String> precautions = AlertDerivations.orNull(tpl == null ? null : tpl.steps);
        String precautionsSource = precautions == null ? null : AlertDerivations.guidanceAttribution(a);

        return new AlertCardDto(
                a.id(),
                a.source() == null ? null : a.source().toLowerCase(Locale.ROOT),
                a.event(),
                headline != null ? headline : a.event(),
                tierOf(a, tpl),
                isLifeThreatening(a, tpl),
                a.event() != null && EVACUATION_PRODUCTS.contains(a.event()),
                headline,
                whatToDo,
                precautions,
                precautionsSource,
                AlertDerivations.lifecycleState(a, Instant.now()),
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
                detailOf(a));
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
        // docs/epics/map-view-revamp/PHASE-2-2026-08-25.md §3.
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
    static boolean isLifeThreatening(NormalizedAlert a, DispatchTemplate tpl) {
        return AlertDispatchService.isLifeThreatening(a, tpl);
    }

    /** USGS names an epicentre; NWS does not. */
    private static String placeOf(NormalizedAlert a) {
        return "USGS".equalsIgnoreCase(a.source()) ? a.area() : null;
    }

    private static AlertCardDto.Detail detailOf(NormalizedAlert a) {
        if ("USGS".equalsIgnoreCase(a.source())) {
            Double mag = AlertDispatchService.parseUsgsMagnitude(a.headline());
            Double depth = depthFromGeometry(a.geometry());
            if (mag == null && depth == null) return null;
            // pagerLevel and tsunami are on the USGS wire and still dropped at
            // the normalizer (audit P1-6) — null here rather than invented.
            return new AlertCardDto.Detail(
                    new AlertCardDto.Earthquake(mag, depth, null, false), null);
        }
        if ("FEMA".equalsIgnoreCase(a.source())) {
            return new AlertCardDto.Detail(null, new AlertCardDto.Disaster(
                    a.id(), a.startedAt(), List.of("Individual Assistance")));
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
