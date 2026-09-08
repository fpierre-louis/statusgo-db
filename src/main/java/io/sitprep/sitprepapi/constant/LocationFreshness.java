package io.sitprep.sitprepapi.constant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * How old a location fix may be and still be trusted — and the one place it is
 * written.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The <b>push</b> path has always bounded this. {@code UserInfoRepo
 * .findPushablesWithLocation} refuses to target a user whose
 * {@code lastKnownLocationAt} is older than the window, and says why:</p>
 *
 * <blockquote>"a life-safety push could be targeted on a coordinate captured at
 * an airport months ago … An unknown-age location is not a location."</blockquote>
 *
 * <p>The <b>pull</b> path had no equivalent, and — the part that matters — could
 * not have had one. {@code GET /api/alerts/feed} took bare {@code lat}/{@code lng}
 * query parameters, so the frontend sent a coordinate with no age attached and
 * the server had nothing to apply a rule to. That was not a forgotten threshold;
 * the wire shape made the check impossible (audit finding F-5,
 * {@code docs/epics/hazards-page/AUDIT-location-freshness-2026-09-07.md}).</p>
 *
 * <p>The two failed in opposite directions and the union was silence. Inside the
 * window both aimed at the stale coordinate. Past it, push correctly went quiet
 * while pull kept rendering the old position as current — so the user got an
 * authoritative "0 active near you" and no notification, and <b>the silence read
 * as safety</b>. Measured 2026-09-07: a user cached in Lehi UT and standing in
 * Beaumont TX saw zero while a Severe / Immediate Flood Warning was in force
 * over their head.</p>
 *
 * <h2>Why it is one class and not one number copied twice</h2>
 *
 * <p>{@code AlertDispatchService} used to hold the {@code @Value} itself. Adding
 * a second on the feed side would have made this the fourth constant in this
 * project to exist in more than one place — after the three radius clamps
 * ({@link FeedRadius}), the alerts radius that disagreed by 50x
 * ({@code AppConfigResource#alertsRadiusMi()}), and the four status palettes.
 * Every one of those was found the same way: not by the two copies diverging
 * loudly, but by someone measuring and discovering they already had.</p>
 *
 * <p>The static accessor mirrors {@code AppConfigResource#alertsRadiusMi()} — the
 * codebase's existing answer to "a tunable value that non-injected code needs" —
 * so no caller takes a constructor parameter for it and no test has to wire a
 * bean to get the default.</p>
 */
@Component
public class LocationFreshness {

    /** Days. The default when nothing overrides it, and the value tests see. */
    public static final int DEFAULT_MAX_AGE_DAYS = 14;

    private static volatile int maxAgeDays = DEFAULT_MAX_AGE_DAYS;

    /**
     * Bound from {@code alerts.push.locationMaxAgeDays} at startup. The property
     * keeps its {@code push} name because that is where the rule was first
     * written and what the Heroku config var is already called; renaming it
     * would trade a shared constant for a broken deploy knob.
     */
    @Value("${alerts.push.locationMaxAgeDays:14}")
    void bindMaxAgeDays(int days) {
        maxAgeDays = days > 0 ? days : DEFAULT_MAX_AGE_DAYS;
    }

    /** The window, in days. Shipped to clients so they word it without owning it. */
    public static int maxAgeDays() {
        return maxAgeDays;
    }

    /** The oldest fix still considered current. */
    public static Instant cutoff(Instant now) {
        return now.minus(Duration.ofDays(maxAgeDays));
    }

    /**
     * Is this fix too old to aim an alert with?
     *
     * <p>Callers must handle "we were never told" separately: a null
     * {@code fixedAt} is <b>unknown</b>, not stale, and the two must not render
     * the same way. A client that omits the timestamp has not asserted
     * freshness, and treating that as a pass would let the exact gap this class
     * closes reopen through the front door.</p>
     */
    public static boolean isStale(Instant fixedAt, Instant now) {
        if (fixedAt == null) return false;
        return fixedAt.isBefore(cutoff(now));
    }

    /** Test seam: pretend config set a different window. */
    static void bindForTest(int days) {
        maxAgeDays = days > 0 ? days : DEFAULT_MAX_AGE_DAYS;
    }

    /** Test seam. Restores the default; production binds from config at boot. */
    static void resetForTest() {
        maxAgeDays = DEFAULT_MAX_AGE_DAYS;
    }
}
