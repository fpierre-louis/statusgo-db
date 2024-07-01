package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class NotificationService {

    public void sendNotification(String title, String body, Set<String> tokens) {
        for (String token : tokens) {
            Message notificationMessage = Message.builder()
                    .setToken(token)
                    .putData("title", title)
                    .putData("body", body)
                    .putData("icon", "/images/icon-120.png")
                    .build();

            try {
                String response = FirebaseMessaging.getInstance().send(notificationMessage);
                System.out.println("Successfully sent message: " + response);
            } catch (FirebaseMessagingException e) {
                e.printStackTrace();
            }
        }
    }
}
