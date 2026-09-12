package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 *   <li>{@link Status#CURRENT} — a live alert for this activation's point
 *       carries a directive. It governs, whether or not it agrees with the
 *       stored one.</li>
 *   <li>{@link Status#UNVERIFIED} — we cannot look: the activation has no
 *       coordinates, or the alert snapshot is unusable. The stored directive is
 *       carried forward because it is the only information there is, but it is
 *       labelled as unverified so no surface can present it as freshly
 *       authoritative.</li>
 *   <li>{@link Status#SUPERSEDED_UNRESOLVED} — we looked, and nothing in force
 *       carries a directive. The governing alert expired with no successor. The
 *       directive becomes {@code follow_official_instruction}: it names no
 *       protective action SitPrep cannot support, and it still refuses to let a
 *       saved destination stand as the current instruction. That is the honest
 *       middle, and it is the same reasoning
 *       {@code AlertSafetyPolicy.movementDirectiveFromCap} already uses when it
 *       declines to promote CAP {@code Shelter} into a shelter-in-place order.</li>
 * </ul>
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

    private final AlertFeedService alertFeedService;

    public ActivationDirectiveResolver(AlertFeedService alertFeedService) {
        this.alertFeedService = alertFeedService;
    }

    public enum Status {
        /** A live official alert carries this directive. */
        CURRENT,
        /** Could not be checked; the stored directive is carried, labelled. */
        UNVERIFIED,
        /** Checked, and nothing in force carries a directive. */
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
            Instant resolvedAt
    ) {
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

        // Same selection rule the client uses when it creates an activation: the
        // first alert carrying a real directive governs, and severity order only
        // breaks ties among those. A directive is an instruction NOT to proceed,
        // and severity does not rank instructions.
        AlertCardDto directed = live.stream()
                .filter(c -> !NONE.equals(directiveOf(c)))
                .findFirst()
                .orElse(null);

        if (directed == null) {
            // We looked and found nothing in force. Do not keep asserting the
            // stored instruction; decline to name one instead.
            boolean changed = !FOLLOW_OFFICIAL.equals(stored);
            return new Resolved(FOLLOW_OFFICIAL, Status.SUPERSEDED_UNRESOLVED, changed,
                    stored, null, null, null, null, null, now);
        }

        String current = directiveOf(directed);
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
                now);
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

    /** In force: a live lifecycle, and not past its own expiry. */
    private boolean isLive(AlertCardDto c, Instant now) {
        if (c == null) return false;
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

    private String directiveOf(AlertCardDto c) {
        if (c == null || c.safety() == null) return NONE;
        return normalize(c.safety().movementDirective());
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return NONE;
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
