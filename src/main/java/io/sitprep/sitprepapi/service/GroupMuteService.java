package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.GroupMutePref;
import io.sitprep.sitprepapi.repo.GroupMutePrefRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Business logic for per-(user, group) notification mutes.
 *
 * <p>Three callers:
 * <ul>
 *   <li>{@code GroupMuteResource} — the FE long-press Mute sheet
 *       PUTs through here.</li>
 *   <li>{@code MeService} — batch-loads a user's mutes when building
 *       the {@code /api/me} payload so the circle cards can render
 *       the muted-bell affordance without an extra round trip.</li>
 *   <li>{@code NotificationService} — calls {@link #isMuted} at
 *       group-scoped dispatch time to skip FCM (with an inbox log
 *       row still written so the missed message is recoverable).</li>
 * </ul></p>
 */
@Service
public class GroupMuteService {

    /**
     * Sentinel for "muted until I turn it back on". Year 9999 keeps
     * the comparison logic uniform (always a deadline check) while
     * being far enough out that nobody will see it as a literal date.
     * The FE detects this sentinel and renders "Muted" without a
     * deadline rather than "Muted until 9999-12-31".
     */
    public static final Instant INDEFINITE = Instant.parse("9999-12-31T23:59:59Z");

    private final GroupMutePrefRepo repo;

    public GroupMuteService(GroupMutePrefRepo repo) {
        this.repo = repo;
    }

    /**
     * @return true when the user has an active mute for {@code groupId}
     *         at the moment of the call. {@code null}/{@code blank}
     *         inputs return false (nothing to enforce against).
     */
    @Transactional(readOnly = true)
    public boolean isMuted(String userEmail, String groupId) {
        if (userEmail == null || userEmail.isBlank()) return false;
        if (groupId == null || groupId.isBlank()) return false;
        Optional<GroupMutePref> opt = repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId);
        if (opt.isEmpty()) return false;
        Instant until = opt.get().getMutedUntil();
        return until != null && until.isAfter(Instant.now());
    }

    /**
     * Upsert mute deadline. Passing {@code mutedUntil = null}
     * un-mutes (row stays for audit; the enforcement check treats
     * a null deadline the same as no row).
     */
    @Transactional
    public GroupMutePref setMute(String userEmail, String groupId, Instant mutedUntil) {
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("userEmail is required");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        GroupMutePref pref = findOrCreatePref(userEmail, groupId);
        pref.setMutedUntil(mutedUntil);
        pref.setUpdatedAt(Instant.now());
        return repo.save(pref);
    }

    /**
     * Upsert daily quiet-hours window. {@code start} / {@code end}
     * are minutes-from-midnight in {@code timezone}; passing
     * {@code null} for both clears the window (timezone column is
     * also nulled so a stale TZ doesn't linger). Same row as the
     * mute deadline — quiet hours and mute coexist independently.
     */
    @Transactional
    public GroupMutePref setQuietHours(String userEmail, String groupId,
                                       Integer start, Integer end, String timezone) {
        if (userEmail == null || userEmail.isBlank()) {
            throw new IllegalArgumentException("userEmail is required");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId is required");
        }
        if (start != null && (start < 0 || start > 1439)) {
            throw new IllegalArgumentException("quietStart must be 0..1439");
        }
        if (end != null && (end < 0 || end > 1439)) {
            throw new IllegalArgumentException("quietEnd must be 0..1439");
        }
        // Both must be set together, or both null. Half-configured
        // windows would make enforcement read as "set" while
        // missing one of the bounds.
        if ((start == null) != (end == null)) {
            throw new IllegalArgumentException("quietStart and quietEnd must both be set or both null");
        }
        GroupMutePref pref = findOrCreatePref(userEmail, groupId);
        pref.setQuietStart(start);
        pref.setQuietEnd(end);
        pref.setQuietTimezone(start == null ? null : (timezone == null || timezone.isBlank() ? "UTC" : timezone));
        pref.setUpdatedAt(Instant.now());
        return repo.save(pref);
    }

    /**
     * True when the user's current local time (in
     * {@link GroupMutePref#getQuietTimezone()}, falling back to UTC
     * if missing or unparseable) falls inside the configured
     * quiet-hours window. Handles wrap-around windows
     * ({@code start > end}, e.g. 22:00→07:00) by checking the union
     * of {@code [start, 24:00)} and {@code [00:00, end)}.
     *
     * <p>Returns false when either bound is null (window not
     * configured), when the bounds are equal (zero-width window
     * means "always on"), or when there's no pref row at all.</p>
     */
    @Transactional(readOnly = true)
    public boolean isInQuietHours(String userEmail, String groupId) {
        if (userEmail == null || userEmail.isBlank()) return false;
        if (groupId == null || groupId.isBlank()) return false;
        Optional<GroupMutePref> opt = repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId);
        if (opt.isEmpty()) return false;
        GroupMutePref pref = opt.get();
        Integer start = pref.getQuietStart();
        Integer end = pref.getQuietEnd();
        if (start == null || end == null || start.equals(end)) return false;

        ZoneId tz;
        try {
            tz = ZoneId.of(pref.getQuietTimezone() == null ? "UTC" : pref.getQuietTimezone());
        } catch (Exception e) {
            tz = ZoneId.of("UTC");
        }
        LocalTime now = Instant.now().atZone(tz).toLocalTime();
        int cur = now.getHour() * 60 + now.getMinute();
        return start < end
                ? (cur >= start && cur < end)        // 09:00 → 17:00
                : (cur >= start || cur < end);       // 22:00 → 07:00 wrap
    }

    private GroupMutePref findOrCreatePref(String userEmail, String groupId) {
        return repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId)
                .orElseGet(() -> {
                    GroupMutePref p = new GroupMutePref();
                    p.setUserEmail(userEmail.trim().toLowerCase());
                    p.setGroupId(groupId);
                    return p;
                });
    }

    /** Convenience: read current pref or empty. */
    @Transactional(readOnly = true)
    public Optional<GroupMutePref> getMute(String userEmail, String groupId) {
        if (userEmail == null || userEmail.isBlank()) return Optional.empty();
        if (groupId == null || groupId.isBlank()) return Optional.empty();
        return repo.findByUserEmailIgnoreCaseAndGroupId(userEmail, groupId);
    }
}
