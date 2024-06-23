package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    public void sendNotification(String title, String body, Set<String> tokens) {
        List<String> registrationTokens = List.copyOf(tokens);

        MulticastMessage message = MulticastMessage.builder()
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .setImage("/images/icon-120.png")
                        .build())
                .putData("badge", "/images/icon-512.png")
                .putData("requireInteraction", "true")
                .putData("sound", "default")
                .addAllTokens(registrationTokens)
                .build();

        try {
            FirebaseMessaging.getInstance().sendMulticast(message);
            logger.info("Successfully sent message to multiple devices.");
        } catch (FirebaseMessagingException e) {
            logger.error("Error sending message: {}", e.getMessage(), e);
        }
    }
}
