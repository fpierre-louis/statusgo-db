package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import io.sitprep.sitprepapi.repo.LiveLocationSessionRepo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Registers the ActivityKit push tokens the native reporter sends
 * (live_activities GAMEPLAN §2.5, §3.5). The contract is written at the top of
 * {@code docs/epics/notifications-and-native/live_activities/EXEC-P1-backend.md}.
 *
 * <p><b>The owner is always the verified caller.</b> The body cannot name one.
 * A token row is the right to receive pushes about a group's check-in, so a
 * situation token is accepted only from someone on that group's roster, and a
 * location-share token only from the session's own sharer.</p>
 */
@Service
public class LiveActivityTokenService {

    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]{16,256}$");
    private static final int MAX_REFERENCE = 128;
    private static final int MAX_ACTIVITY_ID = 64;
    private static final int MAX_FCM = 512;

    public record RegisterRequest(String kind,
                                  String tokenType,
                                  String referenceId,
                                  String activityId,
                                  String apnsToken,
                                  String fcmToken) {}

    /** The stored row as the caller sees it. Neither token is echoed. */
    public record TokenDto(Long id,
                           String kind,
                           String tokenType,
                           String referenceId,
                           String activityId,
                           Instant createdAt,
                           Instant updatedAt) {}

    private final LiveActivityTokenRepo repo;
    private final GroupRepo groupRepo;
    private final LiveLocationSessionRepo sessionRepo;
    private final LiveActivityPushService pushService;
    private io.sitprep.sitprepapi.repo.NotificationLogRepo notificationLogs;

    public LiveActivityTokenService(LiveActivityTokenRepo repo,
                                    GroupRepo groupRepo,
                                    LiveLocationSessionRepo sessionRepo,
                                    LiveActivityPushService pushService) {
        this.repo = repo;
        this.groupRepo = groupRepo;
        this.sessionRepo = sessionRepo;
        this.pushService = pushService;
    }

    /** P3b: who was told about an alert thread (setter-injected, so the constructor tests stay as they are). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationLogs(io.sitprep.sitprepapi.repo.NotificationLogRepo notificationLogs) {
        this.notificationLogs = notificationLogs;
    }

    /**
     * Upsert by {@code apnsToken}. A token that rotated for the same activity
     * retires the caller's older row for it, so a card is never pushed twice.
     */
    @Transactional
    public TokenDto register(String callerEmail, RegisterRequest req) {
        String caller = normalizeEmail(callerEmail);
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated request required");
        if (req == null) throw bad("body required");

        String kind = lower(req.kind());
        if (kind == null || !LiveActivityToken.KINDS.contains(kind)) {
            throw bad("kind must be one of situation, location_share, checkin_request, official_warning");
        }
        String tokenType = lower(req.tokenType());
        if (tokenType == null) tokenType = LiveActivityToken.TYPE_UPDATE;
        if (!LiveActivityToken.TOKEN_TYPES.contains(tokenType)) {
            throw bad("tokenType must be update or push_to_start");
        }
        String apns = trim(req.apnsToken());
        if (apns == null || !HEX.matcher(apns).matches()) throw bad("apnsToken must be 16-256 hex characters");
        apns = apns.toLowerCase(Locale.ROOT);
        String fcm = trim(req.fcmToken());
        if (fcm == null || fcm.length() > MAX_FCM || fcm.chars().anyMatch(Character::isWhitespace)) {
            throw bad("fcmToken is required");
        }

        String referenceId = null;
        String activityId = null;
        if (LiveActivityToken.TYPE_UPDATE.equals(tokenType)) {
            referenceId = trim(req.referenceId());
            activityId = trim(req.activityId());
            if (referenceId == null || referenceId.length() > MAX_REFERENCE) throw bad("referenceId is required");
            if (activityId == null || activityId.length() > MAX_ACTIVITY_ID) throw bad("activityId is required");
            authorizeReference(kind, referenceId, caller);
        }

        Instant now = Instant.now();
        LiveActivityToken row = repo.findByApnsToken(apns).orElseGet(LiveActivityToken::new);
        row.setUserEmail(caller);
        row.setKind(kind);
        row.setTokenType(tokenType);
        row.setReferenceId(referenceId);
        row.setActivityId(activityId);
        row.setApnsToken(apns);
        row.setFcmToken(fcm);
        row.setEndedAt(null);
        row.setUpdatedAt(now);
        LiveActivityToken saved = repo.save(row);

        if (activityId != null) {
            for (LiveActivityToken older : repo.findLiveByUserAndActivity(caller, activityId)) {
                if (!Objects.equals(older.getId(), saved.getId())) {
                    older.setEndedAt(now);
                    repo.save(older);
                }
            }
        }

        afterCommit(() -> pushService.tokenRegistered(saved));
        return toDto(saved);
    }

    /** End the caller's rows for one activity. Idempotent; never touches another user's rows. */
    @Transactional
    public int unregister(String callerEmail, String activityId) {
        String caller = normalizeEmail(callerEmail);
        if (caller == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated request required");
        String id = trim(activityId);
        if (id == null) return 0;
        List<LiveActivityToken> rows = repo.findLiveByUserAndActivity(caller, id);
        Instant now = Instant.now();
        for (LiveActivityToken t : rows) {
            t.setEndedAt(now);
            repo.save(t);
        }
        return rows.size();
    }

    /** How far back a delivered warning row counts; matches the dispatcher's delivery lookback. A card lives at most 8 h. */
    static final java.time.Duration WARNING_TOLD_WINDOW = java.time.Duration.ofDays(7);

    private void authorizeReference(String kind, String referenceId, String caller) {
        if (LiveActivityToken.KIND_OFFICIAL_WARNING.equals(kind)) {
            // P3b: the card follows an alert thread. Only someone the thread
            // was delivered to may follow it, the same people the card was
            // started for (an inbox or push row carries the thread key).
            if (notificationLogs == null
                    || !notificationLogs.wasToldAboutThread(caller, referenceId,
                            Instant.now().minus(WARNING_TOLD_WINDOW))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a warning you were sent");
            }
            return;
        }
        if (LiveActivityToken.KIND_LOCATION_SHARE.equals(kind)) {
            var session = sessionRepo.findById(referenceId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
            if (!caller.equalsIgnoreCase(trim(session.getUserEmail()))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your location share");
            }
            return;
        }
        Group g = groupRepo.findByGroupId(referenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (!onRoster(g, caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only this group's members can follow its check-in");
        }
    }

    static boolean onRoster(Group g, String email) {
        if (g == null || email == null) return false;
        if (email.equalsIgnoreCase(trim(g.getOwnerEmail()))) return true;
        if (containsIgnoreCase(g.getAdminEmails(), email)) return true;
        return containsIgnoreCase(g.getMemberEmails(), email);
    }

    private static boolean containsIgnoreCase(List<String> list, String email) {
        if (list == null) return false;
        for (String e : list) {
            if (e != null && e.trim().equalsIgnoreCase(email)) return true;
        }
        return false;
    }

    private static TokenDto toDto(LiveActivityToken t) {
        return new TokenDto(t.getId(), t.getKind(), t.getTokenType(), t.getReferenceId(),
                t.getActivityId(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    private static ResponseStatusException bad(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String lower(String s) {
        String t = trim(s);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    private static String normalizeEmail(String s) {
        String t = trim(s);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }
}
