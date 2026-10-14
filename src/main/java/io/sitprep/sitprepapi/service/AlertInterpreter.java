package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.LocationFreshness;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.CapReference;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertIdentity;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import io.sitprep.sitprepapi.util.GeoContainment;
import io.sitprep.sitprepapi.util.Vtec;

import java.net.URLEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns one alert's EXISTING decision into the card contract's interpretation
 * blocks (S2 contract §3): {@code interpreter}, {@code presentation},
 * {@code actions}, {@code lifecycleInfo}, {@code officialArea},
 * {@code viewerContext}, {@code sourceIdentity}.
 *
 * <h2>It decides nothing the policy has not already decided</h2>
 *
 * <p>The input carries the {@link AlertFeedService.Assessment} — template and
 * {@link AlertSafetyPolicy.Decision} — that {@code AlertFeedService.assess}
 * made once for this alert, and the copy the card already chose from it. This
 * class never calls the policy, never looks a template up, and never reads a
 * title, a severity or any wording to pick a tone or an action. Every rule below
 * is a table over structured fields, which is the only kind of rule that can be
 * reviewed: "a Severe Watch is amber" is checkable; "a scary-sounding headline is
 * red" is not.</p>
 *
 * <h2>Nothing here is AI</h2>
 *
 * <p>No model, no ranking, no summarisation. The SitPrep text is the reviewed
 * template copy; the official text is the issuer's, verbatim and attributed.</p>
 *
 * <p>{@link #VERSION} is bumped by hand whenever a rule here changes what a card
 * says. Copy-only template edits do not bump it.</p>
 */
public final class AlertInterpreter {

    private AlertInterpreter() {}

    /** Bumped by hand when these rules change behaviour (contract §6). */
    public static final String VERSION = "interp-2026-10-13.1";

    public static final String CONTEXT_CURRENT = "current";
    public static final String CONTEXT_HOME = "home";

    static final String KIND_REVIEWED = "reviewed_guidance";
    static final String KIND_OFFICIAL = "official_instruction";
    static final String REVIEWED_ATTRIBUTION = "SitPrep reviewed guidance";

    /**
     * Hazards that have a SitPrep guide, and where it lives. Mirrors the
     * frontend's {@code SITPREP_GUIDES} routes — a guide is a page, so the
     * route is the honest fallback, not an invented {@code /guides/<key>}.
     */
    private static final Map<String, String> GUIDE_ROUTES = Map.of(
            "hurricane", "/hurricane-prep",
            "wildfire", "/wildfire-prep",
            "earthquake", "/earthquake-prep",
            "flood", "/flood-prep",
            "blizzard", "/blizzard-prep",
            "tornado", "/tornado-prep");

    /** VTEC actions that end an event, as opposed to carrying it on. */
    private static final Set<String> TERMINAL_VTEC = Set.of("CAN", "EXP", "UPG");

    // ------------------------------------------------------------------
    // Inputs and outputs
    // ------------------------------------------------------------------

    /** {@code current} or {@code home}; anything else (or nothing) is {@code current}. */
    public static String normalizeContext(String raw) {
        if (raw != null && CONTEXT_HOME.equalsIgnoreCase(raw.trim())) return CONTEXT_HOME;
        return CONTEXT_CURRENT;
    }

    /** The coordinate the feed was asked about, and whose it is. */
    public record Viewer(double lat, double lng, Instant fixedAt, String context) {
        public Viewer {
            context = normalizeContext(context);
        }
    }

    /**
     * Everything the card has already decided, handed over once.
     *
     * @param record     true for a history card, or a live card no longer in force
     * @param tier       {@code AlertFeedService.tierOf}
     * @param headline   SitPrep's headline, or null when guidance is not shown
     * @param whatToDo   SitPrep's action copy, or null when guidance is not shown
     * @param safety     the card's safety block (the record form on a record)
     * @param allClear   the issuer said AllClear about this alert — on it, or on
     *                   the message that ended it
     * @param viewer     null when the card is built without a viewer point
     * @param communityPostIds readable dispatched posts, ascending; may be empty
     */
    record Input(NormalizedAlert alert,
                 AlertFeedService.Assessment assessed,
                 boolean record,
                 MatchType match,
                 String tier,
                 String headline,
                 String whatToDo,
                 List<String> precautions,
                 String precautionsSource,
                 AlertCardDto.Safety safety,
                 AlertCardDto.LifecycleInfo lifecycle,
                 boolean allClear,
                 Viewer viewer,
                 List<Long> communityPostIds,
                 Instant now) {}

    /** The new card blocks. */
    record Output(String dedupeKey,
                  String sourceRevisionRef,
                  AlertCardDto.SourceIdentity sourceIdentity,
                  String clusterKey,
                  AlertCardDto.Interpreter interpreter,
                  AlertCardDto.Presentation presentation,
                  List<AlertCardDto.Action> actions,
                  AlertCardDto.OfficialArea officialArea,
                  AlertCardDto.ViewerContext viewerContext,
                  AlertCardDto.CommunityThreads communityThreads) {}

    static Output interpret(Input in) {
        NormalizedAlert a = in.alert();
        DispatchTemplate tpl = in.assessed() == null ? null : in.assessed().template();
        AlertSafetyPolicy.Decision decision = in.assessed() == null ? null : in.assessed().decision();

        // A card is current only when it is in force AND not a record. A history
        // card of a still-live alert says "active" in lifecycleInfo — a fact —
        // but presents as a record; the live one is on the feed.
        boolean current = !in.record() && in.lifecycle() != null && in.lifecycle().current();

        String dedupeKey = AlertIdentity.dedupeKey(a.source(), a.id());
        String revisionRef = AlertSourceFingerprint.revisionRef(a);
        TrustedSourceRegistry.SourceIdentity reg = TrustedSourceRegistry.of(a.source());
        String recordType = TrustedSourceRegistry.recordTypeOf(a, tpl);

        AlertCardDto.SourceIdentity sourceIdentity = new AlertCardDto.SourceIdentity(
                reg.key(), reg.name(), reg.official(), blankToNull(a.senderName()), recordType, reg.logoKey());

        boolean lifeSafety = current && decision != null
                && (decision.criticalPush()
                    || decision.movementDirective() != AlertSafetyPolicy.MovementDirective.NONE);

        AlertCardDto.Interpreter interpreter = interpreterOf(in, tpl, decision, current, reg);
        AlertCardDto.Presentation presentation = new AlertCardDto.Presentation(
                priorityOf(current, lifeSafety, recordType),
                toneOf(in, decision, current, lifeSafety, recordType),
                lifecycleLabel(in.lifecycle() == null ? null : in.lifecycle().state()),
                current && decision != null && decision.criticalPush(),
                current);

        AlertCardDto.OfficialArea area = officialAreaOf(a);
        AlertCardDto.ViewerContext viewer = viewerContextOf(a, in.match(), in.viewer(), in.now());
        List<String> postIds = in.communityPostIds() == null || in.communityPostIds().isEmpty()
                ? null
                : in.communityPostIds().stream().map(String::valueOf).toList();

        List<AlertCardDto.Action> actions = actionsOf(a, tpl, dedupeKey, revisionRef, area,
                in.viewer(), current, lifeSafety, postIds);

        return new Output(dedupeKey, revisionRef, sourceIdentity, clusterKeyOf(a),
                interpreter, presentation, actions, area, viewer,
                postIds == null ? null : new AlertCardDto.CommunityThreads(postIds));
    }

    // ------------------------------------------------------------------
    // Rule 1 — interpreter + primary action
    // ------------------------------------------------------------------

    private static AlertCardDto.Interpreter interpreterOf(Input in, DispatchTemplate tpl,
                                                         AlertSafetyPolicy.Decision decision,
                                                         boolean current,
                                                         TrustedSourceRegistry.SourceIdentity reg) {
        NormalizedAlert a = in.alert();
        AlertCardDto.PrimaryAction primary = primaryActionOf(a, decision, current, in.whatToDo(), reg);

        boolean sitprepAuthored = in.headline() != null || in.whatToDo() != null || in.precautions() != null;
        String author = sitprepAuthored ? "sitprep"
                : primary != null && KIND_OFFICIAL.equals(primary.kind()) ? "issuer"
                : "none";

        List<AlertCardDto.Evidence> evidence = null;
        if (sitprepAuthored && tpl != null && tpl.evidence != null && !tpl.evidence.isEmpty()) {
            evidence = tpl.evidence.stream()
                    .map(e -> new AlertCardDto.Evidence(e.agency, e.title, e.url))
                    .toList();
        }

        AlertCardDto.Safety safety = in.safety();
        return new AlertCardDto.Interpreter(
                in.headline(),
                primary,
                in.precautions(),
                in.precautionsSource(),
                author,
                safety == null ? null : safety.guidanceMode(),
                safety == null ? null : safety.compatibility(),
                templateKeyOf(a, tpl),
                tpl == null || tpl.safetyReview == null ? null : tpl.safetyReview.version,
                tpl == null || tpl.safetyReview == null ? null : tpl.safetyReview.approvedAt,
                evidence);
    }

    /**
     * Rule 1, in order:
     * <ol>
     *   <li>current and the decision allows SitPrep guidance → our reviewed
     *       {@code whatToDo};</li>
     *   <li>current, guidance not allowed, and the issuer sent an instruction →
     *       that instruction VERBATIM, attributed to a verified source;</li>
     *   <li>otherwise nothing.</li>
     * </ol>
     * <p>Never from a title, a severity or a keyword, and never on a card that
     * is not current — whatever {@code asIssuedMovementDirective} says.</p>
     */
    static AlertCardDto.PrimaryAction primaryActionOf(NormalizedAlert a,
                                                      AlertSafetyPolicy.Decision decision,
                                                      boolean current,
                                                      String whatToDo,
                                                      TrustedSourceRegistry.SourceIdentity reg) {
        if (!current || decision == null) return null;
        if (decision.allowsSitPrepGuidance()) {
            return isBlank(whatToDo) ? null
                    : new AlertCardDto.PrimaryAction(whatToDo, KIND_REVIEWED, REVIEWED_ATTRIBUTION);
        }
        String instruction = a.instruction();
        // An unverified source cannot be attributed, so it gets no primary action.
        if (isBlank(instruction) || reg == null || !reg.official() || reg.name() == null) return null;
        // Verbatim: not trimmed, not shortened. A client clamps for display.
        return new AlertCardDto.PrimaryAction(instruction, KIND_OFFICIAL, officialAttribution(reg));
    }

    /** "From the National Weather Service", "From FEMA". */
    static String officialAttribution(TrustedSourceRegistry.SourceIdentity reg) {
        String name = reg.name();
        return "FEMA".equals(reg.key()) ? "From " + name : "From the " + name;
    }

    private static String templateKeyOf(NormalizedAlert a, DispatchTemplate tpl) {
        if (tpl == null) return null;
        String subject = !isBlank(a.event()) ? a.event() : tpl.hazardType;
        return tpl.source + ":" + subject + ":" + tpl.tier;
    }

    // ------------------------------------------------------------------
    // Rules 2 and 3 — tone and priority
    // ------------------------------------------------------------------

    /**
     * Rule 2 (R17). Read off the tier, the dispatch mode and the lifecycle —
     * never off {@code severity}.
     */
    static String toneOf(Input in, AlertSafetyPolicy.Decision decision, boolean current,
                         boolean lifeSafety, String recordType) {
        if (!current) {
            boolean verified = in.lifecycle() != null && in.lifecycle().verified();
            return in.record() && verified && in.allClear() ? "all_clear" : "neutral";
        }
        // Records of fact — a disaster declaration, a quake that has happened —
        // are neutral unless the policy found something life-safety in them.
        if (!lifeSafety && (TrustedSourceRegistry.RECORD_DISASTER_DECLARATION.equals(recordType)
                || TrustedSourceRegistry.RECORD_EARTHQUAKE_INFORMATION.equals(recordType))) {
            return "neutral";
        }
        String tier = in.tier() == null ? "" : in.tier();
        AlertSafetyPolicy.DispatchMode mode = decision == null ? null : decision.dispatchMode();
        switch (tier) {
            case "warning":
                return mode == AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH
                        || mode == AlertSafetyPolicy.DispatchMode.ATTENTION
                        ? "danger" : "attention";
            case "watch":
                // Amber at most, whatever the CAP severity: a Watch never takes
                // warning-level tone.
                return "warning";
            default:
                return "attention";
        }
    }

    /** Rule 3. */
    static String priorityOf(boolean current, boolean lifeSafety, String recordType) {
        if (!current) return "record";
        if (lifeSafety) return "life_safety";
        if (TrustedSourceRegistry.RECORD_WARNING.equals(recordType)) return "urgent";
        if (TrustedSourceRegistry.RECORD_WATCH.equals(recordType)) return "prepare";
        return "info";
    }

    private static String lifecycleLabel(String state) {
        if (state == null) return null;
        return switch (state) {
            case AlertDerivations.LIFECYCLE_ACTIVE -> "In effect";
            case AlertDerivations.LIFECYCLE_UPDATED -> "Updated";
            case AlertDerivations.LIFECYCLE_SUPERSEDED -> "Replaced";
            case AlertDerivations.LIFECYCLE_CANCELLED -> "Cancelled";
            case AlertDerivations.LIFECYCLE_EXPIRED -> "Ended";
            case AlertDerivations.LIFECYCLE_NO_LONGER_LISTED -> "No longer listed";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Rule 4 — actions
    // ------------------------------------------------------------------

    private static List<AlertCardDto.Action> actionsOf(NormalizedAlert a, DispatchTemplate tpl,
                                                       String dedupeKey, String revisionRef,
                                                       AlertCardDto.OfficialArea area, Viewer viewer,
                                                       boolean current, boolean lifeSafety,
                                                       List<String> postIds) {
        List<AlertCardDto.Action> out = new ArrayList<>();
        String context = viewer == null ? CONTEXT_CURRENT : viewer.context();

        // Detail — always.
        Map<String, Object> detail = new LinkedHashMap<>();
        put(detail, "dedupeKey", dedupeKey);
        put(detail, "revisionRef", revisionRef);
        detail.put("context", context);
        String hazards = CONTEXT_HOME.equals(context) ? "/hazards?scope=home" : "/hazards";
        String detailRoute = dedupeKey == null ? hazards
                : hazards + (hazards.contains("?") ? "&" : "?") + "alert=" + enc(dedupeKey);
        out.add(action("OPEN_ALERT_DETAIL", detail, detailRoute));

        // Map — only when there is an area to show.
        boolean hasZones = area.zones() != null && !area.zones().isEmpty();
        if (area.bbox() != null || hasZones) {
            Map<String, Object> mp = new LinkedHashMap<>();
            put(mp, "dedupeKey", dedupeKey);
            if (area.bbox() != null) mp.put("bbox", area.bbox());
            out.add(action("OPEN_ALERT_MAP", mp, mapRoute(a, area.bbox(), viewer)));
        }

        // Guide — when SitPrep has one for this hazard. Commerce stays out of a
        // guide opened from a live alert.
        String hazardKey = hazardKeyOf(a, tpl);
        String guideRoute = hazardKey == null ? null : GUIDE_ROUTES.get(hazardKey);
        if (guideRoute != null) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("hazardKey", hazardKey);
            g.put("commerceSuppressed", current);
            out.add(action("OPEN_GUIDE", g, guideRoute));
        }

        // The community conversation, one action per readable post.
        if (postIds != null) {
            for (String postId : postIds) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("postId", postId);
                out.add(action("OPEN_COMMUNITY_THREAD", p, "/community/posts/" + enc(postId)));
            }
        }

        // Household doors — NAVIGATION ONLY. Opening the check-in drawer or the
        // plan never starts a check-in or an activation (handoff §7); the
        // household decides that on the page it lands on.
        if (current && lifeSafety) {
            out.add(action("OPEN_HOUSEHOLD_CHECKIN", Map.of(), "/home?household=open"));
            out.add(action("OPEN_EMERGENCY_PLAN", Map.of(), "/view-plan"));
        }
        // START_POST is deliberately never emitted from here: on a record it is
        // forbidden (rule 4), and a live card's composer entry is the surface's.
        return List.copyOf(out);
    }

    private static String hazardKeyOf(NormalizedAlert a, DispatchTemplate tpl) {
        if (tpl != null && !isBlank(tpl.hazardType)) return tpl.hazardType.trim().toLowerCase(Locale.ROOT);
        // USGS is an earthquake by source, not by wording.
        return "USGS".equalsIgnoreCase(a.source()) ? "earthquake" : null;
    }

    /**
     * {@code /map?lat&lng&select=alert:<id>&bbox=s,w,n,e}. The map's deep-link
     * reader needs a camera point, so the box's centre (or, for a zone-only
     * alert, the viewer's own point) goes along; {@code select} names the alert
     * the way the map's alert list keys it, by its upstream id.
     */
    private static String mapRoute(NormalizedAlert a, List<Double> bbox, Viewer viewer) {
        StringBuilder sb = new StringBuilder("/map?");
        if (bbox != null) {
            sb.append("lat=").append(num((bbox.get(0) + bbox.get(2)) / 2))
                    .append("&lng=").append(num((bbox.get(1) + bbox.get(3)) / 2)).append('&');
        } else if (viewer != null) {
            sb.append("lat=").append(num(viewer.lat())).append("&lng=").append(num(viewer.lng())).append('&');
        }
        sb.append("select=").append(enc("alert:" + (a.id() == null ? "" : a.id().trim())));
        if (bbox != null) {
            sb.append("&bbox=").append(num(bbox.get(0))).append(',').append(num(bbox.get(1)))
                    .append(',').append(num(bbox.get(2))).append(',').append(num(bbox.get(3)));
        }
        return sb.toString();
    }

    private static AlertCardDto.Action action(String type, Map<String, Object> params, String route) {
        return new AlertCardDto.Action(type, Collections.unmodifiableMap(new LinkedHashMap<>(params)), route);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    // ------------------------------------------------------------------
    // Rule 5 — official area and viewer context
    // ------------------------------------------------------------------

    static AlertCardDto.OfficialArea officialAreaOf(NormalizedAlert a) {
        String type = geometryType(a.geometry());
        boolean polygonal = "Polygon".equals(type) || "MultiPolygon".equals(type);
        List<String> zones = a.ugc() == null || a.ugc().isEmpty() ? null : List.copyOf(a.ugc());
        String kind;
        if (polygonal) kind = "polygon";
        else if (zones != null) kind = "zone";
        else if ("FEMA".equalsIgnoreCase(a.source())) kind = "declared";
        // A USGS epicentre is a point, not an area: no kind of area, though
        // the box still frames the point on a map.
        else kind = "none";
        return new AlertCardDto.OfficialArea(
                "OFFICIAL_ALERT_AREA",
                kind,
                bboxOf(a.geometry()),
                zones,
                blankToNull(a.area()),
                AlertDerivations.geometryConfidence(a),
                AlertIdentity.knownSource(a.source()));
    }

    /**
     * Rule 5: {@code containment} is the only "inside" answer. A polygon is
     * tested with {@link GeoContainment} (holes excluded); a zone match says
     * {@code zone}, because a county-sized zone is not a claim about the
     * street; everything else is {@code unknown}. {@code matchType} rides along
     * as what it is — the relevance match.
     */
    static AlertCardDto.ViewerContext viewerContextOf(NormalizedAlert a, MatchType match,
                                                      Viewer viewer, Instant now) {
        if (viewer == null) return null;
        String type = geometryType(a.geometry());
        String containment;
        if ("Polygon".equals(type) || "MultiPolygon".equals(type)) {
            containment = GeoContainment.locate(viewer.lat(), viewer.lng(), a.geometry())
                    .name().toLowerCase(Locale.ROOT);
        } else if (match == MatchType.ZONE) {
            containment = "zone";
        } else {
            containment = "unknown";
        }
        return new AlertCardDto.ViewerContext(
                CONTEXT_HOME.equals(viewer.context()) ? "HOUSEHOLD_LOCATION" : "CURRENT_LOCATION",
                match == null ? null : match.wire(),
                containment,
                viewer.fixedAt() == null ? null : viewer.fixedAt().toString(),
                viewer.fixedAt() == null ? null : LocationFreshness.isStale(viewer.fixedAt(), now));
    }

    @SuppressWarnings("rawtypes")
    private static String geometryType(Object geometry) {
        if (!(geometry instanceof Map)) return null;
        Object t = ((Map) geometry).get("type");
        return t instanceof String ? (String) t : null;
    }

    /** [south, west, north, east] over every position in the geometry, or null. */
    @SuppressWarnings("rawtypes")
    static List<Double> bboxOf(Object geometry) {
        if (!(geometry instanceof Map)) return null;
        double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        Map g = (Map) geometry;
        if ("GeometryCollection".equals(g.get("type")) && g.get("geometries") instanceof List) {
            for (Object part : (List) g.get("geometries")) {
                List<Double> sub = bboxOf(part);
                if (sub != null) {
                    box[0] = Math.min(box[0], sub.get(0));
                    box[1] = Math.min(box[1], sub.get(1));
                    box[2] = Math.max(box[2], sub.get(2));
                    box[3] = Math.max(box[3], sub.get(3));
                }
            }
        } else {
            walk(g.get("coordinates"), box);
        }
        if (box[0] == Double.POSITIVE_INFINITY) return null;
        return List.of(box[0], box[1], box[2], box[3]);
    }

    @SuppressWarnings("rawtypes")
    private static void walk(Object node, double[] box) {
        if (!(node instanceof List list) || list.isEmpty()) return;
        if (list.get(0) instanceof Number && list.size() >= 2 && list.get(1) instanceof Number) {
            double lng = ((Number) list.get(0)).doubleValue();
            double lat = ((Number) list.get(1)).doubleValue();
            if (!Double.isFinite(lat) || !Double.isFinite(lng)) return;
            box[0] = Math.min(box[0], lat);
            box[1] = Math.min(box[1], lng);
            box[2] = Math.max(box[2], lat);
            box[3] = Math.max(box[3], lng);
            return;
        }
        for (Object child : (List) list) walk(child, box);
    }

    // ------------------------------------------------------------------
    // Presentation-only cluster key
    // ------------------------------------------------------------------

    /**
     * {@code NWS:flood watch:UTZ104+UTZ105:2026-10-13T12} — source, product,
     * sorted zones, onset hour. PRESENTATION ONLY: two alerts sharing it may sit
     * together on a surface; it never ends, hides or replaces either. Null for
     * a source with no product name.
     */
    static String clusterKeyOf(NormalizedAlert a) {
        String source = AlertIdentity.knownSource(a.source());
        if (source == null || isBlank(a.event())) return null;
        TreeSet<String> zones = new TreeSet<>();
        if (a.ugc() != null) for (String z : a.ugc()) if (!isBlank(z)) zones.add(z.trim());
        String hour = null;
        Instant start = parseInstant(a.startedAt());
        if (start != null) {
            hour = start.truncatedTo(ChronoUnit.HOURS).atOffset(ZoneOffset.UTC).toString();
            hour = hour.substring(0, 13);   // yyyy-MM-ddTHH
        }
        return source + ":" + a.event().trim().toLowerCase(Locale.ROOT)
                + ":" + (zones.isEmpty() ? "-" : String.join("+", zones))
                + (hour == null ? "" : ":" + hour);
    }

    // ------------------------------------------------------------------
    // Rule 8 — lifecycleInfo
    // ------------------------------------------------------------------

    /**
     * The lifecycle of a LIVE card.
     *
     * <p>Strongest issuer evidence first:</p>
     * <ol>
     *   <li><b>The message that replaced this one</b>, when it is in hand. Its
     *       VTEC action for this alert's own event decides — {@code CAN}
     *       cancelled, {@code EXP} expired, {@code UPG} superseded, anything else
     *       superseded — and, without a matching VTEC, its CAP shape does
     *       ({@link AlertDerivations#endingStateOf}).</li>
     *   <li><b>The alert's own VTEC</b>, when every entry is terminal: a
     *       message whose only word is {@code CAN} is a cancellation.</li>
     *   <li><b>The alert's own fields</b> — {@link AlertDerivations#lifecycleState}:
     *       past its end time is expired; an Update is updated.</li>
     * </ol>
     * <p>All three are the issuer's own statements, so all are verified. Only
     * history can say {@code no_longer_listed}, and it never verifies it.</p>
     *
     * @param knownIds upstream ids of messages in hand, for {@code references[].resolved}
     */
    static AlertCardDto.LifecycleInfo liveLifecycle(NormalizedAlert a, NormalizedAlert replacedBy,
                                                    Set<String> knownIds, Instant now) {
        String base = AlertDerivations.lifecycleState(a, now);
        Vtec own = primaryVtec(a);
        String state;
        String reason;
        String replacedByKey = null;
        String endedAt = null;

        if (replacedBy != null) {
            replacedByKey = AlertIdentity.dedupeKey(replacedBy.source(), replacedBy.id());
            endedAt = blankToNull(replacedBy.sent());
            Vtec edge = edgeVtec(replacedBy, a);
            if (edge != null) {
                state = switch (edge.action()) {
                    case "CAN" -> AlertDerivations.LIFECYCLE_CANCELLED;
                    case "EXP" -> AlertDerivations.LIFECYCLE_EXPIRED;
                    default -> AlertDerivations.LIFECYCLE_SUPERSEDED;
                };
                reason = vtecReason(edge);
            } else {
                state = AlertDerivations.endingStateOf(replacedBy);
                if (AlertDerivations.LIFECYCLE_CANCELLED.equals(state)) {
                    reason = "cancel".equalsIgnoreCase(trim(replacedBy.messageType()))
                            ? "cap_cancel" : "all_clear";
                } else {
                    reason = "cap_update";
                }
            }
        } else if (AlertDerivations.LIFECYCLE_EXPIRED.equals(base)) {
            state = base;
            reason = own != null && "EXP".equals(own.action()) ? "vtec_exp" : "expired";
            endedAt = blankToNull(a.endsAt());
        } else if (own != null && TERMINAL_VTEC.contains(own.action())) {
            state = switch (own.action()) {
                case "CAN" -> AlertDerivations.LIFECYCLE_CANCELLED;
                case "EXP" -> AlertDerivations.LIFECYCLE_EXPIRED;
                default -> AlertDerivations.LIFECYCLE_SUPERSEDED;
            };
            reason = vtecReason(own);
            endedAt = blankToNull(a.sent());
        } else {
            state = base;
            reason = ownReason(a, base, own);
        }

        return new AlertCardDto.LifecycleInfo(
                state,
                isCurrent(state),
                true,
                reason,
                threadKeyOf(a, own),
                referencesOf(a, knownIds),
                replacedByKey,
                updatedAtOf(a),
                endedAt);
    }

    /**
     * The lifecycle of a RECORD whose state the caller already decided — history
     * holds the stored edges that decide it. This adds the evidence: what the
     * alert's own fields support, or else how the caller's state is known.
     * {@code no_longer_listed} is poll absence, the one state that is never
     * verified.
     */
    static AlertCardDto.LifecycleInfo recordLifecycle(NormalizedAlert a, String givenState,
                                                      Set<String> knownIds, Instant now) {
        AlertCardDto.LifecycleInfo own = liveLifecycle(a, null, knownIds, now);
        if (givenState == null || givenState.equals(own.state())) return own;

        boolean verified = true;
        String reason;
        String endedAt = null;
        switch (givenState) {
            case AlertDerivations.LIFECYCLE_NO_LONGER_LISTED -> {
                verified = false;
                reason = "not_listed";
            }
            case AlertDerivations.LIFECYCLE_EXPIRED -> {
                Vtec v = primaryVtec(a);
                reason = v != null && "EXP".equals(v.action()) ? "vtec_exp" : "expired";
                endedAt = blankToNull(a.endsAt());
            }
            // A stored CAP references edge from a later message (history's
            // reverse index): the issuer's statement, not in hand here.
            case AlertDerivations.LIFECYCLE_CANCELLED -> reason = "cap_cancel";
            case AlertDerivations.LIFECYCLE_SUPERSEDED -> reason = "replaced";
            default -> reason = own.reason();
        }
        return new AlertCardDto.LifecycleInfo(
                givenState, isCurrent(givenState), verified, reason,
                own.threadKey(), own.references(), null, own.updatedAt(), endedAt);
    }

    /** CAP said AllClear about this message — in {@code response} or {@code responseTypes}. */
    static boolean isAllClear(NormalizedAlert a) {
        if (a == null) return false;
        if ("allclear".equalsIgnoreCase(trim(a.response()))) return true;
        if (a.responseTypes() != null) {
            for (String r : a.responseTypes()) if ("allclear".equalsIgnoreCase(trim(r))) return true;
        }
        return false;
    }

    static boolean isCurrent(String state) {
        return AlertDerivations.LIFECYCLE_ACTIVE.equals(state)
                || AlertDerivations.LIFECYCLE_UPDATED.equals(state);
    }

    private static String ownReason(NormalizedAlert a, String base, Vtec own) {
        if (own != null) return vtecReason(own);
        return switch (base) {
            case AlertDerivations.LIFECYCLE_UPDATED -> "cap_update";
            case AlertDerivations.LIFECYCLE_SUPERSEDED ->
                    "cancel".equalsIgnoreCase(trim(a.messageType())) ? "cap_cancel" : "all_clear";
            default -> null;
        };
    }

    private static String vtecReason(Vtec v) {
        return "vtec_" + v.action().toLowerCase(Locale.ROOT);
    }

    /**
     * The VTEC entry that speaks for this message: the first one that carries an
     * event on (a message that upgrades a watch and issues the warning is the
     * warning's message), else the first entry, else none.
     */
    static Vtec primaryVtec(NormalizedAlert a) {
        if (a == null || a.vtec() == null || a.vtec().isEmpty()) return null;
        for (Vtec v : a.vtec()) {
            if (v != null && v.action() != null && !TERMINAL_VTEC.contains(v.action())) return v;
        }
        return a.vtec().get(0);
    }

    /** {@code replacing}'s VTEC entry for one of {@code replaced}'s own events, or null. */
    private static Vtec edgeVtec(NormalizedAlert replacing, NormalizedAlert replaced) {
        if (replacing.vtec() == null || replaced.vtecEventKeys() == null
                || replaced.vtecEventKeys().isEmpty()) {
            return null;
        }
        for (Vtec v : replacing.vtec()) {
            String key = v == null ? null : v.eventKey();
            if (key != null && replaced.vtecEventKeys().contains(key)) return v;
        }
        return null;
    }

    /**
     * {@code VTEC:<event key>} when NWS threads the event itself; else
     * {@code CAP:<root>}, the oldest message the CAP references name (or this
     * message, when it names none); else the dedupeKey.
     */
    static String threadKeyOf(NormalizedAlert a, Vtec own) {
        if (own != null && own.eventKey() != null) return "VTEC:" + own.eventKey();
        if ("NWS".equals(AlertIdentity.knownSource(a.source()))) {
            String root = rootReference(a);
            if (root == null) root = blankToNull(a.id());
            if (root != null) return "CAP:" + root.trim();
        }
        return AlertIdentity.dedupeKey(a.source(), a.id());
    }

    private static String rootReference(NormalizedAlert a) {
        List<CapReference> tuples = a.referenceTuples();
        if (tuples != null && !tuples.isEmpty()) {
            CapReference oldest = null;
            Instant oldestAt = null;
            for (CapReference r : tuples) {
                if (r == null || isBlank(r.identifier())) continue;
                Instant at = parseInstant(r.sent());
                if (oldest == null || (at != null && (oldestAt == null || at.isBefore(oldestAt)))) {
                    oldest = r;
                    oldestAt = at;
                }
            }
            if (oldest != null) return oldest.identifier();
        }
        if (a.references() != null) {
            for (String id : a.references()) if (!isBlank(id)) return id;
        }
        return null;
    }

    private static List<AlertCardDto.Reference> referencesOf(NormalizedAlert a, Set<String> knownIds) {
        Collection<String> known = knownIds == null ? Set.of() : knownIds;
        List<AlertCardDto.Reference> out = new ArrayList<>();
        if (a.referenceTuples() != null && !a.referenceTuples().isEmpty()) {
            for (CapReference r : a.referenceTuples()) {
                if (r == null || isBlank(r.identifier())) continue;
                out.add(new AlertCardDto.Reference(r.sender(), r.identifier(), r.sent(),
                        known.contains(r.identifier())));
            }
        } else if (a.references() != null) {
            for (String id : a.references()) {
                if (isBlank(id)) continue;
                out.add(new AlertCardDto.Reference(null, id, null, known.contains(id)));
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    /** USGS's own revision time, else when the message was sent. */
    private static String updatedAtOf(NormalizedAlert a) {
        if (!isBlank(a.revisionUpdatedAt())) return a.revisionUpdatedAt();
        return blankToNull(a.sent());
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /** A plain decimal, never exponent notation, for a URL. */
    private static String num(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static Instant parseInstant(String iso) {
        if (isBlank(iso)) return null;
        try {
            return Instant.parse(iso.trim());
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(iso.trim()).toInstant();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
