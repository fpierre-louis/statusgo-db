// src/main/java/io/sitprep/sitprepapi/service/NotificationService.java
package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.*;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import io.sitprep.sitprepapi.util.GroupUrlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /** Core presence-aware delivery: WS if online, FCM if offline (with logging). */
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

        if (online) {
            try {
                webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                        recipientEmail, title, body, iconUrl, notificationType, targetUrl, referenceId, Instant.now()
                ));
                logSocketDelivery(recipientEmail, notificationType, title, body, referenceId, targetUrl);
                logger.info("🟢 Socket notification sent to online user {}", recipientEmail);
            } catch (Exception e) {
                logger.warn("Socket notification failed for {}: {}", recipientEmail, e.getMessage());
            }
            // Do not send FCM for online users
            return;
        }

        // Offline → FCM (if token)
        if (recipientFcmTokenOrNull == null || recipientFcmTokenOrNull.isEmpty()) {
            logger.info("No FCM token for offline user {}, logging only.", recipientEmail);
            notificationLogRepo.save(new NotificationLog(
                    recipientEmail, notificationType, null, title, body, referenceId, targetUrl,
                    Instant.now(), false, "No token (offline)"
            ));
            return;
        }

        boolean success = false;
        String errorMessage = null;

        try {
            Message msg = Message.builder()
                    .setToken(recipientFcmTokenOrNull)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .setImage(iconUrl != null && iconUrl.startsWith("http") ? iconUrl : null)
                            .build())
                    .putData("notificationType", safe(notificationType))
                    .putData("referenceId", safe(referenceId))
                    .putData("sender", safe(senderName))
                    .putData("targetUrl", safe(targetUrl))
                    .putData("additionalData", safe(additionalData))
                    .putData("title", safe(title))
                    .putData("body", safe(body))
                    .putData("icon", safe(iconUrl))
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
                    recipientEmail, notificationType, recipientFcmTokenOrNull, title, body, referenceId, targetUrl,
                    Instant.now(), success, errorMessage
            ));
        }
    }

    /**
     * ✅ Legacy wrapper kept for backward compatibility with existing callers.
     * Iterates the provided token set and sends one FCM per token, logging per attempt.
     * (Use deliverPresenceAware for presence-aware behavior when you have the recipient email.)
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
                    recipientEmail, notificationType, null, title, body, referenceId, targetUrl,
                    Instant.now(), false, "No tokens provided"
            ));
            return;
        }

        for (String token : tokens) {
            boolean success = false;
            String errorMessage = null;

            try {
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
                        recipientEmail, notificationType, token, title, body, referenceId, targetUrl,
                        Instant.now(), success, errorMessage
                ));
            }
        }
    }

    /**
     * ✅ Restored for GroupService compatibility.
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

            // One call handles WS vs FCM and logging
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

    /** Explicit socket-delivery logger (for non-FCM paths). */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl) {
        notificationLogRepo.save(new NotificationLog(
                recipientEmail, type, null, title, body, referenceId, targetUrl, Instant.now(), true, null
        ));
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
