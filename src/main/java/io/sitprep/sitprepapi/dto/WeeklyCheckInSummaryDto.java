package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * Roster of weekly check-in completions in the rolling 7-day window —
 * feeds §8 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md}'s variable
 * reward scene. The reward composes its copy from these honest signals
 * (position in the roster, total household size) so we never invent
 * fake social proof.
 *
 * <p>{@code actorPosition} is 1-based: 1 = "first this week", 2 = "2nd to
 * check in", etc. 0 when the caller hasn't checked in yet. Computed from
 * the chronological order of latest-per-member completions in the window.</p>
 *
 * <p>"This week" is currently a rolling 7-day window from {@code now}.
 * v2 will switch to calendar-week boundaries in the household's timezone
 * once the ritual scheduler ships and a "week" has a fixed reset moment.</p>
 *
 * <p>{@code viewerStreakWeeks} is the count of consecutive 7-day
 * windows back from {@code now} where the viewer has ≥1 check-in
 * event. 0 if they haven't checked in this week (no in-flight streak
 * to display). Capped at 12 weeks of lookback to bound the BE
 * query; surfaces a meaningful "X weeks in a row" line on the
 * reward scene without ever inventing the number.</p>
 */
public record WeeklyCheckInSummaryDto(
        Instant windowStart,
        Instant windowEnd,
        int totalMembers,
        int actorPosition,
        int viewerStreakWeeks,
        List<Completion> completions
) {
    public record Completion(
            String actorEmail,
            String actorName,
            String actorProfileImageUrl,
            Instant at,
            String mood
    ) {}
}
