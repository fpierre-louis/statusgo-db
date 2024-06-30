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

    public void sendNotification(String title, String body, Set<String> tokens) {
        for (String token : tokens) {
            logger.info("Sending notification to token: {}", token);

            // Log the payload being sent
            logger.info("Notification Payload - Title: {}, Body: {}, Token: {}", title, body, token);

            Message notificationMessage = Message.builder()
                    .setToken(token)
                    .putData("title", title)
                    .putData("body", body)
                    .putData("icon", "/images/icon-120.png")
//                    .putData("badge", "/images/icon-512.png")
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
