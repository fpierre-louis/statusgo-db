package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
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
        notificationLogRepo.save(row);
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
     * Map notification types into high-level categories (used as hints by frontends).
     */
    private String categoryForType(String type) {
        if (type == null) return "SYSTEM";
        switch (type) {
            case "alert":
                return "ALERT_CHECKIN";
            case "group_status":
            case "PLAN_ACTIVATION":
                return "ALERT_GROUP";
            case "comment_on_post":
            case "comment_thread_reply":
                return "COMMENT";
            case "new_member":
            case "pending_member":
                return "MEMBERSHIP";
            default:
                return "SYSTEM";
        }
    }
}