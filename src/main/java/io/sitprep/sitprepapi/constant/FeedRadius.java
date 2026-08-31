package io.sitprep.sitprepapi.constant;

/**
 * The hard ceiling on a feed search radius, and the one place it is written.
 *
 * <h2>Why this exists</h2>
 *
 * An arbitrarily large radius turns the Haversine filter into a full-table
 * match and the response into a country dump, so both community feed paths
 * clamp it. Each of them wrote {@code if (radiusKm > 500.0) radiusKm = 500.0}
 * independently — {@code PostService.discoverCommunity} and
 * {@code CommunityDiscoverService.discover} — and the frontend then wrote a
 * THIRD copy, derived: {@code radiusOverride.js}'s {@code MAX_RADIUS_MI = 310},
 * with a comment doing the {@code 500 / 1.609344} conversion by hand.
 *
 * <p>CLAUDE.md lists that frontend copy as a <b>standing</b> example of a
 * server constant duplicated across a deploy boundary: change the clamp and the
 * two disagree until the client ships. What the note did not say is that the
 * backend had already duplicated it once itself, so the count was three.</p>
 *
 * <p>Both services now read {@link #MAX_KM}, and the value reaches the frontend
 * as {@code radiusMi.max} on {@code GET /api/config/defaults} — the same
 * endpoint that already owns the radius ladder — so no client re-derives it.
 * {@code AppConfigResource} makes the same argument for {@code alertsRadiusMi()}
 * after three layers of that one disagreed by 50x.</p>
 */
public final class FeedRadius {

    /** Kilometres. The clamp both community query paths apply. */
    public static final double MAX_KM = 500.0;

    private static final double KM_PER_MILE = 1.609344;

    private FeedRadius() {}

    /**
     * The clamp in miles, for display.
     *
     * <p>Deliberately FLOORED. 500 km is 310.7 miles, and this number is spoken
     * to the user as what the widest rung reaches ("about N miles"). Rounding up
     * would state a reach the server does not deliver; flooring understates by
     * less than a mile, which is the safe direction for a coverage claim.</p>
     */
    public static int maxMiles() {
        return (int) Math.floor(MAX_KM / KM_PER_MILE);
    }
}
