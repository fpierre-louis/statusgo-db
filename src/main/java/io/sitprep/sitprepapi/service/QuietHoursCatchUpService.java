package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * "While your notifications were quiet" — ONE push when a user's quiet window
 * ends, if that window held anything they have not read (EXEC-N, 2026-10-07).
 *
 * <p><b>Why.</b> Quiet hours turn a non-critical push into an inbox row
 * (PushPolicyService → Lane B). Nothing re-surfaced those rows: a fire warning,
 * a check-in request or a direct message that arrived at 2am waited silently
 * until the user happened to open the inbox. The entity javadoc promised a 7am
 * re-push that never existed.</p>
 *
 * <p><b>What it sends.</b> Never the held notifications themselves (re-pushing
 * five items at 7am is the burst quiet hours exist to prevent) — one summary,
 * "{n} updates while your notifications were quiet", opening
 * {@code /notifications}. Calm copy; no hazard wording, so no alert-template
 * safety copy is involved.</p>
 *
 * <p><b>Who.</b> Every 15 minutes, the recipients with an unread, unarchived
 * row marked {@code deferred_reason = 'QUIET_HOURS'} (V101) in the last
 * {@link #LOOKBACK} — keyset-paged by email. For each:</p>
 * <ul>
 *   <li>skip unless push is on and quiet hours are still on (a user who turned
 *       quiet hours off overnight has no window to end);</li>
 *   <li>their most recently ENDED window, in their own timezone, must have
 *       ended within {@link #GRACE} — so a summary is never sent at 4pm for a
 *       window that closed at 7am, and a 15-minute tick or a short outage
 *       still catches it;</li>
 *   <li>at least one unread deferred row timestamped inside THAT window;</li>
 *   <li>no summary already sent since the window ended (the summary's own
 *       audit row is the once-per-night stamp);</li>
 *   <li>a push token on file.</li>
 * </ul>
 * <p>Only rows marked by the user's own quiet hours count — not rate-capped
 * rows, not rows held by a per-group quiet window or a muted group.</p>
 *
 * <p>Off with every other job when {@code app.scheduling.enabled=false}
 * ({@code SchedulingConfig} owns {@code @EnableScheduling}). Single-dyno
 * assumption: two instances ticking the same minute could each send before
 * either stamp row is visible; the stamp check makes that a same-tick race,
 * not a repeat.</p>
 */
@Service
public class QuietHoursCatchUpService {

    private static final Logger log = LoggerFactory.getLogger(QuietHoursCatchUpService.class);

    static final String TITLE = "Your inbox";
    static final int BATCH_SIZE = 200;
    /** How long after a window ends the summary may still go out. */
    static final Duration GRACE = Duration.ofHours(3);
    /** Longest possible window (24h) plus the grace period. */
    static final Duration LOOKBACK = Duration.ofHours(27);
    static final String QUIET = PushPolicyService.DeferReason.QUIET_HOURS.name();

    private final NotificationLogRepo logRepo;
    private final UserAlertPreferenceRepo prefRepo;
    private final UserInfoRepo userInfoRepo;
    private final NotificationService notifications;
    private final Clock clock;

    @Autowired
    public QuietHoursCatchUpService(NotificationLogRepo logRepo,
                                    UserAlertPreferenceRepo prefRepo,
                                    UserInfoRepo userInfoRepo,
                                    NotificationService notifications) {
        this(logRepo, prefRepo, userInfoRepo, notifications, Clock.systemUTC());
    }

    QuietHoursCatchUpService(NotificationLogRepo logRepo,
                             UserAlertPreferenceRepo prefRepo,
                             UserInfoRepo userInfoRepo,
                             NotificationService notifications,
                             Clock clock) {
        this.logRepo = logRepo;
        this.prefRepo = prefRepo;
        this.userInfoRepo = userInfoRepo;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Every 15 minutes; 14-minute initial delay keeps it off the other sweeps' minute. */
    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT14M")
    public void scheduledSweep() {
        try {
            int sent = sweepOnce();
            if (sent > 0) log.info("QuietHoursCatchUp: sent {} summaries", sent);
        } catch (Exception e) {
            log.warn("QuietHoursCatchUp: sweep failed: {}", e.getMessage(), e);
            try { Sentry.captureException(e); } catch (Throwable ignored) {}
        }
    }

    /** One full pass. Returns the number of summaries sent. */
    public int sweepOnce() {
        Instant now = clock.instant();
        String after = "";
        int sent = 0;
        while (true) {
            List<String> page = logRepo.findDeferredUnreadRecipients(
                    QUIET, now.minus(LOOKBACK), after, PageRequest.of(0, BATCH_SIZE));
            if (page == null || page.isEmpty()) break;
            for (String email : page) {
                if (email != null && email.compareTo(after) > 0) after = email;
                try {
                    if (handle(email, now)) sent++;
                } catch (Exception e) {
                    log.warn("QuietHoursCatchUp: {} failed: {}", email, e.getMessage());
                }
            }
            if (page.size() < BATCH_SIZE) break;
        }
        return sent;
    }

    /** @return true when a summary was sent to {@code email}. */
    boolean handle(String email, Instant now) {
        if (email == null || email.isBlank()) return false;
        Optional<UserAlertPreference> prefOpt = prefRepo.findByEmail(email);
        if (prefOpt.isEmpty()) return false;
        UserAlertPreference pref = prefOpt.get();
        if (!pref.isPushEnabled() || !pref.isQuietHoursEnabled()) return false;

        Window window = lastEndedWindow(pref, now);
        if (window == null) return false;
        if (now.isAfter(window.end().plus(GRACE))) return false;

        long count = logRepo.countDeferredUnread(email, QUIET, window.start(), window.end());
        if (count <= 0) return false;
        if (logRepo.existsTypeSince(email, NotificationService.TYPE_QUIET_HOURS_CATCH_UP, window.end())) {
            return false;
        }

        String token = userInfoRepo.findByUserEmailIgnoreCase(email)
                .map(UserInfo::getFcmtoken)
                .orElse(null);
        if (token == null || token.isBlank()) return false;

        int n = (int) Math.min(count, Integer.MAX_VALUE);
        return notifications.sendQuietHoursCatchUp(email, token, TITLE, body(n), n);
    }

    /** "1 update while…" / "3 updates while…" — calm, no hazard wording. */
    static String body(int n) {
        return n + (n == 1 ? " update" : " updates") + " while your notifications were quiet.";
    }

    /** One quiet window, as instants. */
    record Window(Instant start, Instant end) {}

    /**
     * The user's most recent quiet window that has ENDED at or before
     * {@code now}, in their own timezone (invalid zone → America/New_York, the
     * same fallback PushPolicyService uses). Null when the window is unset or
     * zero-length. A window whose start is after its end wraps midnight
     * (21:00–07:00): it started the evening before the morning it ends.
     */
    static Window lastEndedWindow(UserAlertPreference pref, Instant now) {
        LocalTime start = pref.getQuietStart();
        LocalTime end = pref.getQuietEnd();
        if (start == null || end == null || start.equals(end)) return null;
        ZoneId zone;
        try {
            zone = ZoneId.of(pref.getTimezone());
        } catch (Exception e) {
            zone = ZoneId.of("America/New_York");
        }
        LocalDate today = now.atZone(zone).toLocalDate();
        ZonedDateTime endAt = ZonedDateTime.of(today, end, zone);
        if (endAt.toInstant().isAfter(now)) endAt = ZonedDateTime.of(today.minusDays(1), end, zone);
        LocalDate startDate = start.isAfter(end) ? endAt.toLocalDate().minusDays(1) : endAt.toLocalDate();
        ZonedDateTime startAt = ZonedDateTime.of(startDate, start, zone);
        return new Window(startAt.toInstant(), endAt.toInstant());
    }
}
