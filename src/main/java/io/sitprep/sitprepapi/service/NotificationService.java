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

            Message notificationMessage = Message.builder()
                    .setToken(token)
                    .putData("title", title)
                    .putData("body", body)
                    .putData("icon", "/images/icon-120.png")
                    .putData("badge", "/images/icon-512.png")
                    .build();

            try {
                String response = FirebaseMessaging.getInstance().send(notificationMessage);
                logger.info("Successfully sent message: {}", response);
            } catch (FirebaseMessagingException e) {
                logger.error("Error sending message: {}", e.getMessage(), e);
            }
        }
    }

    // Specific notification methods
    public void sendNewMemberNotification(String groupName, String memberName, Set<String> adminTokens) {
        String title = "New Member Joined";
        String body = memberName + " has joined your group " + groupName;
        sendNotification(title, body, adminTokens);
    }

    public void sendMemberLeftNotification(String groupName, String memberName, Set<String> adminTokens) {
        String title = "Member Left";
        String body = memberName + " has left your group " + groupName;
        sendNotification(title, body, adminTokens);
    }

    public void sendEmergencyCheckInReminder(Set<String> adminTokens) {
        String title = "Check-In Reminder";
        String body = "It's time to check in with your group members.";
        sendNotification(title, body, adminTokens);
    }

    public void sendGroupEventReminder(String eventName, String date, Set<String> memberTokens) {
        String title = "Event Reminder";
        String body = "Reminder: Upcoming event " + eventName + " on " + date;
        sendNotification(title, body, memberTokens);
    }

    public void sendWelcomeMessage(String groupName, String memberName, Set<String> memberTokens) {
        String title = "Welcome to the Group";
        String body = "Welcome " + memberName + " to " + groupName + "! We’re glad to have you.";
        sendNotification(title, body, memberTokens);
    }

    public void sendNewCommentNotification(String postId, String commentAuthor, Set<String> postAuthorToken) {
        String title = "New Comment on Your Post";
        String body = commentAuthor + " commented on your post with ID: " + postId;
        sendNotification(title, body, postAuthorToken);
    }

    public void sendNewPostNotification(String groupName, Set<String> memberTokens) {
        String title = "New Post in Your Group";
        String body = "There is a new post in your group " + groupName;
        sendNotification(title, body, memberTokens);
    }
}
