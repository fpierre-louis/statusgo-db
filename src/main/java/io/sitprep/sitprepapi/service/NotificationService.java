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
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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

    public NotificationService(WebSocketMessageSender webSocketMessageSender,
                               UserInfoRepo userInfoRepo,
                               NotificationLogRepo notificationLogRepo,
                               WebSocketPresenceService presenceService) {
        this.webSocketMessageSender = webSocketMessageSender;
        this.userInfoRepo = userInfoRepo;
        this.notificationLogRepo = notificationLogRepo;
        this.presenceService = presenceService;
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

        if (online) {
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
                logSocketDelivery(recipientEmail, notificationType, title, body, referenceId, targetUrl);
                logger.info("🟢 Socket notification sent to online user {}", recipientEmail);
            } catch (Exception e) {
                logger.warn("Socket notification failed for {}: {}", recipientEmail, e.getMessage());
            }
            // Online users get socket only to avoid duplicate toasts.
            return;
        }

        // Offline → FCM (if token available)
        if (recipientFcmTokenOrNull == null || recipientFcmTokenOrNull.isEmpty()) {
            logger.info("No FCM token for offline user {}, logging only.", recipientEmail);
            notificationLogRepo.save(new NotificationLog(
                    recipientEmail,
                    notificationType,
                    null,
                    title,
                    body,
                    referenceId,
                    targetUrl,
                    Instant.now(),
                    false,
                    "No token (offline)"
            ));
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
            notificationLogRepo.save(new NotificationLog(
                    recipientEmail,
                    notificationType,
                    recipientFcmTokenOrNull,
                    title,
                    body,
                    referenceId,
                    targetUrl,
                    Instant.now(),
                    success,
                    errorMessage
            ));
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
        if (tokens == null || tokens.isEmpty()) {
            logger.info("No tokens for {}, skipping FCM.", recipientEmail);
            notificationLogRepo.save(new NotificationLog(
                    recipientEmail,
                    notificationType,
                    null,
                    title,
                    body,
                    referenceId,
                    targetUrl,
                    Instant.now(),
                    false,
                    "No tokens provided"
            ));
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
                notificationLogRepo.save(new NotificationLog(
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
                ));
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
        notificationLogRepo.save(new NotificationLog(
                recipientEmail,
                type,
                null,           // no token for socket writes
                title,
                body,
                referenceId,
                targetUrl,
                Instant.now(),
                true,           // socket write attempted
                null
        ));
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