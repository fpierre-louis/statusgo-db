package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.service.AlertIngestService.MatchType;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertIngestService.Snapshot;
import io.sitprep.sitprepapi.util.GeoContainment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What official movement guidance is in force for this activation RIGHT NOW.
 *
 * <p>── WHY THIS EXISTS (P0-A) ──────────────────────────────────────────────
 *
 * <p>A {@link PlanActivation} stores its movement directive and governing alert
 * <b>once, at creation</b>. Nothing re-resolved them: not a read path, not a
 * cron — the only scheduled job touching activations is the expiry sweep. So an
 * activation fired under "shelter in place" kept saying shelter in place after
 * officials reversed to evacuation, and kept suppressing the route the household
 * now needed.
 *
 * <p>Stress-test Scenario 24 reproduced it with a chlorine release: shelter and
 * seal, then fifty minutes later leave crosswind by a named route. The stored
 * directive is correct at 4:35 PM and lethal at 5:25 PM, and the product had no
 * path from one to the other.
 *
 * <p><b>It is worst for a link holder.</b> A recipient with no account has no
 * live alert layer behind the payload — the shared plan is the whole of what
 * they can see. So the one reader least able to notice the contradiction is the
 * one being handed it.
 *
 * <p>── THE SHAPE: RESOLVE, DO NOT MUTATE ──────────────────────────────────
 *
 * <p>The stored fields are <b>history</b>: what the household was told when
 * they activated, and worth keeping. This resolver does not overwrite them. It
 * answers a different question — what is in force now — and presentation uses
 * the answer while provenance keeps the record. RC-1's principle is that
 * current official guidance governs what SitPrep presents as the current
 * action; it does not require forgetting what was true an hour ago.
 *
 * <p>── UNDERCLAIMING, DELIBERATELY ────────────────────────────────────────
 *
 * <p>Three outcomes, and two of them are refusals:
 *
 * <ul>
 *   <li>{@link Status#CURRENT} — we looked, and this is what is in force: a
 *       live alert that GOVERNS this point carries the directive, whether or
 *       not it agrees with the stored one. Also the answer for an activation
 *       that never carried a directive and still has none directing it — see
 *       the enum.</li>
 *   <li>{@link Status#UNVERIFIED} — we cannot look: the activation has no
 *       coordinates, or the alert snapshot is unusable. The stored directive is
 *       carried forward because it is the only information there is, but it is
 *       labelled as unverified so no surface can present it as freshly
 *       authoritative.</li>
 *   <li>{@link Status#SUPERSEDED_UNRESOLVED} — we looked, and the alert this
 *       was activated against is no longer in force, with nothing directing in
 *       its place. A stored directive becomes {@code follow_official_instruction}:
 *       it names no protective action SitPrep cannot support, and it still
 *       refuses to let a saved destination stand as the current instruction.
 *       That is the honest middle, and it is the same reasoning
 *       {@code AlertSafetyPolicy.movementDirectiveFromCap} already uses when it
 *       declines to promote CAP {@code Shelter} into a shelter-in-place order.
 *       A stored {@code none} stays {@code none}: there was no instruction to
 *       retire, so narrowing it would invent one (D6).</li>
 * </ul>
 *
 * <p>── WHAT MAY GOVERN ────────────────────────────────────────────────────
 *
 * <p>Not every card in the point's feed. A governing alert must be live
 * ({@code active}/{@code updated}, not past its expiry, and not replaced — a
 * card with a {@code supersededBy} edge has a successor that speaks instead),
 * must carry a directive, and must match this point at <b>containment
 * quality</b> (R4, owner ruling 2026-10-13): an exact {@code zone} match, or a
 * {@code polygon} that {@link GeoContainment} reads as INSIDE. The feed's own
 * {@code polygon} label is not enough: {@code matchTypeFor} gives it when the
 * polygon's first vertex is within 50 miles, which put an evacuation order
 * drawn 40 miles away in charge of a household it does not cover. Statewide
 * and broadcast matches never govern either. Those alerts still show on the
 * alert surfaces; they just do not decide where this household goes.
 *
 * <p>When several govern, the strongest instruction wins — evacuate, then
 * shelter in place, then avoid the area, then follow official instructions —
 * so the answer never depends on snapshot order, and {@code conflicting} says
 * the officials in force disagree (R5).
 *
 * <p>What it never does is keep asserting an obsolete instruction because it
 * used to be right.
 */
@Service
public class ActivationDirectiveResolver {

    /** Lifecycle states that still describe an alert in force. */
    private static final Set<String> LIVE_LIFECYCLE = Set.of("active", "updated");

    private static final String NONE = "none";
    private static final String FOLLOW_OFFICIAL = "follow_official_instruction";

    /**
     * Strongest instruction first (R5). Evacuate outranks shelter because an
     * order to leave is the one that cannot wait for the other to be
     * reconciled; shelter outranks avoid because it is the narrower
     * instruction; follow-official names no action of its own. Anything not
     * listed ranks below all four and so never outranks a real directive.
     */
    private static final List<String> PRECEDENCE = List.of(
            "evacuate", "shelter_in_place", "avoid_area", FOLLOW_OFFICIAL);

    private final AlertFeedService alertFeedService;
    private final AlertIngestService alertIngestService;

    public ActivationDirectiveResolver(AlertFeedService alertFeedService, AlertIngestService alertIngestService) {
        this.alertFeedService = alertFeedService;
        this.alertIngestService = alertIngestService;
    }

    public enum Status {
        /**
         * Checked just now, and this is what is in force. Either a live alert
         * that governs this point carries the directive ({@code alertId} names
         * it), or the activation never carried a directive and nothing live
         * directs it now — then the directive is {@code none}, attributed to
         * the stored alert if that is still in force, else to nothing.
         *
         * <p>That second reading is CURRENT rather than SUPERSEDED_UNRESOLVED
         * on purpose: nothing was superseded. A plain "activate my plan" has no
         * official order behind it to lapse, and labelling it as a lapse is
         * how D6 shipped "Follow official instructions" — and hid plan
         * navigation — on activations no official had ever spoken to. The
         * client already reads CURRENT with {@code changed=false} and no
         * governing alert as "nothing to caveat".
         */
        CURRENT,
        /** Could not be checked; the stored directive is carried, labelled. */
        UNVERIFIED,
        /**
         * Checked, and the alert this was activated against is no longer in
         * force, with nothing governing in its place. A stored directive is
         * narrowed to {@code follow_official_instruction}; a stored
         * {@code none} stays {@code none}.
         */
        SUPERSEDED_UNRESOLVED
    }

    /**
     * @param directive       what to PRESENT as the current movement guidance
     * @param status          how much weight that directive may be given
     * @param changed         does it differ from what the household activated under
     * @param asActivated     the stored directive, preserved for provenance
     * @param source          issuer of the governing alert now in force, or null
     * @param alertId         its id, or null
     * @param event           its event name, or null
     * @param headline        its headline, or null
     * @param lifecycleState  its lifecycle, or null
     * @param resolvedAt      when this answer was computed
     * @param conflicting     more than one alert governs this point and they
     *                        do not carry the same directive; {@code directive}
     *                        is the strongest of them (R5). Only alerts that
     *                        may govern count — a distant or statewide order
     *                        neither wins nor conflicts.
     */
    public record Resolved(
            String directive,
            Status status,
            boolean changed,
            String asActivated,
            String source,
            String alertId,
            String event,
            String headline,
            String lifecycleState,
            Instant resolvedAt,
            boolean conflicting
    ) {
        /** No conflict — every outcome but a disagreeing CURRENT. */
        public Resolved(String directive, Status status, boolean changed, String asActivated, String source,
                        String alertId, String event, String headline, String lifecycleState, Instant resolvedAt) {
            this(directive, status, changed, asActivated, source, alertId, event, headline, lifecycleState,
                    resolvedAt, false);
        }

        public boolean isCurrent() { return status == Status.CURRENT; }
    }

    /** Resolve against the activation's own point. */
    public Resolved resolve(PlanActivation a) {
        return resolve(a, null, null);
    }

    /**
     * Resolve, falling back to {@code fallbackLat/Lng} when the activation
     * carries no coordinates of its own.
     *
     * <p>That fallback is load-bearing rather than defensive: {@code location}
     * is optional on the create request, so a large share of real activations
     * have no point at all. Without somewhere to look, every one of them would
     * answer UNVERIFIED forever and the re-resolution would be dead code in
     * production. The household's own location is the right stand-in — it is
     * where the plan is about.
     */
    public Resolved resolve(PlanActivation a, Double fallbackLat, Double fallbackLng) {
        if (a == null) return unverifiedFrom(null);
        String stored = normalize(a.getMovementDirective());
        Instant now = Instant.now();

        Double lat = a.getLat() != null ? a.getLat() : fallbackLat;
        Double lng = a.getLng() != null ? a.getLng() : fallbackLng;

        if (lat == null || lng == null) {
            // Nothing to resolve against. Carry the stored directive, say so.
            return unverified(a, stored, now);
        }

        List<AlertCardDto> live;
        try {
            AlertFeedResponse feed = alertFeedService.feedFor(lat, lng);
            live = feed == null || feed.alerts() == null ? List.of() : feed.alerts().stream()
                    .filter(c -> isLive(c, now))
                    .toList();
        } catch (RuntimeException ex) {
            // A failure to READ the feed is not evidence that guidance changed.
            return unverified(a, stored, now);
        }

        // Only an alert that may govern THIS point is a candidate (R4), and
        // among those the strongest instruction wins (R5). Severity does not
        // enter into it: a directive is an instruction not to proceed, and
        // severity does not rank instructions. Precedence does, and it makes
        // the answer independent of snapshot order; the first-match rule this
        // replaced answered with whichever card the snapshot listed first.
        List<AlertCardDto> directing = live.stream()
                .filter(c -> !NONE.equals(directiveOf(c)))
                .toList();
        Map<String, Object> geometries = directing.stream()
                .anyMatch(c -> MatchType.POLYGON.wire().equals(matchTypeOf(c)))
                ? geometryIndex() : Map.of();
        double pointLat = lat;
        double pointLng = lng;
        List<AlertCardDto> governing = directing.stream()
                .filter(c -> governsPoint(c, pointLat, pointLng, geometries))
                .toList();

        String storedAlertId = blankToNull(a.getGoverningAlertId());
        AlertCardDto directed = governing.stream()
                .min(Comparator.comparingInt((AlertCardDto c) -> rank(directiveOf(c)))
                        // Same instruction from two alerts: keep the one the
                        // household activated under, so the attribution does
                        // not churn, then fall back to id for determinism.
                        .thenComparing(c -> !Objects.equals(c.id(), storedAlertId))
                        .thenComparing(c -> c.id() == null ? "" : c.id()))
                .orElse(null);

        if (directed != null) {
            String current = directiveOf(directed);
            boolean conflicting = governing.stream().map(this::directiveOf).distinct().count() > 1;
            return new Resolved(
                    current,
                    Status.CURRENT,
                    !current.equals(stored),
                    stored,
                    directed.source(),
                    directed.id(),
                    directed.eventType() != null ? directed.eventType() : directed.eventLabel(),
                    directed.headline(),
                    directed.lifecycleState(),
                    now,
                    conflicting);
        }

        // The alert the household activated under may still be listed even
        // though it cannot govern here (its polygon no longer contains the
        // point, say). Carry its REAL lifecycle so the stored record is never
        // labelled "expired" — "no longer in force" — while it is in force.
        String storedLifecycle = storedAlertId == null ? null : live.stream()
                .filter(c -> storedAlertId.equals(c.id()))
                .map(AlertCardDto::lifecycleState)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (!NONE.equals(stored)) {
            // The household activated under an instruction and nothing in
            // force carries one now. Do not keep asserting it; decline to name
            // one instead.
            boolean changed = !FOLLOW_OFFICIAL.equals(stored);
            return new Resolved(FOLLOW_OFFICIAL, Status.SUPERSEDED_UNRESOLVED, changed,
                    stored, null, null, null, null, storedLifecycle, now);
        }

        // ── D6: STORED NONE, NOTHING DIRECTING ─────────────────────────────
        // There was no instruction to retire, so there is nothing to narrow:
        // "none" is still the true answer. What is left is labelling it.
        AlertCardDto stillInForce = storedAlertId == null ? null : live.stream()
                .filter(c -> storedAlertId.equals(c.id()) && NONE.equals(directiveOf(c)))
                .findFirst()
                .orElse(null);
        if (stillInForce != null) {
            // The alert it was activated under (a Watch, say) is still in force
            // and still carries no movement directive.
            return new Resolved(NONE, Status.CURRENT, false, stored,
                    stillInForce.source(), stillInForce.id(),
                    stillInForce.eventType() != null ? stillInForce.eventType() : stillInForce.eventLabel(),
                    stillInForce.headline(), stillInForce.lifecycleState(), now);
        }
        if (storedAlertId != null || blankToNull(a.getGoverningAlertEvent()) != null) {
            // Activated under an alert that has since lapsed (or, when
            // storedLifecycle is set, one still listed that no longer governs).
            return new Resolved(NONE, Status.SUPERSEDED_UNRESOLVED, false,
                    stored, null, null, null, null, storedLifecycle, now);
        }
        // A plain activation: no alert behind it then, none directing it now.
        return new Resolved(NONE, Status.CURRENT, false, stored, null, null, null, null, null, now);
    }

    /**
     * The conservative answer when no resolver is available at all: carry the
     * stored directive, labelled UNVERIFIED. Used as a fallback so a missing or
     * mocked collaborator degrades to "we could not check" rather than to a
     * confident claim.
     */
    public static Resolved unverifiedFrom(PlanActivation a) {
        String stored = normalize(a == null ? null : a.getMovementDirective());
        return new Resolved(stored, Status.UNVERIFIED, false, stored,
                a == null ? null : a.getGoverningAlertSource(),
                a == null ? null : a.getGoverningAlertId(),
                a == null ? null : a.getGoverningAlertEvent(),
                a == null ? null : a.getGoverningAlertHeadline(),
                a == null ? null : a.getGoverningAlertLifecycleState(),
                Instant.now());
    }

    private Resolved unverified(PlanActivation a, String stored, Instant now) {
        return new Resolved(
                stored,
                Status.UNVERIFIED,
                false,
                stored,
                a == null ? null : a.getGoverningAlertSource(),
                a == null ? null : a.getGoverningAlertId(),
                a == null ? null : a.getGoverningAlertEvent(),
                a == null ? null : a.getGoverningAlertHeadline(),
                a == null ? null : a.getGoverningAlertLifecycleState(),
                now);
    }

    /**
     * In force: a live lifecycle, not past its own expiry, and not replaced.
     *
     * <p>{@code supersededBy} is checked separately from the lifecycle because
     * the two are derived apart: the lifecycle comes from the card's own CAP
     * fields, the replacement edge from a later message in the same snapshot.
     * A card can read {@code active} by the first and still have a successor
     * by the second — and then the successor is what officials are saying.
     */
    private boolean isLive(AlertCardDto c, Instant now) {
        if (c == null) return false;
        if (c.supersededBy() != null) return false;
        String lifecycle = c.lifecycleState() == null ? "" : c.lifecycleState().toLowerCase(Locale.ROOT);
        if (!LIVE_LIFECYCLE.contains(lifecycle)) return false;
        if (c.expiresAt() == null || c.expiresAt().isBlank()) return true;
        try {
            return Instant.parse(c.expiresAt()).isAfter(now);
        } catch (RuntimeException ex) {
            // An unparseable expiry is not a reason to discard a live alert.
            return true;
        }
    }

    /**
     * May this card govern the movement directive at this point (R4)? Only a
     * containment-quality match: an exact zone match, or a polygon that
     * actually contains the point. A {@code polygon} label alone is
     * proximity — see the class javadoc — so it is re-tested here, and a
     * geometry we cannot read or cannot find is UNKNOWN, which does not
     * govern. Neither does a state-prefix or broadcast match, or a card that
     * gives no match reason at all.
     */
    private boolean governsPoint(AlertCardDto c, double lat, double lng, Map<String, Object> geometryById) {
        String match = matchTypeOf(c);
        if (MatchType.ZONE.wire().equals(match)) return true;
        if (!MatchType.POLYGON.wire().equals(match)) return false;
        Object geometry = c.id() == null ? null : geometryById.get(c.id());
        return GeoContainment.locate(lat, lng, geometry) == GeoContainment.Containment.INSIDE;
    }

    private static String matchTypeOf(AlertCardDto c) {
        return c == null || c.location() == null || c.location().matchType() == null
                ? null
                : c.location().matchType().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Alert geometry by id, read from the ingest snapshot. The card does not
     * carry geometry, and the snapshot is the record it was built from. Read
     * only when a polygon card needs it. A snapshot that rolled between the
     * feed read and this one simply lacks the id, and that reads UNKNOWN — so
     * the race can only cost a directive, never invent one.
     */
    private Map<String, Object> geometryIndex() {
        Map<String, Object> out = new HashMap<>();
        try {
            Snapshot snap = alertIngestService == null ? null : alertIngestService.getSnapshot();
            if (snap == null || snap.alerts() == null) return out;
            for (NormalizedAlert n : snap.alerts()) {
                if (n != null && n.id() != null && n.geometry() != null) out.putIfAbsent(n.id(), n.geometry());
            }
        } catch (RuntimeException ex) {
            // Unreadable geometry is UNKNOWN, and UNKNOWN does not govern.
        }
        return out;
    }

    private static int rank(String directive) {
        int i = PRECEDENCE.indexOf(directive);
        return i < 0 ? PRECEDENCE.size() : i;
    }

    private String directiveOf(AlertCardDto c) {
        if (c == null || c.safety() == null) return NONE;
        return normalize(c.safety().movementDirective());
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
