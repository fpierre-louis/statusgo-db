package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CheckInRequest;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.CheckInRequestRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records — and answers — whether a person was actually asked to check in.
 *
 * <p>One dimension, kept deliberately narrow. This service does not know or care
 * whether a notification went out; that is {@code NotificationLog}'s question and
 * conflating the two is what RC-2 exists to stop. "Asked" and "reached" are
 * different facts and a household needs both.</p>
 */
@Service
public class CheckInRequestService {

    private static final Logger log = LoggerFactory.getLogger(CheckInRequestService.class);

    private final CheckInRequestRepo repo;

    public CheckInRequestService(CheckInRequestRepo repo) {
        this.repo = repo;
    }

    /**
     * The window a check-in belongs to.
     *
     * <p>{@code alertActivatedAt} when the group has an open alert — the same
     * anchor the roster's NO RESPONSE derivation uses, so the two cannot
     * disagree about which situation a status belongs to. A nudge sent with no
     * alert open carries its own request time, which makes it its own window:
     * that is honest, and it keeps "asked" meaningful outside a crisis without
     * inventing a situation that does not exist.</p>
     */
    public static Instant windowStartFor(Group group, Instant now) {
        Instant activated = group == null ? null : group.getAlertActivatedAt();
        // "Active" is the ONE open value. This tested "anything but
        // 'inactive'", and the live closed value is "Not Active" — it held
        // only because alertActivatedAt is cleared on all-clear.
        boolean alertOpen = group != null && "Active".equalsIgnoreCase(group.getAlert());
        return (alertOpen && activated != null) ? activated : now;
    }

    /**
     * How long an ask sent with no check-in running keeps saying "asked" — and,
     * through the member view's check-in state, how long a calm answer keeps
     * showing. The one source of "24 hours" for the check-in surfaces.
     */
    public static final java.time.Duration OUTSIDE_CHECK_IN_WINDOW = java.time.Duration.ofHours(24);

    /**
     * Where the READ window starts: the open check-in's start, else the last
     * 24 hours.
     *
     * <p>Not {@link #windowStartFor}: with no check-in open that returns
     * {@code now}, so reading "asks since now" found none — an ask sent
     * outside a check-in was recorded and never shown (open-items plan 1.4,
     * 2026-10-02).</p>
     */
    public static Instant readWindowStartFor(Group group, Instant now) {
        Instant start = windowStartFor(group, now);
        return start.equals(now) ? now.minus(OUTSIDE_CHECK_IN_WINDOW) : start;
    }

    /**
     * Record that each of {@code subjectEmails} was asked, for this group's
     * current window.
     *
     * <p>Idempotent inside a window: re-asking the same person updates the
     * request time rather than adding a row, so "ask again" does not inflate
     * anything a household reads. Never throws into the caller — an ask that
     * was sent but not recorded is a lesser failure than an ask that did not
     * happen because bookkeeping failed.</p>
     */
    @Transactional
    public void recordAsked(Group group, Collection<String> subjectEmails, String requestedByEmail) {
        if (group == null || subjectEmails == null || subjectEmails.isEmpty()) return;
        Instant now = Instant.now();
        Instant windowStart = windowStartFor(group, now);
        String actor = normalize(requestedByEmail);

        try {
            Map<String, CheckInRequest> existing = new HashMap<>();
            for (CheckInRequest r : repo.findByGroupIdAndWindowStartedAtGreaterThanEqual(
                    group.getGroupId(), windowStart)) {
                if (windowStart.equals(r.getWindowStartedAt())) {
                    existing.put(normalize(r.getSubjectEmail()), r);
                }
            }

            List<CheckInRequest> toSave = new java.util.ArrayList<>();
            for (String raw : subjectEmails) {
                String email = normalize(raw);
                if (email == null) continue;
                CheckInRequest row = existing.get(email);
                if (row == null) {
                    toSave.add(new CheckInRequest(group.getGroupId(), email, windowStart, now, actor));
                } else {
                    row.setRequestedAt(now);
                    row.setRequestedByEmail(actor);
                    toSave.add(row);
                }
            }
            if (!toSave.isEmpty()) repo.saveAll(toSave);
        } catch (Exception e) {
            // Bookkeeping must never take down the ask itself.
            log.warn("CheckInRequest: failed to record asks for group {}: {}",
                    group.getGroupId(), e.getMessage());
        }
    }

    /**
     * When each person was asked, for this group's current window.
     *
     * @return lower-cased email → requestedAt. An email ABSENT from the map was
     *         not asked in this window, which is a different fact from having
     *         been asked and not answered.
     */
    @Transactional(readOnly = true)
    public Map<String, Instant> askedAtByEmail(Group group) {
        Map<String, Instant> out = new HashMap<>();
        if (group == null || group.getGroupId() == null) return out;
        Instant windowStart = readWindowStartFor(group, Instant.now());
        try {
            for (CheckInRequest r : repo.findByGroupIdAndWindowStartedAtGreaterThanEqual(
                    group.getGroupId(), windowStart)) {
                String email = normalize(r.getSubjectEmail());
                if (email == null) continue;
                Instant prior = out.get(email);
                if (prior == null || r.getRequestedAt().isAfter(prior)) {
                    out.put(email, r.getRequestedAt());
                }
            }
        } catch (Exception e) {
            log.warn("CheckInRequest: failed to read asks for group {}: {}",
                    group.getGroupId(), e.getMessage());
        }
        return out;
    }

    /**
     * When ONE person was last asked, for this group's current read window —
     * null when they were not. The single-subject form of
     * {@link #askedAtByEmail}, for a status write deciding whether it answers
     * an ask.
     */
    @Transactional(readOnly = true)
    public Instant askedAt(Group group, String subjectEmail) {
        String email = normalize(subjectEmail);
        if (group == null || group.getGroupId() == null || email == null) return null;
        Instant windowStart = readWindowStartFor(group, Instant.now());
        Instant latest = null;
        try {
            for (CheckInRequest r : repo.findByGroupIdAndSubjectEmailIgnoreCase(group.getGroupId(), email)) {
                if (r.getWindowStartedAt() == null || r.getWindowStartedAt().isBefore(windowStart)) continue;
                if (latest == null || r.getRequestedAt().isAfter(latest)) latest = r.getRequestedAt();
            }
        } catch (Exception e) {
            log.warn("CheckInRequest: failed to read ask for {} in group {}: {}",
                    email, group.getGroupId(), e.getMessage());
        }
        return latest;
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}
