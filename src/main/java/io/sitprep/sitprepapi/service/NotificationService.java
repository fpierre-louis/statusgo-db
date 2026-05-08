package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Presence-aware notifications:
 * - If user is ONLINE (WebSocket session active) -> deliver in-app via STOMP (+ log)
 * - Else, deliver via FCM (Android/Web via data + Notification; iOS via APNs block) (+ log)
 *
 * Legacy sendNotification(...) is kept for compatibility with callers that already have token sets.
 */
@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final WebSocketMessageSender webSocketMessageSender;
    private final UserInfoRepo userInfoRepo;
    private final NotificationLogRepo notificationLogRepo;
    private final WebSocketPresenceService presenceService;
    private final PushPolicyService pushPolicyService;

    public NotificationService(WebSocketMessageSender webSocketMessageSender,
                               UserInfoRepo userInfoRepo,
                               NotificationLogRepo notificationLogRepo,
                               WebSocketPresenceService presenceService,
                               PushPolicyService pushPolicyService) {
        this.webSocketMessageSender = webSocketMessageSender;
        this.userInfoRepo = userInfoRepo;
        this.notificationLogRepo = notificationLogRepo;
        this.presenceService = presenceService;
        this.pushPolicyService = pushPolicyService;
    }

    /**
     * Decide push lane for an outgoing notification by mapping the
     * legacy free-form {@code notificationType} string onto a structured
     * {@link Category}. Returns null when the type isn't mapped — caller
     * should fall through to legacy behavior in that case (no policy
     * applied, but log row still written for audit).
     *
     * <p>Mapping is the boundary between the free-form caller API and
     * the policy chokepoint. Future call sites should pass {@link Category}
     * directly (overload below) so this best-effort lookup isn't needed.</p>
     */
    private Category mapTypeToCategory(String notificationType) {
        if (notificationType == null) return null;
        return switch (notificationType.toLowerCase(Locale.ROOT)) {
            // Group / household alert flips. The fan-out doesn't
            // distinguish household vs org by group type — defaulting
            // to ORG; household-specific flips can pass Category directly
            // via the overload to opt into critical-bypass quiet hours.
            case "alert", "group_status" -> Category.GROUP_ALERT_ORG;
            case "post_notification", "post_mention" -> Category.MENTION;
            case "comment_on_post" -> Category.COMMENT_REPLY;
            case "new_member" -> Category.NEW_MEMBER;
            case "pending_member" -> Category.PENDING_MEMBER_REQUEST;
            case "task_assigned" -> Category.TASK_ASSIGNED;
            case "plan_activation" -> Category.PLAN_ACTIVATION_RECEIVED;
            case "activation_ack" -> Category.ACTIVATION_ACK;
            default -> null;
        };
    }

    /**
     * Single chokepoint for {@code NotificationLog} writes. Always
     * populates the new {@code lane} + {@code category} columns
     * (shipped 2026-04-29 for the inbox redesign) so the inbox
     * surface can render cleanly. Existing call sites pass null lane
     * + null category for unmapped types — the FE inbox treats null
     * lane as Lane B (silent inbox) per the spec.
     */
    private void saveLogRow(String recipientEmail,
                            String notificationType,
                            String token,
                            String title,
                            String body,
                            String referenceId,
                            String targetUrl,
                            boolean success,
                            String errorMessage,
                            Lane lane,
                            Category category) {
        NotificationLog row = new NotificationLog(
                recipientEmail,
                notificationType,
                token,
                title,
                body,
                referenceId,
                targetUrl,
                Instant.now(),
                success,
                errorMessage
        );
        if (lane != null) row.setLane(lane.name());
        if (category != null) row.setCategory(category.name());
        NotificationLog saved = notificationLogRepo.save(row);

        // Live-update fan-out: prepend the new row in any open inbox
        // tab via STOMP. Per NOTIFICATIONS_INBOX.md the inbox page
        // listens on /topic/notifications/{userEmail} for kind-tagged
        // events; "created" carries the saved row so the FE can drop
        // it into the list without an extra round trip. Skip rows
        // whose lane is "DROP" — those weren't user-visible to begin
        // with (rate-limited or DND-suppressed entirely) and shouldn't
        // surface in the inbox.
        if (saved != null && lane != Lane.DROP) {
            try {
                webSocketMessageSender.sendInboxEvent(
                        recipientEmail,
                        java.util.Map.of(
                                "kind", "created",
                                "row", toInboxRowMap(saved)
                        )
                );
            } catch (Exception e) {
                // Broadcast failure must never break the persisted save.
                logger.warn("Inbox WS broadcast failed for {}: {}",
                        recipientEmail, e.getMessage());
            }
        }
    }

    /**
     * Handle FCM stale-token errors by nulling the recipient's
     * {@code fcmtoken} on UserInfo. Per
     * docs/PUSH_NOTIFICATION_POLICY.md "FCM token lifecycle":
     * <blockquote>
     *   On send: if FCM returns UNREGISTERED or INVALID_ARGUMENT,
     *   null the user's fcmtoken field. The next foreground app open
     *   refreshes via firebase.messaging().getToken() and PATCHes
     *   back.
     * </blockquote>
     *
     * <p>Token-match guard: if the stored fcmtoken doesn't match the
     * one we just tried, the user re-registered between our send and
     * this error returning. Don't clobber the fresh token because of
     * a stale-token failure.</p>
     */
    private void handleFcmDeliveryError(FirebaseMessagingException e,
                                        String recipientEmail,
                                        String triedToken) {
        if (e == null || recipientEmail == null || triedToken == null) return;
        MessagingErrorCode code = e.getMessagingErrorCode();
        if (code != MessagingErrorCode.UNREGISTERED
                && code != MessagingErrorCode.INVALID_ARGUMENT) {
            return;
        }
        try {
            userInfoRepo.findByUserEmailIgnoreCase(recipientEmail).ifPresent(u -> {
                if (triedToken.equals(u.getFcmtoken())) {
                    u.setFcmtoken(null);
                    userInfoRepo.save(u);
                    logger.info("Cleared stale FCM token for {} (code={})",
                            recipientEmail, code);
                }
            });
        } catch (Exception suppress) {
            // Token cleanup is opportunistic — never let it bubble
            // through and mask the original send failure.
            logger.warn("Failed to clear stale FCM token for {}: {}",
                    recipientEmail, suppress.getMessage());
        }
    }

    /**
     * Lightweight inbox-row payload for the WS push. Mirrors what the
     * existing {@code GET /api/notifications} endpoint returns so the FE
     * can drop the WS row straight into its list without a separate
     * shape conversion. {@code Map} keeps the wire shape decoupled from
     * the entity in case the inbox DTO diverges later.
     */
    private static java.util.Map<String, Object> toInboxRowMap(NotificationLog n) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", n.getId());
        m.put("recipientEmail", n.getRecipientEmail());
        m.put("notificationType", n.getType());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("referenceId", n.getReferenceId());
        m.put("targetUrl", n.getTargetUrl());
        m.put("timestamp", n.getTimestamp());
        m.put("readAt", n.getReadAt());
        m.put("lane", n.getLane());
        m.put("category", n.getCategory());
        m.put("archivedAt", n.getArchivedAt());
        return m;
    }

    /**
     * Core presence-aware delivery.
     * If online -> STOMP only (no FCM); if offline -> FCM (with APNs for iOS and data for Android/Web).
     * Adds channel/category hints for Android/iOS to tune UX without changing client logic.
     */
    public void deliverPresenceAware(String recipientEmail,
                                     String title,
                                     String body,
                                     String senderName,
                                     String iconUrl,
                                     String notificationType,
                                     String referenceId,
                                     String targetUrl,
                                     String additionalData,
                                     String recipientFcmTokenOrNull) {

        boolean online = presenceService.isUserOnline(recipientEmail);
        String channelId = channelForType(notificationType);
        String category = categoryForType(notificationType);

        // Apply policy: map legacy notificationType → structured Category,
        // evaluate against UserAlertPreference + quiet hours, get a Lane.
        // Unmapped types skip policy entirely (lane = null) so legacy
        // behavior is preserved — the inbox FE treats null lane as B.
        Category catEnum = mapTypeToCategory(notificationType);
        Lane lane = (catEnum != null)
                ? pushPolicyService.evaluate(recipientEmail, catEnum, /* severity */ null)
                : null;
        if (lane == Lane.DROP) {
            // User opted out of this category entirely (or master-switched
            // off). No log, no push, no socket. Suppressed per policy.
            return;
        }

        if (online) {
            // Lane C is in-session-only ephemeral — no log row. Lanes A
            // and B and "no-policy" all still log + socket since the
            // user is currently looking at the app.
            try {
                webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                        recipientEmail,
                        title,
                        body,
                        iconUrl,
                        notificationType,
                        targetUrl,
                        referenceId,
                        Instant.now()
                ));
                if (lane != Lane.C) {
                    logSocketDelivery(recipientEmail, notificationType, title, body,
                            referenceId, targetUrl, lane, catEnum);
                }
                logger.info("🟢 Socket notification sent to online user {}", recipientEmail);
            } catch (Exception e) {
                logger.warn("Socket notification failed for {}: {}", recipientEmail, e.getMessage());
            }
            // Online users get socket only to avoid duplicate toasts.
            return;
        }

        // Offline. Lane C requires the user be present, so it's a
        // no-op when offline.
        if (lane == Lane.C) return;

        // Offline + Lane B: skip FCM, just log so the inbox surface
        // gets the row when the user opens the app.
        if (lane == Lane.B) {
            saveLogRow(recipientEmail, notificationType, recipientFcmTokenOrNull,
                    title, body, referenceId, targetUrl,
                    /* success */ false,
                    /* error */ "Lane B (silent inbox)",
                    lane, catEnum);
            return;
        }

        // Offline + Lane A (or no-policy fallback) → FCM if token available.
        if (recipientFcmTokenOrNull == null || recipientFcmTokenOrNull.isEmpty()) {
            logger.info("No FCM token for offline user {}, logging only.", recipientEmail);
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl,
                    false, "No token (offline)", lane, catEnum);
            return;
        }

        boolean success = false;
        String errorMessage = null;

        try {
            // ANDROID/Web data – keep your existing keys
            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setSound("default")
                            .build())
                    .build();

            // iOS APNs block (correct API: use ApnsConfig + Aps; put custom keys via Aps or ApnsConfig.putCustomData)
            ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                    .putHeader("apns-priority", "10"); // 10 = alert, 5 = background

            Aps.Builder apsBuilder = Aps.builder()
                    .setMutableContent(true)      // enables notification service extension (if you have one)
                    .setSound("default");

            // Custom metadata inside "aps"
            apsBuilder.putCustomData("notificationType", safe(notificationType));
            apsBuilder.putCustomData("referenceId", safe(referenceId));
            apsBuilder.putCustomData("targetUrl", safe(targetUrl));
            apsBuilder.putCustomData("title", safe(title));
            apsBuilder.putCustomData("body", safe(body));
            apsBuilder.putCustomData("channelId", safe(channelId));
            apsBuilder.putCustomData("category", safe(category));
            // iOS 15+ lock-screen affordances: interruption-level
            // (Focus-mode break-through), relevance-score (stack
            // ranking), thread-id (group related items).
            applyIosLockScreenAffordances(apsBuilder, notificationType, referenceId);
            apnsBuilder.setAps(apsBuilder.build());

            Message msg = Message.builder()
                    .setToken(recipientFcmTokenOrNull)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .setImage(iconUrl != null && iconUrl.startsWith("http") ? iconUrl : null)
                            .build())
                    // Android/Web data
                    .putData("notificationType", safe(notificationType))
                    .putData("referenceId", safe(referenceId))
                    .putData("sender", safe(senderName))
                    .putData("targetUrl", safe(targetUrl))
                    .putData("additionalData", safe(additionalData))
                    .putData("title", safe(title))
                    .putData("body", safe(body))
                    .putData("icon", safe(iconUrl))
                    .putData("channelId", safe(channelId))
                    .putData("category", safe(category))
                    .setAndroidConfig(androidConfig)
                    .setApnsConfig(apnsBuilder.build()) // <-- iOS APNs
                    .build();

            String response = FirebaseMessaging.getInstance().send(msg);
            logger.info("🔵 FCM sent to {} -> {}", recipientEmail, response);
            success = true;
        } catch (FirebaseMessagingException e) {
            errorMessage = e.getMessage();
            logger.error("❌ FCM error for {}: {}", recipientEmail, errorMessage);
            handleFcmDeliveryError(e, recipientEmail, recipientFcmTokenOrNull);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            logger.error("❌ Unexpected FCM error for {}: {}", recipientEmail, errorMessage, e);
        } finally {
            saveLogRow(recipientEmail, notificationType, recipientFcmTokenOrNull,
                    title, body, referenceId, targetUrl,
                    success, errorMessage, lane, catEnum);
        }
    }

    /**
     * Legacy wrapper kept for backward compatibility.
     * Iterates the provided token set and sends one FCM per token, logging per attempt.
     */
    public void sendNotification(String title,
                                 String body,
                                 String sender,
                                 String iconUrl,
                                 Set<String> tokens,
                                 String notificationType,
                                 String referenceId,
                                 String targetUrl,
                                 String additionalData,
                                 String recipientEmail) {
        // Apply policy at the top — same gating as deliverPresenceAware.
        // sendNotification is the legacy multi-token wrapper but the
        // policy still applies: a user who muted earthquakes shouldn't
        // get an FCM through the legacy path either.
        Category catEnum = mapTypeToCategory(notificationType);
        Lane lane = (catEnum != null && recipientEmail != null)
                ? pushPolicyService.evaluate(recipientEmail, catEnum, /* severity */ null)
                : null;
        if (lane == Lane.DROP) return;
        // Lane B = silent inbox: skip FCM, write log row.
        if (lane == Lane.B) {
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl,
                    false, "Lane B (silent inbox)",
                    lane, catEnum);
            return;
        }
        // Lane C = ephemeral: this path is offline-FCM only, so Lane C
        // here is a no-op (banner happens elsewhere).
        if (lane == Lane.C) return;

        if (tokens == null || tokens.isEmpty()) {
            logger.info("No tokens for {}, skipping FCM.", recipientEmail);
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl,
                    false, "No tokens provided",
                    lane, catEnum);
            return;
        }

        String channelId = channelForType(notificationType);
        String category = categoryForType(notificationType);

        for (String token : tokens) {
            boolean success = false;
            String errorMessage = null;

            try {
                AndroidConfig androidConfig = AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setSound("default")
                                .build())
                        .build();

                ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                        .putHeader("apns-priority", "10");

                Aps.Builder apsBuilder = Aps.builder()
                        .setMutableContent(true)
                        .setSound("default");

                apsBuilder.putCustomData("notificationType", safe(notificationType));
                apsBuilder.putCustomData("referenceId", safe(referenceId));
                apsBuilder.putCustomData("targetUrl", safe(targetUrl));
                apsBuilder.putCustomData("title", safe(title));
                apsBuilder.putCustomData("body", safe(body));
                apsBuilder.putCustomData("channelId", safe(channelId));
                apsBuilder.putCustomData("category", safe(category));
                // iOS 15+ lock-screen affordances — see helper docstring.
                applyIosLockScreenAffordances(apsBuilder, notificationType, referenceId);
                apnsBuilder.setAps(apsBuilder.build());

                Message msg = Message.builder()
                        .setToken(token)
                        .setNotification(Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .setImage(iconUrl != null && iconUrl.startsWith("http") ? iconUrl : null)
                                .build())
                        .putData("notificationType", safe(notificationType))
                        .putData("referenceId", safe(referenceId))
                        .putData("sender", safe(sender))
                        .putData("targetUrl", safe(targetUrl))
                        .putData("additionalData", safe(additionalData))
                        .putData("title", safe(title))
                        .putData("body", safe(body))
                        .putData("icon", safe(iconUrl))
                        .putData("channelId", safe(channelId))
                        .putData("category", safe(category))
                        .setAndroidConfig(androidConfig)
                        .setApnsConfig(apnsBuilder.build())
                        .build();

                String response = FirebaseMessaging.getInstance().send(msg);
                logger.info("✅ FCM sent to {} -> {}", recipientEmail, response);
                success = true;
            } catch (FirebaseMessagingException e) {
                errorMessage = e.getMessage();
                logger.error("❌ FCM error for {}: {}", recipientEmail, errorMessage);
                handleFcmDeliveryError(e, recipientEmail, token);
            } catch (Exception e) {
                errorMessage = e.getMessage();
                logger.error("❌ Unexpected FCM error for {}: {}", recipientEmail, errorMessage, e);
            } finally {
                saveLogRow(recipientEmail, notificationType, token,
                        title, body, referenceId, targetUrl,
                        success, errorMessage, lane, catEnum);
            }
        }
    }

    /**
     * Presence-aware fan-out of a group alert to all members except the initiator.
     */
    public void notifyGroupAlertChange(Group group, String newAlertStatus, String initiatedByEmail) {
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) return;

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String title = group.getGroupName();
        String body = "🚨 Important: " + owner + " here! Checking in on you. Click here and let me know your status.";
        String type = "alert";
        String referenceId = group.getGroupId();
        String targetUrl = "/status-now";

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        for (UserInfo user : users) {
            if (user.getUserEmail().equalsIgnoreCase(initiatedByEmail)) continue;

            deliverPresenceAware(
                    user.getUserEmail(),
                    title,
                    body,
                    owner,
                    "/images/group-alert-icon.png",
                    type,
                    referenceId,
                    targetUrl,
                    null,
                    user.getFcmtoken()
            );
        }

        logger.info("📢 Presence-aware alert fan-out completed for group '{}' to {} members.",
                group.getGroupName(), users.size());
    }

    /** Log a socket-only delivery so we can backfill even if FCM was not used. */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl) {
        logSocketDelivery(recipientEmail, type, title, body, referenceId, targetUrl, null, null);
    }

    /**
     * Lane-aware overload. Used by {@code deliverPresenceAware} after
     * policy evaluation so the {@code NotificationLog} row carries the
     * lane + category for the inbox surface.
     */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl,
                                  Lane lane,
                                  Category category) {
        saveLogRow(recipientEmail, type,
                /* token */ null,
                title, body, referenceId, targetUrl,
                /* success */ true,
                /* error */ null,
                lane, category);
    }

    // ---------------------- helpers ----------------------

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Map notification types into Android channel IDs.
     * Keep this in sync with your native channel creation if you add a wrapper.
     */
    private String channelForType(String type) {
        if (type == null) return "general";
        switch (type) {
            case "alert":
            case "group_status":
            case "PLAN_ACTIVATION":
                return "alerts";
            case "comment_on_post":
            case "comment_thread_reply":
                return "conversations";
            case "new_member":
            case "pending_member":
                return "membership";
            default:
                return "general";
        }
    }

    /**
     * Map notification types into iOS UNNotificationCategory identifiers.
     *
     * <p>These strings MUST match the identifiers registered in the iOS
     * native shell (see {@code ios/App/App/AppDelegate.swift}
     * {@code registerNotificationCategories()}). When iOS receives an
     * APNs payload with {@code aps.category = "GROUP_ALERT"}, it looks
     * up the matching registered category and renders its action buttons
     * on the lock screen / banner / Notification Center.</p>
     *
     * <p>String name carries iOS semantics now (it's a category id), not
     * a generic "hint" — the category name is load-bearing for the
     * action-button UX. Don't rename without updating AppDelegate.swift
     * AND the dispatcher in
     * {@code src/shared/notifications/NotificationActionDispatcher.jsx}.</p>
     *
     * <p>Currently registered categories with action buttons (v1):</p>
     * <ul>
     *   <li><b>GROUP_ALERT</b> — household / group alert flip; actions: SAFE / HELP / INJURED</li>
     * </ul>
     *
     * <p>Other categories return a sensible identifier so the payload
     * carries the meta info, but iOS won't render action buttons until
     * the category is also registered native-side. Adding more:
     * (1) declare the category + actions in AppDelegate.swift,
     * (2) add the dispatch case in NotificationActionDispatcher.jsx,
     * (3) return the matching id here.</p>
     */
    private String categoryForType(String type) {
        if (type == null) return "SYSTEM";
        switch (type) {
            // Group / household alert flip. Actions: SAFE / HELP / INJURED.
            // Registered in AppDelegate.swift.
            case "alert":
            case "group_status":
                return "GROUP_ALERT";
            // Plan activation received. Actions registration TODO —
            // returns canonical id now so the payload carries it for
            // future native registration.
            case "PLAN_ACTIVATION":
            case "plan_activation":
                return "PLAN_ACTIVATION";
            case "activation_ack":
                return "ACTIVATION_ACK";
            case "task_assigned":
                return "TASK_ASSIGNED";
            case "pending_member":
                return "PENDING_MEMBER";
            case "new_member":
                return "NEW_MEMBER";
            case "comment_on_post":
            case "comment_thread_reply":
                return "COMMENT_REPLY";
            case "post_mention":
                return "MENTION";
            default:
                return "SYSTEM";
        }
    }

    /**
     * Whether this notification type warrants iOS
     * {@code interruption-level: "time-sensitive"} (iOS 15+). Time-sensitive
     * notifications break through Focus modes (Sleep, Work, etc.) without
     * needing the critical-alerts entitlement — appropriate for life-safety
     * categories where the user explicitly wants to be reachable.
     *
     * <p>Routine categories (comments, new members, task assignments) get
     * the default {@code "active"} level so a 2am comment doesn't punch
     * through Sleep Focus.</p>
     *
     * <p>Mirrors the policy in {@code docs/PUSH_NOTIFICATION_POLICY.md}'s
     * critical-bypass list, but loosened — time-sensitive ≠ critical;
     * critical alerts (DND bypass) require a separate Apple entitlement
     * we deliberately don't request for v1.</p>
     */
    private boolean isTimeSensitiveType(String type) {
        if (type == null) return false;
        switch (type) {
            case "alert":
            case "group_status":
            case "PLAN_ACTIVATION":
            case "plan_activation":
                return true;
            default:
                return false;
        }
    }

    /**
     * Apply the three iOS-native lock-screen affordances missing from the
     * pre-2026-05-08 payload:
     *
     * <ul>
     *   <li><b>{@code interruption-level}</b> — {@code "time-sensitive"} for
     *       alert categories so they break through Focus modes;
     *       {@code "active"} (default) for routine categories.</li>
     *   <li><b>{@code relevance-score}</b> — 0.0–1.0 hint to iOS notification
     *       stack ranking. Alerts score 1.0 so they outrank routine items
     *       when the lock screen is full.</li>
     *   <li><b>{@code thread-id}</b> — groups related notifications under
     *       one expandable lock-screen stack. We thread by {@code referenceId}
     *       so all alerts for one household, all acks for one activation, all
     *       comments on one post collapse into a single threaded summary.</li>
     * </ul>
     *
     * <p>Apple ignores unknown keys, so this is a forward-only payload tweak
     * — older iOS versions silently fall back to the prior behavior. None of
     * these keys require an entitlement; they're plain APNs payload fields.</p>
     */
    private void applyIosLockScreenAffordances(Aps.Builder apsBuilder,
                                                String notificationType,
                                                String referenceId) {
        boolean timeSensitive = isTimeSensitiveType(notificationType);
        apsBuilder.putCustomData("interruption-level", timeSensitive ? "time-sensitive" : "active");
        apsBuilder.putCustomData("relevance-score", timeSensitive ? 1.0 : 0.5);
        if (referenceId != null && !referenceId.isEmpty()) {
            apsBuilder.setThreadId(referenceId);
        }
    }
}