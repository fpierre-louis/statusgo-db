package io.sitprep.sitprepapi.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Until when a person's stored status still counts as their CURRENT status
 * (status freshness, 2026-10-13).
 *
 * <p>{@code UserInfo.userStatus} is the last-known answer and is never cleared:
 * nothing resets it after a time or when a check-in starts or ends. So every
 * surface that showed it raw showed a SAFE from three weeks ago as SAFE. The
 * household roster was right only because it reads {@link CheckInState}; this
 * puts the same lapse on every status the server ships, so a client compares a
 * clock and holds no duration of its own.</p>
 *
 * <ul>
 *   <li><b>SAFE</b> is current until {@code updatedAt + 24h}, the same
 *       {@link CheckInRequestService#OUTSIDE_CHECK_IN_WINDOW} CheckInState uses.</li>
 *   <li><b>HELP / INJURED</b> never lapse: {@code null}. Bad news stays until
 *       it is changed.</li>
 *   <li><b>Anything else</b> (no status, "NO RESPONSE", a SAFE with no time):
 *       {@link Instant#EPOCH}, already past. Never "current" by accident.</li>
 * </ul>
 *
 * <p>During a check-in the stricter rule still applies on top: an answer from
 * before the check-in started is not an answer to it ({@link CheckInState},
 * {@link StatusRollups}).</p>
 */
public final class StatusFreshness {

    private static final Set<String> BAD_NEWS = Set.of("HELP", "INJURED");

    private StatusFreshness() {}

    public static Instant freshUntil(String value, Instant updatedAt) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (BAD_NEWS.contains(v)) return null;
        if ("SAFE".equals(v) && updatedAt != null) {
            return updatedAt.plus(CheckInRequestService.OUTSIDE_CHECK_IN_WINDOW);
        }
        return Instant.EPOCH;
    }
}
