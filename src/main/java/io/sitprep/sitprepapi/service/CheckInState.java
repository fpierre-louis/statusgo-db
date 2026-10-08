package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.GroupMemberViewDto.CheckIn;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * The household roster's per-row answer to "were they asked, and what did they
 * say" — one pure function, so the drawer, the map and any later surface read
 * the same rule instead of re-deriving 24 hours from two timestamps (household
 * drawer gameplan §3.2 / §5.3, owner rulings Q3 + Q3b, 2026-10-08).
 *
 * <h2>Precedence</h2>
 * <ol>
 *   <li><b>Bad news</b> — INJURED or HELP. Always {@code ANSWERED} with that
 *       value and {@code showUntil = null}: it never lapses, and a new ask
 *       never turns it into {@code AWAITING}. A household must not lose
 *       "Maya is injured" because somebody tapped Ask.</li>
 *   <li><b>AWAITING</b> — asked, and no status written since the ask.</li>
 *   <li><b>ANSWERED</b> (SAFE) — a status written after the ask, or a
 *       self-report with no ask at all (Q3: it still answers the question).</li>
 *   <li><b>NONE</b> — nothing asked and nothing fresh to show.</li>
 * </ol>
 *
 * <h2>Windows</h2>
 * <ul>
 *   <li><b>Calm.</b> AWAITING lasts until {@code askedAt + 24h}. A SAFE shows
 *       until {@code max(askedAt, answeredAt) + 24h}, so a reply at hour 23
 *       still shows a full day. A NEW ask after a SAFE moves the row to
 *       AWAITING (Q3b (i)); the last-known value stays on {@code selfStatus}.</li>
 *   <li><b>Check-in running</b> (group alert "Active"). {@code showUntil} is
 *       the check-in's end, and the window rule the rest of the roster uses
 *       decides: a SAFE written at or after the check-in started is ANSWERED,
 *       whatever was asked since.</li>
 * </ul>
 *
 * <p>The 24 hours is {@link CheckInRequestService#OUTSIDE_CHECK_IN_WINDOW} —
 * the same constant that decides how long an ask keeps saying "asked".</p>
 *
 * <p>In {@code AWAITING} and {@code NONE}, {@code answeredAt}, {@code value}
 * and {@code setByName} are null: there is no answer to this ask. The person's
 * last-known status is {@code selfStatus}, alongside.</p>
 */
public final class CheckInState {

    public static final String NONE = "NONE";
    public static final String AWAITING = "AWAITING";
    public static final String ANSWERED = "ANSWERED";

    private static final Set<String> BAD_NEWS = Set.of("INJURED", "HELP");

    private CheckInState() {}

    /**
     * @param askedAt          the latest ask in the current read window, or null
     * @param value            the stored status (anything but SAFE/HELP/INJURED
     *                         reads as no status — e.g. "NO RESPONSE")
     * @param updatedAt        when that status was written
     * @param setByName        who wrote it when it was not the person; null for self
     * @param alertActive      the group's check-in is running
     * @param alertActivatedAt when it started (ignored when not running) —
     *                         callers pass {@link StatusRollups#anchorFor},
     *                         the line the counts use, so row and count agree
     * @param alertExpiresAt   when it ends by itself (ignored when not running)
     */
    public static CheckIn of(Instant askedAt, String value, Instant updatedAt, String setByName,
                             boolean alertActive, Instant alertActivatedAt, Instant alertExpiresAt,
                             Instant now) {
        String v = normalize(value);
        if (now == null) now = Instant.now();

        if (v != null && BAD_NEWS.contains(v)) {
            return new CheckIn(ANSWERED, askedAt, updatedAt, v, setByName, null);
        }

        if (alertActive) {
            boolean respondedInWindow = v != null && updatedAt != null
                    && (alertActivatedAt == null || !updatedAt.isBefore(alertActivatedAt));
            if (respondedInWindow) {
                return new CheckIn(ANSWERED, askedAt, updatedAt, v, setByName, alertExpiresAt);
            }
            if (askedAt != null) {
                return new CheckIn(AWAITING, askedAt, null, null, null, alertExpiresAt);
            }
            return none();
        }

        boolean answeredSinceAsk = v != null && updatedAt != null
                && (askedAt == null || !updatedAt.isBefore(askedAt));
        if (askedAt != null && !answeredSinceAsk) {
            Instant until = askedAt.plus(CheckInRequestService.OUTSIDE_CHECK_IN_WINDOW);
            return now.isBefore(until)
                    ? new CheckIn(AWAITING, askedAt, null, null, null, until)
                    : none();
        }
        if (answeredSinceAsk) {
            // max(askedAt, answeredAt) + 24h — and answered-since-ask means
            // answeredAt is the later of the two.
            Instant until = updatedAt.plus(CheckInRequestService.OUTSIDE_CHECK_IN_WINDOW);
            if (now.isBefore(until)) {
                return new CheckIn(ANSWERED, askedAt, updatedAt, v, setByName, until);
            }
        }
        return none();
    }

    /** True when this state still shows a status (bad news, or a SAFE inside its window). */
    public static boolean showsStatus(CheckIn c) {
        return c != null && ANSWERED.equals(c.state());
    }

    private static CheckIn none() {
        return new CheckIn(NONE, null, null, null, null, null);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        return "SAFE".equals(v) || BAD_NEWS.contains(v) ? v : null;
    }
}
