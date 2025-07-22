package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    /**
     * Sends a notification to the specified recipients.
     *
     * @param title            The title of the notification.
     * @param body             The body/content of the notification.
     * @param sender           The sender's name or identifier.
     * @param iconUrl          URL of the icon/image to include in the notification.
     * @param tokens           A set of FCM tokens representing the recipients.
     * @param notificationType The type/category of the notification (e.g., "alert", "post_notification").
     * @param referenceId      A reference ID related to the notification (e.g., groupId or postId).
     * @param targetUrl        (Optional) The direct URL to navigate users when clicking the notification.
     * @param additionalData   (Optional) Additional metadata to attach to the notification.
     */
    public void sendNotification(
            String title,
            String body,
            String sender,
            String iconUrl,
            Set<String> tokens,
            String notificationType,
            String referenceId,
            String targetUrl,      // <-- renamed for clarity from actionUrl
            String additionalData
    ) {
        if (tokens == null || tokens.isEmpty()) {
            logger.warn("No recipient tokens provided. Skipping notification.");
            return;
        }

        for (String token : tokens) {
            try {
                Message.Builder messageBuilder = Message.builder()
                        .setToken(token)
                        .setNotification(Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .setImage(iconUrl != null && iconUrl.startsWith("http") ? iconUrl : null)
                                .build())
                        .putData("notificationType", notificationType)
                        .putData("referenceId", referenceId != null ? referenceId : "")
                        .putData("sender", sender != null ? sender : "")
                        .putData("targetUrl", targetUrl != null ? targetUrl : "")   // ✅ Important line
                        .putData("additionalData", additionalData != null ? additionalData : "")
                        .putData("title", title)
                        .putData("body", body)
                        .putData("icon", iconUrl != null ? iconUrl : "");

                Message message = messageBuilder.build();
                String response = FirebaseMessaging.getInstance().send(message);
                logger.info("Successfully sent message to token {}: {}", token, response);

            } catch (FirebaseMessagingException e) {
                logger.error("Error sending FCM notification to token {}: {}", token, e.getMessage());
                if ("registration-token-not-registered".equals(e.getMessagingErrorCode())) {
                    logger.warn("Token no longer valid. Should remove: {}", token);
                }
            } catch (Exception ex) {
                logger.error("Unexpected error while sending notification to token {}", token, ex);
            }
        }
    }
}
