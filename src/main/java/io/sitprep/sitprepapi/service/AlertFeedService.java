package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.LocationFreshness;
import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.resource.AppConfigResource;
import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import io.sitprep.sitprepapi.util.AlertIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 *       (P0-5);</li>
 *   <li>how to present it — {@link AlertInterpreter}, handed the one
 *       {@link Assessment} per alert this class already made (S2 C2). It
 *       never asks the policy again.</li>
 * </ul>
 *
 * <p>Re-deriving any of those here would create a fifth place that knows how to
 * tell a Watch from a Warning. There were already four, and that is what the
 * epic has spent its time undoing.</p>
 */
@Service
public class AlertFeedService {

    private static final Logger log = LoggerFactory.getLogger(AlertFeedService.class);

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
    /** Null in unit tests that build the service by hand: no community threads then. */
    private final AlertPostRepo alertPostRepo;
    /**
     * Source of each person-facing card's {@code interpretationRef} — a cache
     * read, never a query (S2 C3). Null in unit tests that build the service by
     * hand: no {@code interpretationRef} then.
     */
    private final InterpretationRecorder recorder;

    /** Serve-time clock. A field so a test can pin it. */
    Clock clock = Clock.systemUTC();

    @Autowired
    public AlertFeedService(AlertIngestService ingest, AlertDispatchService dispatch,
                            AlertPostRepo alertPostRepo, InterpretationRecorder recorder) {
        this.ingest = ingest;
        this.dispatch = dispatch;
        this.alertPostRepo = alertPostRepo;
        this.recorder = recorder;
    }

    /** Without the interpretation snapshots — for tests with no recorder. */
    public AlertFeedService(AlertIngestService ingest, AlertDispatchService dispatch,
                            AlertPostRepo alertPostRepo) {
        this(ingest, dispatch, alertPostRepo, null);
    }

    /** Without the community-thread lookup — for callers and tests with no repository. */
    public AlertFeedService(AlertIngestService ingest, AlertDispatchService dispatch) {
        this(ingest, dispatch, null);
    }

    /** The feed for a coordinate, with no claim about how old that coordinate is. */
    public AlertFeedResponse feedFor(double lat, double lng) {
        return feedFor(lat, lng, null);
    }

    /**
     * The DECISION feed for a coordinate captured at {@code fixedAt}: every
     * existing card field, none of the S2 interpretation blocks.
     *
     * <p>This is the overload the internal safety readers call —
     * {@code ConcealmentSafetyService} (once per push recipient, through
     * {@code NotificationService.shouldSendSilently} and the Live Activity
     * pusher) and {@code ActivationDirectiveResolver} (and
     * {@code PlanActivationService} through it).
     * They read {@code lifecycleState}, {@code tier} and {@code safety} and
     * nothing else, so they get an in-memory build with no repository call
     * and no per-card fingerprint or containment test: during an emergency
     * that path runs once per person being notified. A reader that serves a
     * card to a person uses {@link #feedForViewer}.</p>
     *
     * <p>{@code fixedAt} is optional and a null one is honestly reported as
     * "unknown" rather than quietly treated as fresh — see
     * {@link AlertFeedResponse.LocationAge}.</p>
     */
    public AlertFeedResponse feedFor(double lat, double lng, Instant fixedAt) {
        return build(lat, lng, fixedAt, AlertInterpreter.CONTEXT_CURRENT, false);
    }

    /**
     * The feed a PERSON reads ({@code GET /api/alerts/feed}): the decision feed
     * above plus the S2 card contract — interpreter, presentation, actions,
     * lifecycle evidence, official area, viewer context and the community
     * threads (one batched repository call per response).
     *
     * <p>{@code context} says whose coordinate this is: {@code current} (the
     * default — the device's location) or {@code home} (the household's). It
     * decides {@code viewerContext.role} on every card and is echoed in
     * {@code meta.context}; it changes nothing about which alerts match.</p>
     */
    public AlertFeedResponse feedForViewer(double lat, double lng, Instant fixedAt, String context) {
        return build(lat, lng, fixedAt, AlertInterpreter.normalizeContext(context), true);
    }

    private AlertFeedResponse build(double lat, double lng, Instant fixedAt, String ctx, boolean forViewer) {
        int radiusMi = AppConfigResource.alertsRadiusMi();
        double radiusKm = radiusMi * 1.609344;
        AlertInterpreter.Viewer viewer = new AlertInterpreter.Viewer(lat, lng, fixedAt, ctx);

        Snapshot snap = ingest.getSnapshot();
        Set<String> userZones = ingest.zoneCodesForPoint(lat, lng);
        Set<String> userStates = AlertIngestService.statePrefixesOf(userZones);

        // Built from the WHOLE snapshot, not from the matched subset: an alert
        // can be replaced by one that does not match this viewer's point, and
        // the edge is a fact about the alert rather than about the reader.
        Map<String, NormalizedAlert> supersededBy =
                AlertDerivations.supersessionIndex(snap.alerts());
        Set<String> knownIds = new HashSet<>();
        if (forViewer) {
            for (NormalizedAlert a : snap.alerts()) {
                if (a != null && a.id() != null) knownIds.add(a.id());
            }
        }

        // Pass 1 — decide and match. One template lookup and one policy decision
        // per alert, handed to everything below. The filter, the card and the
        // life-threatening flag used to ask separately — three answers that
        // only agreed because nothing had changed between the calls yet.
        record Matched(NormalizedAlert alert, MatchType match, Assessment assessed) {}
        List<Matched> matched = new ArrayList<>();
        for (NormalizedAlert a : snap.alerts()) {
            Assessment assessed = assess(a);
            if (assessed.decision().dispatchMode() == AlertSafetyPolicy.DispatchMode.SUPPRESS) {
                continue;
            }
            MatchType match = AlertIngestService.matchTypeFor(
                    a, lat, lng, radiusKm, userZones, userStates);
            if (match == null) continue;
            matched.add(new Matched(a, match, assessed));
        }

        // Pass 2 — ONE batched lookup of the community threads for every card,
        // and only for a person's feed: no internal safety reader looks at them.
        Map<String, List<Long>> threads = forViewer
                ? communityThreadsFor(matched.stream().map(Matched::alert).toList())
                : Map.of();

        List<AlertCardDto> cards = new ArrayList<>(matched.size());
        for (Matched m : matched) {
            NormalizedAlert a = m.alert();
            cards.add(liveCard(a, m.match(), radiusMi, supersededBy.get(a.id()), m.assessed(),
                    forViewer ? new Reader(viewer, knownIds, threads.getOrDefault(a.id(), List.of())) : null));
        }

        return new AlertFeedResponse(List.copyOf(cards), metaFor(snap, fixedAt, ctx));
    }

    /**
     * Readable dispatched community posts per alert id, in ONE repository call.
     *
     * <p>Keyed the dispatcher's way: {@code alert_post.alert_id} stores the
     * legacy {@code SOURCE-id} form ({@link AlertIdentity#toLegacyDispatchKey}),
     * so a bare id or a {@code SOURCE:id} key finds nothing. Each alert asks
     * for its own id and every id its CAP {@code references} name — the
     * dispatcher's post keeps the id it was created from, and NWS replaces that
     * message with a new one, so the discussion is usually filed under an
     * earlier revision of the same chain.</p>
     *
     * <p>A failed lookup costs the threads, never the feed: this is enrichment
     * on a life-safety surface.</p>
     */
    Map<String, List<Long>> communityThreadsFor(List<NormalizedAlert> alerts) {
        if (alertPostRepo == null || alerts == null || alerts.isEmpty()) return Map.of();
        Map<String, Set<String>> alertIdsByKey = new HashMap<>();
        for (NormalizedAlert a : alerts) {
            if (a == null || a.id() == null) continue;
            List<String> chain = new ArrayList<>();
            chain.add(a.id());
            if (a.references() != null) chain.addAll(a.references());
            for (String upstream : chain) {
                String key = AlertIdentity.toLegacyDispatchKey(a.source(), upstream);
                if (key != null) alertIdsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(a.id());
            }
        }
        if (alertIdsByKey.isEmpty()) return Map.of();

        List<AlertPostRepo.ThreadPostRef> rows;
        try {
            rows = alertPostRepo.findReadableThreadPosts(alertIdsByKey.keySet(), SystemAccounts.SITPREP_EMAIL);
        } catch (RuntimeException e) {
            log.warn("AlertFeed: community thread lookup failed; serving cards without threads: {}", e.toString());
            return Map.of();
        }
        Map<String, TreeSet<Long>> byAlert = new HashMap<>();
        for (AlertPostRepo.ThreadPostRef r : rows == null ? List.<AlertPostRepo.ThreadPostRef>of() : rows) {
            if (r == null || r.getPostId() == null) continue;
            for (String alertId : alertIdsByKey.getOrDefault(r.getAlertId(), Set.of())) {
                byAlert.computeIfAbsent(alertId, k -> new TreeSet<>()).add(r.getPostId());
            }
        }
        Map<String, List<Long>> out = new HashMap<>();
        byAlert.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    AlertFeedResponse.Meta metaFor(Snapshot snap) {
        return metaFor(snap, null);
    }

    AlertFeedResponse.Meta metaFor(Snapshot snap, Instant fixedAt) {
        return metaFor(snap, fixedAt, AlertInterpreter.CONTEXT_CURRENT);
    }

    AlertFeedResponse.Meta metaFor(Snapshot snap, Instant fixedAt, String context) {
        Instant last = snap == null ? null : snap.lastSuccessAt();
        boolean stale = last == null || last.isBefore(Instant.now().minus(STALE_AFTER));
        return new AlertFeedResponse.Meta(
                last == null ? null : last.toString(),
                stale,
                AlertFeedResponse.COVERAGE_CAVEAT,
                locationAgeFor(fixedAt),
                AlertInterpreter.normalizeContext(context));
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
        return liveCard(a, match, radiusMi, replacedBy, assessed, new Reader(null, Set.of(), List.of()));
    }

    /**
     * The viewer-free card {@code InterpretationRecorder} snapshots: the same
     * live-card path a person's feed takes, with no viewer point and no
     * community threads, so the recorded interpreter, presentation, source
     * identity and lifecycle are exactly what a reader is shown — minus
     * everything that depends on who is reading. Built at the recorder's
     * {@code now}, the instant its fingerprint's lifecycle mode was read at.
     */
    AlertCardDto interpretationCard(NormalizedAlert a, NormalizedAlert replacedBy,
                                    Assessment assessed, Set<String> knownIds, Instant now) {
        return liveCard(a, MatchType.BROADCAST, AppConfigResource.alertsRadiusMi(), replacedBy, assessed,
                new Reader(null, knownIds == null ? Set.of() : knownIds, List.of()), now);
    }

    /**
     * One LIVE alert as a person's feed would show it, re-evaluated now — the
     * canonical record lookup's live answer (S2 C4). The same path as
     * {@link #feedForViewer}: the same decision, the same replacing message
     * ({@code replacedBy}, from the snapshot's supersession index), the same
     * community-thread lookup (one repository call). Two differences, both
     * because the caller asked for this alert by key rather than by point:
     * {@code match} may be null (the alert does not apply at the reader's
     * point, or no point was given), and {@code knownIds} also holds the
     * stored messages of the alert's thread.
     */
    AlertCardDto viewerLiveCard(NormalizedAlert a, MatchType match, NormalizedAlert replacedBy,
                                AlertInterpreter.Viewer viewer, Set<String> knownIds) {
        List<Long> posts = communityThreadsFor(List.of(a)).getOrDefault(a.id(), List.of());
        return liveCard(a, match, AppConfigResource.alertsRadiusMi(), replacedBy, assess(a),
                new Reader(viewer, knownIds == null ? Set.of() : knownIds, posts));
    }

    /**
     * The all-clear reading a card's tone and its snapshot fingerprint share:
     * CAP AllClear on the alert or on the message that ended it.
     */
    static boolean allClearOf(NormalizedAlert a, NormalizedAlert endedBy) {
        return AlertInterpreter.isAllClear(a) || AlertInterpreter.isAllClear(endedBy);
    }

    /**
     * What a person's feed adds to a card: whose point it is, which messages
     * are in hand (for {@code references[].resolved}) and the thread posts.
     * Null for the internal decision feed, which gets no interpretation.
     */
    private record Reader(AlertInterpreter.Viewer viewer, Set<String> knownIds, List<Long> communityPostIds) {}

    /**
     * A live card.
     *
     * <p><b>{@code lifecycleState} and record-ness are the SHARED derivation</b>
     * — the replacing message's CAP shape ({@link AlertDerivations#endingStateOf})
     * or the alert's own fields ({@link AlertDerivations#lifecycleState}) — the
     * same answer the dispatcher, the risk profile, {@code ConcealmentSafetyService}
     * and {@code ActivationDirectiveResolver} act on. That shared answer reads
     * the alert's own all-terminal VTEC ({@link AlertDerivations#vtecEndingState},
     * P1), the same rule {@link AlertSafetyPolicy#lifecycleBlockReason} blocks
     * on. The interpreter's fuller VTEC reading ({@link AlertInterpreter#liveLifecycle})
     * goes into {@code lifecycleInfo} and the presentation blocks only, where it
     * can only make a card LESS current, never more.</p>
     */
    private AlertCardDto liveCard(NormalizedAlert a, MatchType match, int radiusMi,
                                  NormalizedAlert replacedBy, Assessment assessed, Reader reader) {
        return liveCard(a, match, radiusMi, replacedBy, assessed, reader, clock.instant());
    }

    private AlertCardDto liveCard(NormalizedAlert a, MatchType match, int radiusMi,
                                  NormalizedAlert replacedBy, Assessment assessed, Reader reader,
                                  Instant now) {
        // Record-ness here is the rule InterpretationRecorder.LifecycleMode.live
        // fingerprints with: a record exactly when something replaced it.
        boolean record = replacedBy != null;
        String lifecycleState = record
                ? AlertDerivations.endingStateOf(replacedBy)
                : AlertDerivations.lifecycleState(a, now);
        Interpretation interpretation = reader == null ? null : new Interpretation(
                AlertInterpreter.liveLifecycle(a, replacedBy, reader.knownIds(), now),
                allClearOf(a, replacedBy),
                reader.viewer(), reader.communityPostIds(), now);
        return card(a, match, radiusMi, replacedBy, assessed, lifecycleState, record, interpretation);
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
        return toRecordCard(a, match, radiusMi, lifecycleState, null, null);
    }

    /**
     * As above, with the reader's point for {@code viewerContext} and, when the
     * caller holds it, the message that ENDED this one ({@code endedBy} — the
     * stored edge's source). It lets {@code lifecycleInfo} say how the alert
     * ended: {@code all_clear} versus {@code cap_cancel}, the VTEC action, and
     * the {@code all_clear} tone. Without it a {@code cancelled} record is still
     * verified, but its reason is null rather than a guess.
     */
    AlertCardDto toRecordCard(NormalizedAlert a, MatchType match, int radiusMi, String lifecycleState,
                              AlertInterpreter.Viewer viewer, NormalizedAlert endedBy) {
        return toRecordCard(a, match, radiusMi, lifecycleState, viewer, endedBy, Set.of());
    }

    /**
     * As above, with the upstream ids of the messages the caller holds, so
     * {@code lifecycleInfo.references[].resolved} says which of the alert's
     * references are in hand (S2 C4: history's loaded rows, the record
     * lookup's thread). {@code match} may be null — a record looked up by key
     * from a point it does not apply at.
     */
    AlertCardDto toRecordCard(NormalizedAlert a, MatchType match, int radiusMi, String lifecycleState,
                              AlertInterpreter.Viewer viewer, NormalizedAlert endedBy,
                              Set<String> knownIds) {
        Instant now = clock.instant();
        Interpretation interpretation = new Interpretation(
                AlertInterpreter.recordLifecycle(a, lifecycleState, endedBy,
                        knownIds == null ? Set.of() : knownIds, now),
                allClearOf(a, endedBy),
                viewer, List.of(), now);
        return card(a, match, radiusMi, null, assess(a), lifecycleState, true, interpretation);
    }

    /** The interpretation inputs a person's card carries; null on the decision feed. */
    private record Interpretation(AlertCardDto.LifecycleInfo lifecycle, boolean allClear,
                                  AlertInterpreter.Viewer viewer, List<Long> communityPostIds,
                                  Instant now) {}

    private AlertCardDto card(NormalizedAlert a, MatchType match, int radiusMi,
                              NormalizedAlert replacedBy, Assessment assessed,
                              String lifecycleState, boolean record, Interpretation interpretation) {
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
        String tier = tierOf(a, tpl);
        AlertCardDto.Safety safety = record ? recordSafetyOf(decision, lifecycleState) : safetyOf(decision);

        AlertInterpreter.Output interp = interpretation == null
                ? AlertInterpreter.Output.NONE
                : AlertInterpreter.interpret(new AlertInterpreter.Input(
                        a, assessed, record, match, tier, headline, whatToDo, precautions, precautionsSource,
                        safety, interpretation.lifecycle(), interpretation.allClear(), interpretation.viewer(),
                        interpretation.communityPostIds(), interpretation.now()));

        return new AlertCardDto(
                a.id(),
                a.source() == null ? null : a.source().toLowerCase(Locale.ROOT),
                a.event(),
                headline != null ? headline : a.event(),
                tier,
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
                        // Null only on a card looked up by key (S2 C4) from a
                        // point the alert does not apply at: not relevant here.
                        match == null ? null : match.wire(),
                        AlertDerivations.geometryConfidence(a),
                        AlertDerivations.areaLabel(a),
                        placeOf(a)),
                radiusMi,
                a.startedAt(),
                a.endsAt(),                       // null stays null — see the DTO
                ATTRIBUTION.get(a.source()),
                detailOf(a),
                safety,
                interp.dedupeKey(),
                interp.sourceRevisionRef(),
                interpretationRefOf(interpretation, interp, assessed, a, record),
                interpretation == null ? null : AlertInterpreter.VERSION,
                interp.sourceIdentity(),
                interpretation == null ? null : interpretation.lifecycle(),
                interp.clusterKey(),
                interp.interpreter(),
                interp.presentation(),
                interp.actions(),
                interp.officialArea(),
                interp.viewerContext(),
                interp.communityThreads());
    }

    /**
     * The recorded snapshot of exactly this interpretation, from the
     * recorder's cache (S2 C3). Never a query: a feed of N cards costs N hash
     * computations and N map reads. Null on the decision feed, without a
     * recorder, and when the decision or the lifecycle has moved since the
     * last ingest tick recorded it — a card never points at a snapshot that
     * says something else.
     *
     * <p>The fingerprint carries the card's own record flag and lifecycle
     * verdict, so a record — a history card, or a live message already
     * replaced — names only a snapshot recorded as that record, never the
     * live one with its {@code current=true} and primary action. A history
     * card of an alert the feed never showed replaced gets null.</p>
     */
    private String interpretationRefOf(Interpretation interpretation, AlertInterpreter.Output interp,
                                       Assessment assessed, NormalizedAlert a, boolean record) {
        if (interpretation == null || recorder == null || interp.sourceRevisionRef() == null) return null;
        return recorder.cachedSnapshotId(InterpretationRecorder.snapshotFingerprint(
                interp.sourceRevisionRef(), assessed, a,
                InterpretationRecorder.LifecycleMode.of(record, interpretation.lifecycle(), interpretation.allClear())));
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
