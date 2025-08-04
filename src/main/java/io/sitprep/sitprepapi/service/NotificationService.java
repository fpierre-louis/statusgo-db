package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.util.GroupUrlUtil;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final WebSocketMessageSender webSocketMessageSender;
    private final UserInfoRepo userInfoRepo;



    public NotificationService(WebSocketMessageSender webSocketMessageSender, UserInfoRepo userInfoRepo) {
        this.webSocketMessageSender = webSocketMessageSender;
        this.userInfoRepo = userInfoRepo;
    }

    /**
     *
     *
     *
     * Sends a Firebase and in-app WebSocket notification to each token.
     */
    public void sendNotification(
            String title,
            String body,
            String sender,
            String iconUrl,
            Set<String> tokens,
            String notificationType,
            String referenceId,
            String targetUrl,
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
                        .putData("targetUrl", targetUrl != null ? targetUrl : "")
                        .putData("additionalData", additionalData != null ? additionalData : "")
                        .putData("title", title)
                        .putData("body", body)
                        .putData("icon", iconUrl != null ? iconUrl : "");

                Message message = messageBuilder.build();
                String response = FirebaseMessaging.getInstance().send(message);
                logger.info("✅ Sent Firebase message to token {}: {}", token, response);

            } catch (FirebaseMessagingException e) {
                logger.error("❌ FCM error sending to token {}: {}", token, e.getMessage());
                if ("registration-token-not-registered".equals(e.getMessagingErrorCode())) {
                    logger.warn("🗑️ Token invalid. Consider removing: {}", token);
                }
            } catch (Exception ex) {
                logger.error("❌ Unexpected error sending to token {}", token, ex);
            }
        }
    }

    /**
     * Broadcasts alert status change to all group members (except initiator).
     */
    public void notifyGroupAlertChange(Group group, String newAlertStatus, String initiatedByEmail) {
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) return;

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);
        Optional<UserInfo> senderInfo = userInfoRepo.findByUserEmail(initiatedByEmail);

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String alertMessage = "🚨 Important: " + owner + " here! Checking in on you. Click here and let me know your status.";

        Set<String> tokens = users.stream()
                .filter(user -> !user.getUserEmail().equalsIgnoreCase(initiatedByEmail))
                .map(UserInfo::getFcmtoken)
                .filter(token -> token != null && !token.isEmpty())
                .collect(Collectors.toSet());

        String groupUrl = GroupUrlUtil.getGroupTargetUrl(group);

        for (UserInfo user : users) {
            if (user.getUserEmail().equalsIgnoreCase(initiatedByEmail)) continue;

            // ✅ FCM Notification
            String token = user.getFcmtoken();
            if (token != null && !token.isEmpty()) {
                sendNotification(
                        group.getGroupName(),
                        alertMessage,
                        owner,
                        "/images/group-alert-icon.png",
                        Set.of(token), // ✅ Safe now
                        "alert",
                        group.getGroupId(),
                        "/status-now",
                        null
                );

                webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                        user.getUserEmail(),
                        group.getGroupName(),
                        alertMessage,
                        "/images/group-alert-icon.png",
                        "alert",
                        "/status-now",
                        group.getGroupId(),
                        Instant.now()
                ));
            }


            // ✅ WebSocket In-App Notification
            webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                    user.getUserEmail(),
                    group.getGroupName(),
                    alertMessage,
                    "/images/group-alert-icon.png",
                    "alert",
                    "/status-now",
                    group.getGroupId(),
                    Instant.now()
            ));
        }

        logger.info("📣 Group alert status change sent to {} members for group {}", tokens.size(), group.getGroupId());
    }

}
