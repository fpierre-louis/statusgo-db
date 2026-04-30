package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Push policy chokepoint. Implements the lane decision model from
 * {@code docs/PUSH_NOTIFICATION_POLICY.md}: every notifiable event
 * routes through {@link #evaluate} and gets back exactly one of
 * {@link Lane#A} (push), {@link Lane#B} (inbox-only), {@link Lane#C}
 * (ephemeral), {@link Lane#DROP}.
 *
 * <p>The policy doc gives the full rule set; this service is the
 * operational mechanism. Rules applied in order:</p>
 * <ol>
 *   <li><b>Master switches</b> — {@code pushEnabled} / {@code inboxEnabled}
 *       short-circuit. Push off → all Lane-A categories drop to Lane B.
 *       Inbox off → drop to Lane C or DROP.</li>
 *   <li><b>Per-category opt-out</b> — user muted earthquakes? Lane B
 *       (still inboxed unless inbox is also off).</li>
 *   <li><b>Quiet hours</b> — Lane A pushes during the user's quiet
 *       window defer to inbox UNLESS in {@link #CRITICAL_BYPASS}.</li>
 *   <li><b>Default</b> — return the category's documented lane.</li>
 * </ol>
 *
 * <p><b>Rate caps</b> (1/60s per source, 4/5min total, 20/day soft) and
 * <b>NotificationService.send wiring</b> are deliberately out of scope
 * for this skeleton — they land in follow-up sessions per the policy
 * doc's implementation checklist. This service is callable now via
 * {@link #evaluate}; integrating it into the send sites is the next
 * step.</p>
 */
@Service
public class PushPolicyService {

    private static final Logger log = LoggerFactory.getLogger(PushPolicyService.class);

    /**
     * Categories that bypass quiet hours per the policy doc's
     * "Critical bypass list". A hurricane warning at 2am is the
     * literal use case; the rest can wait until morning.
     */
    private static final Set<Category> CRITICAL_BYPASS = EnumSet.of(
            Category.NWS_SEVERE_EXTREME,        // limited to severity Extreme below
            Category.USGS_QUAKE_MAJOR,          // limited to mag >= 6.0 below
            Category.PLAN_ACTIVATION_RECEIVED,
            Category.GROUP_ALERT_HOUSEHOLD
    );

    private final UserAlertPreferenceRepo repo;
    private final RateLimiterService rateLimiter;

    public PushPolicyService(UserAlertPreferenceRepo repo, RateLimiterService rateLimiter) {
        this.repo = repo;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Evaluate the lane for one notifiable event.
     *
     * @param userEmail the recipient (case-insensitive; null/blank → DROP)
     * @param category  the structured category from
     *                  {@code NOTIFICATIONS_INBOX.md}'s vocabulary
     * @param severity  source-specific severity used for critical-bypass
     *                  judgments (NWS "Extreme" or USGS magnitude as a
     *                  decimal string). May be null when the category
     *                  doesn't carry a severity.
     * @return the decided lane; never null
     */
    @Transactional
    public Lane evaluate(String userEmail, Category category, String severity) {
        if (userEmail == null || userEmail.isBlank() || category == null) return Lane.DROP;
        UserAlertPreference pref = getOrCreate(userEmail);

        // -- per-category opt-out applied first, before master switches,
        //    so a user who muted earthquakes drops it entirely (not even
        //    inboxed). Per spec: "the inbox is the audit log for Lane A
        //    when push is suppressed by quiet hours / rate caps" — opt-
        //    out is a stronger user signal than quiet hours, so honor it.
        if (!isCategoryEnabled(pref, category)) {
            return Lane.DROP;
        }

        Lane base = defaultLaneFor(category);

        // -- master switches:
        //    push off → Lane A demotes to Lane B (inboxed but silent).
        //    inbox off → strip to Lane C (ephemeral banner only). If
        //    BOTH off, DROP.
        if (base == Lane.A && !pref.isPushEnabled()) base = Lane.B;
        if (base == Lane.B && !pref.isInboxEnabled()) base = Lane.C;
        if (base == Lane.C && !pref.isPushEnabled() && !pref.isInboxEnabled()) {
            // User has aggressively muted both — Lane C still fires the
            // in-app banner, which the user explicitly opted into by
            // having the app open. Keep C; only DROP when the app's
            // off-screen + push is off (not knowable here, FE handles).
        }

        // -- quiet hours apply only to Lane A (push). Lane B / C aren't
        //    interruptive so the quiet window doesn't gate them.
        if (base == Lane.A && pref.isQuietHoursEnabled() && isWithinQuietHours(pref)) {
            if (!isCriticalBypass(category, severity)) {
                base = Lane.B;
            }
        }

        // -- rate caps apply only to Lane A. Excess pushes demote to
        //    Lane B per spec ("Excess events drop to Lane B inbox").
        //    Critical-bypass categories are exempt — a hurricane
        //    warning shouldn't get rate-limited because the user
        //    already saw a quake notification 2min ago.
        if (base == Lane.A && !isCriticalBypass(category, severity)) {
            if (!rateLimiter.tryConsume(userEmail, category)) {
                base = Lane.B;
            }
        }

        return base;
    }

    /**
     * Get-or-create the preference record for this user. Default is
     * opt-in to everything per the policy doc; existing users land
     * in the right shape on first push send without a backfill. Users
     * who later customize their settings overwrite via the FE settings
     * page (which calls a PATCH endpoint that lands in a future session).
     */
    @Transactional
    public UserAlertPreference getOrCreate(String userEmail) {
        String key = userEmail.trim().toLowerCase();
        return repo.findByEmail(key).orElseGet(() -> {
            UserAlertPreference p = new UserAlertPreference();
            p.setUserEmail(key);
            // All other fields default to opt-in via the entity
            // initializers — mirrors the policy doc's "default record
            // auto-created on first push send if missing — keeps
            // existing users opt-in to everything by default".
            try {
                return repo.save(p);
            } catch (Exception e) {
                // Concurrent get-or-create may race; fall back to a
                // re-read. If that also fails we synthesize a transient
                // default so evaluate() doesn't crash the send path.
                log.debug("PushPolicy: race on default record for {}; rereading", key);
                return repo.findByEmail(key).orElse(p);
            }
        });
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private static Lane defaultLaneFor(Category c) {
        return switch (c) {
            // Lane A — interruptive, eight bounded categories per policy doc
            case NWS_SEVERE_EXTREME, USGS_QUAKE_MAJOR, WILDFIRE_NEAR,
                 GROUP_ALERT_HOUSEHOLD, GROUP_ALERT_ORG,
                 PLAN_ACTIVATION_RECEIVED, ACTIVATION_ACK,
                 TASK_ASSIGNED, PENDING_MEMBER_REQUEST -> Lane.A;
            // Lane B — silent inbox
            case NWS_MINOR, USGS_QUAKE_MINOR, FEMA_DECLARATION,
                 MENTION, COMMENT_REPLY, REACTION_ROLLUP,
                 NEW_MEMBER, TASK_STATUS_CHANGE, AUTO_POST_LOCAL -> Lane.B;
            // Lane C — ephemeral
            case SELF_STATUS_SYNC, CONNECTION_STATE,
                 OPTIMISTIC_ROLLBACK, TOAST_CONFIRMATION -> Lane.C;
        };
    }

    private static boolean isCategoryEnabled(UserAlertPreference p, Category c) {
        return switch (c) {
            case NWS_SEVERE_EXTREME, NWS_MINOR -> p.isNwsAlerts();
            case USGS_QUAKE_MAJOR, USGS_QUAKE_MINOR -> p.isEarthquakes();
            case WILDFIRE_NEAR -> p.isWildfires();
            case GROUP_ALERT_HOUSEHOLD, GROUP_ALERT_ORG -> p.isGroupAlerts();
            case PLAN_ACTIVATION_RECEIVED -> p.isPlanActivations();
            case ACTIVATION_ACK -> p.isActivationAcks();
            case TASK_ASSIGNED -> p.isTaskAssignments();
            case PENDING_MEMBER_REQUEST -> p.isPendingMembers();
            // Lane B + C categories aren't user-mutable in v1 — they're
            // either always-inbox or always-ephemeral. FE settings page
            // can grow knobs for these in v2 if users ask.
            default -> true;
        };
    }

    private static boolean isWithinQuietHours(UserAlertPreference p) {
        ZoneId zone;
        try {
            zone = ZoneId.of(p.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.of("America/New_York");
        }
        LocalTime now = ZonedDateTime.ofInstant(Instant.now(), zone).toLocalTime();
        LocalTime start = p.getQuietStart();
        LocalTime end = p.getQuietEnd();
        if (start == null || end == null) return false;
        if (start.equals(end)) return false; // zero-length window

        // Window spans midnight when start > end (e.g. 21:00–07:00).
        if (start.isAfter(end)) {
            return !now.isBefore(start) || now.isBefore(end);
        }
        return !now.isBefore(start) && now.isBefore(end);
    }

    private static boolean isCriticalBypass(Category category, String severity) {
        if (!CRITICAL_BYPASS.contains(category)) return false;
        return switch (category) {
            // NWS Severe + Extreme is the umbrella; only Extreme bypasses
            // quiet hours per the policy doc.
            case NWS_SEVERE_EXTREME -> "Extreme".equalsIgnoreCase(severity);
            // USGS major (M5.5+); M6.0+ bypasses.
            case USGS_QUAKE_MAJOR -> {
                if (severity == null) yield false;
                try { yield Double.parseDouble(severity) >= 6.0; }
                catch (NumberFormatException nfe) { yield false; }
            }
            // Plan activation + household group alert always bypass.
            case PLAN_ACTIVATION_RECEIVED, GROUP_ALERT_HOUSEHOLD -> true;
            default -> false;
        };
    }

    // -------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------

    public enum Lane {
        /** Interruptive push (FCM + APNs). */
        A,
        /** Silent inbox row. */
        B,
        /** Ephemeral in-app banner, no inbox row, no log. */
        C,
        /** Suppressed entirely — no inbox, no banner, no push. */
        DROP
    }

    /**
     * Structured event categories. Mirrors the 17-entry vocabulary in
     * {@code docs/NOTIFICATIONS_INBOX.md} so the inbox surface and
     * the policy chokepoint share the same shape.
     */
    public enum Category {
        // Lane A — interruptive (eight categories per policy doc)
        NWS_SEVERE_EXTREME,
        USGS_QUAKE_MAJOR,
        WILDFIRE_NEAR,
        GROUP_ALERT_HOUSEHOLD,
        GROUP_ALERT_ORG,
        PLAN_ACTIVATION_RECEIVED,
        ACTIVATION_ACK,
        TASK_ASSIGNED,
        PENDING_MEMBER_REQUEST,

        // Lane B — silent inbox
        NWS_MINOR,
        USGS_QUAKE_MINOR,
        FEMA_DECLARATION,
        MENTION,
        COMMENT_REPLY,
        REACTION_ROLLUP,
        NEW_MEMBER,
        TASK_STATUS_CHANGE,
        AUTO_POST_LOCAL,

        // Lane C — ephemeral (in-app banner only)
        SELF_STATUS_SYNC,
        CONNECTION_STATE,
        OPTIMISTIC_ROLLBACK,
        TOAST_CONFIRMATION;
    }
}
