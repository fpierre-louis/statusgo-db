package io.sitprep.sitprepapi.service;

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
     * @param actionUrl        (Optional) A URL to include in the notification for user actions.
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
            String actionUrl,
            String additionalData
    ) {
        // Ensure tokens are not null or empty
        if (tokens == null || tokens.isEmpty()) {
            logger.warn("No recipient tokens provided. Skipping notification.");
            return;
        }

        // Log notification details
        logger.info("Sending notification...");
        logger.info("Title: {}", title);
        logger.info("Body: {}", body);
        logger.info("Sender: {}", sender);
        logger.info("Icon URL: {}", iconUrl);
        logger.info("Notification Type: {}", notificationType);
        logger.info("Reference ID: {}", referenceId);
        logger.info("Action URL: {}", actionUrl);
        logger.info("Additional Data: {}", additionalData);

        // Simulate sending the notification
        for (String token : tokens) {
            logger.info("Notification sent to token: {}", token);
        }

        // Add your actual notification-sending logic here (e.g., Firebase Cloud Messaging)
    }
}
