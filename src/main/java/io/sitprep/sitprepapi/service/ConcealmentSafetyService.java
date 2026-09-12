package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Is somebody near this point likely to be hiding right now?
 *
 * <p>── WHY THIS EXISTS (P0-B) ──────────────────────────────────────────────
 *
 * <p>Every push SitPrep sends is built with {@code sound("default")} at Android
 * {@code HIGH} / APNs priority {@code 10}. There is one volume, and it is
 * audible. That is right for almost everything the product does and wrong for
 * one situation: a household member concealed during a lockdown or a violent
 * threat, whose phone making a noise is the specific danger.
 *
 * <p>Stress-test Scenario 28: a 12-year-old in a locked, silent classroom while
 * her family — frightened, and doing the natural thing — taps Nudge.
 *
 * <p><b>Quiet hours do not cover this.</b> Quiet hours are a schedule the user
 * sets for sleeping, and that lockdown is at 1:05 PM on a Wednesday. A
 * concealment event is not scheduled, so a scheduled preference cannot answer
 * it. (It is also worth being accurate about the mechanism: {@code
 * CHECK_IN_REQUEST} is NOT on {@code PushPolicyService.CRITICAL_BYPASS}, so a
 * nudge inside a user's quiet window does get demoted to the inbox. The gap is
 * not a bypass — it is that there is no situational silence at all.)
 *
 * <p>── HOW THE JUDGMENT IS MADE ───────────────────────────────────────────
 *
 * <p>From the template contract, not from the event name. A template declares
 * {@code sitprep.concealmentSensitive}, the same place its movement directive is
 * declared, and the same safety review decides both. Matching {@code "shooting"}
 * against a headline would be the wrong architecture and would also be wrong in
 * fact — the CAP events that carry these orders are {@code Civil Danger Warning}
 * and {@code Law Enforcement Warning}, which say nothing of the sort.
 *
 * <p>The check is cheap: {@link AlertFeedService#feedFor} reads an in-memory
 * snapshot, so this costs no I/O on a send path.
 */
@Service
public class ConcealmentSafetyService {

    private static final Set<String> LIVE_LIFECYCLE = Set.of("active", "updated");

    private final AlertFeedService alertFeedService;
    private final AlertDispatchService alertDispatchService;

    public ConcealmentSafetyService(AlertFeedService alertFeedService,
                                    AlertDispatchService alertDispatchService) {
        this.alertFeedService = alertFeedService;
        this.alertDispatchService = alertDispatchService;
    }

    /**
     * True when a live alert covering this point is classified
     * concealment-sensitive.
     *
     * <p>Fails CLOSED toward noise, deliberately: if we cannot tell, we do not
     * silence. Silencing a reminder that somebody needed is its own harm, and
     * the honest default for an unknown situation is the ordinary one. The
     * caller pairs this with sender-side copy so nobody is told a push was
     * silent when it was not.
     */
    public boolean isConcealmentSensitiveAt(Double lat, Double lng) {
        if (lat == null || lng == null) return false;
        try {
            AlertFeedResponse feed = alertFeedService.feedFor(lat, lng);
            if (feed == null || feed.alerts() == null) return false;
            Instant now = Instant.now();
            return feed.alerts().stream().anyMatch(c -> isLive(c, now) && isConcealmentSensitive(c));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Does this card's reviewed template declare the hazard concealment-sensitive? */
    private boolean isConcealmentSensitive(AlertCardDto card) {
        if (card == null) return false;
        String event = card.eventType() != null ? card.eventType() : card.eventLabel();
        if (event == null || event.isBlank()) return false;
        return alertDispatchService.templateForEvent(event)
                .map(t -> t.sitprep != null && t.sitprep.concealmentSensitive)
                .orElse(false);
    }

    private boolean isLive(AlertCardDto c, Instant now) {
        if (c == null) return false;
        String lifecycle = c.lifecycleState() == null ? "" : c.lifecycleState().toLowerCase(Locale.ROOT);
        if (!LIVE_LIFECYCLE.contains(lifecycle)) return false;
        if (c.expiresAt() == null || c.expiresAt().isBlank()) return true;
        try {
            return Instant.parse(c.expiresAt()).isAfter(now);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    /** Convenience for callers that already hold a list of live events. */
    public boolean anyConcealmentSensitive(List<String> eventNames) {
        if (eventNames == null) return false;
        return eventNames.stream().anyMatch(e -> e != null && !e.isBlank()
                && alertDispatchService.templateForEvent(e)
                        .map(t -> t.sitprep != null && t.sitprep.concealmentSensitive)
                        .orElse(false));
    }
}
