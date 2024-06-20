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

    @Autowired
    private UserInfoRepo userInfoRepo;

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
                if ("UNREGISTERED".equals(e.getErrorCode())) {
                    logger.warn("FCM token is unregistered. Removing it from the database: {}", token);
                    removeInvalidFcmToken(token);
                } else {
                    logger.error("Error sending message: {}", e.getMessage(), e);
                }
            }
        }
    }

    private void removeInvalidFcmToken(String token) {
        Optional<UserInfo> userOptional = userInfoRepo.findByFcmtoken(token);
        if (userOptional.isPresent()) {
            UserInfo user = userOptional.get();
            user.setFcmtoken(null);
            userInfoRepo.save(user);
            logger.info("Removed invalid FCM token for user: {}", user.getUserEmail());
        } else {
            logger.warn("No user found with FCM token: {}", token);
        }
    }
}
