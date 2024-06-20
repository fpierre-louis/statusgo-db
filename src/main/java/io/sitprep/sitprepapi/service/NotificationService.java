package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    public void sendNotification(String title, String body, Set<String> tokens) {
        for (String token : tokens) {
            Message notificationMessage = Message.builder()
                    .setToken(token)
                    .putData("title", title)
                    .putData("body", body)
                    .putData("image", "/icon-120.png")
                    .build();

            try {
                String response = FirebaseMessaging.getInstance().send(notificationMessage);
                logger.info("Successfully sent message: {}", response);
            } catch (FirebaseMessagingException e) {
                logger.error("Error sending message: {}", e.getMessage(), e);

                // Check for unregistered token error and remove the token from your database
                if (e.getErrorCode().equals("UNREGISTERED")) {
                    // Handle the unregistered token case
                }
            }
        }
    }
}
