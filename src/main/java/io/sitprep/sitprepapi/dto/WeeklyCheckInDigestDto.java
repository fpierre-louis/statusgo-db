package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

/**
 * §8 R5 — admin-side rollup of household check-in activity.
 * Includes per-member streak counts so admins see who's most
 * consistent without inventing a leaderboard ("longest streak: 7
 * weeks (Sarah)" is honest signal — the user actually has 7 weeks
 * of events).
 *
 * <p>Built off the same calendar-week boundaries the summary uses
 * (Sunday 00:00 → Sunday 00:00 in the household's tz). Capped at
 * the {@code HouseholdEventService}'s 12-week lookback so the
 * counts are bounded regardless of history depth.</p>
 */
public record WeeklyCheckInDigestDto(
        Instant windowStart,
        Instant windowEnd,
        int totalMembers,
        int checkedInThisWeek,
        MemberStreak longestStreak,
        List<MemberStreak> memberStreaks
) {
    public record MemberStreak(
            String actorEmail,
            String actorName,
            String actorProfileImageUrl,
            int streakWeeks,
            boolean checkedInThisWeek
    ) {}
}
