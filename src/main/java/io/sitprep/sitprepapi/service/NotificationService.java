package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.*;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

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
     * Presence-aware delivery:
     *  - If user is online (has an active WS), send in-app notification via socket & log; SKIP FCM.
     *  - If user is offline, send FCM (and log).
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
            return; // Do not send FCM for online users
        }

        // Offline path → FCM if token is available
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

    /** Explicit socket-delivery logger (still useful if other services push directly). */
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
