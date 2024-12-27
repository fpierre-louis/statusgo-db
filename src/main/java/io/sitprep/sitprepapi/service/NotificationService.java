package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    public void sendNotification(String title, String body, String iconUrl, String imageUrl, String sound, String from, Set<String> tokens, String type, String groupId) {
        for (String token : tokens) {
            logger.info("Sending notification to token: {}", token);

            // Use provided or default values
            String iconUrlToUse = iconUrl != null ? iconUrl : "https://firebasestorage.googleapis.com/v0/b/sitprep-new.appspot.com/o/icons%2Fdefault-icon.png?alt=media";
            String imageUrlToUse = imageUrl != null ? imageUrl : "https://firebasestorage.googleapis.com/v0/b/sitprep-new.appspot.com/o/posts%2Fpost-image.png?alt=media";
            String soundToUse = sound != null ? sound : "default"; // Use "default" or a custom sound file

            Message notificationMessage = Message.builder()
                    .setToken(token)
                    .putData("title", title)
                    .putData("body", body)
                    .putData("icon", iconUrlToUse)  // Small icon for the notification
                    .putData("image", imageUrlToUse)  // Large image for the notification body
                    .putData("sound", soundToUse)  // Sound to play when notification is received
                    .putData("badge", "1") // Badge count, typically an integer
                    .putData("type", type)
                    .putData("groupId", groupId)
                    .build();

            try {
                String response = FirebaseMessaging.getInstance().send(notificationMessage);
                logger.info("Successfully sent message: {}", response);
            } catch (FirebaseMessagingException e) {
                logger.error("Error sending message: {}", e.getMessage(), e);
            }
        }
    }

}
