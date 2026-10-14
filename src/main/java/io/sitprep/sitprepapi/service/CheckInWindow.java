package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;

import java.time.Duration;
import java.time.Instant;

/**
 * How long a check-in runs and when a page offers "Continue" — decided here,
 * once, so no client restates it (ST-04, B4 2026-10-14).
 *
 * <p><b>Before this</b> the frontend held both numbers itself
 * ({@code checkInWindow.js}: {@code CHECK_IN_HOURS = 48},
 * {@code CONTINUE_WINDOW_MS = 12h}), copied from the server's decay config and
 * from ruling K4. The member view now ships {@code checkInWindowHours} and
 * {@code continueAvailableFrom}, and the page renders those.</p>
 *
 * <p><b>The rule (K4, unchanged):</b> the page offers Continue in the running
 * check-in's last {@link #CONTINUE_OFFER} — from {@code end − 12h} — which is
 * also when the "Auto-ends in 12 hours" organizer push goes out
 * ({@code GroupCheckInReminderService.ENDING_SOON_SLOT}).</p>
 *
 * <p><b>Why {@code POST /alert/continue} does not refuse earlier.</b> Every
 * organizer reminder (30 min, 4 h, 12 h, day 1) is a CHECK_IN_REVIEW push, and
 * that push carries a "Keep going" action ({@code NotificationPresentationBuilder}).
 * Refusing a continue before {@code end − 12h} would turn those lock-screen
 * buttons into a 409. Whether the earlier pushes should offer it is the
 * notifications lane's call; until then this is the offer time, and a
 * continue from a push is still accepted while the check-in runs.</p>
 */
public final class CheckInWindow {

    private CheckInWindow() {}

    /** The page offers Continue in the running check-in's last 12 hours (K4). */
    public static final Duration CONTINUE_OFFER = Duration.ofHours(12);

    /**
     * When the page should start offering Continue: {@code endsAt − 12h}, or
     * null when no check-in is running ({@code endsAt} null).
     */
    public static Instant continueAvailableFrom(Instant endsAt) {
        return endsAt == null ? null : endsAt.minus(CONTINUE_OFFER);
    }

    /**
     * When the running check-in ends by itself: V90's {@code alertExpiresAt},
     * else its start plus the window; null when none is running.
     */
    public static Instant endsAt(Group g, int windowHours) {
        if (g == null || !"active".equalsIgnoreCase(g.getAlert()) || g.getAlertActivatedAt() == null) return null;
        return g.getAlertExpiresAt() != null
                ? g.getAlertExpiresAt()
                : g.getAlertActivatedAt().plus(Duration.ofHours(windowHours));
    }
}
