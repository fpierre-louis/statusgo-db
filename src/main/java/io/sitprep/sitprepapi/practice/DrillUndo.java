package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.DrillCompletion;

import java.time.Duration;
import java.time.Instant;

/**
 * Who may undo a drill mark from the drill page, and until when (Practice
 * redesign FR-4; handoff DRILL_FLOW "undo only exists to fix a mistaken tap
 * made within the last 10 minutes"; owner ruling 2026-10-22 on who may change
 * shared history).
 *
 * <p>The server answers per viewer so the client shows Undo only when the
 * server would honour it: the member who made the mark, or a household
 * owner/admin, within ten minutes of it. Past that the mark simply stands; an
 * owner/admin who wants it gone uses Clear practice history.</p>
 */
public final class DrillUndo {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    private DrillUndo() {}

    /** The instant this viewer's Undo stops being offered, or null when they have none. */
    public static Instant undoUntil(DrillCompletion mark, String viewerUserId, boolean viewerIsAdmin, Instant now) {
        if (mark == null || mark.getCompletedAt() == null || now == null) return null;
        Instant until = mark.getCompletedAt().plus(WINDOW);
        if (!until.isAfter(now)) return null;
        boolean own = viewerUserId != null && viewerUserId.equals(mark.getCompletedByUserId());
        return own || viewerIsAdmin ? until : null;
    }

    /** A member's own mark inside the window (the resource's non-admin undo rule). */
    public static boolean isOwnRecentMark(DrillCompletion mark, String viewerUserId, Instant now) {
        return undoUntil(mark, viewerUserId, false, now) != null;
    }
}
