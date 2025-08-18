package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.*;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final WebSocketMessageSender webSocketMessageSender;
    private final UserInfoRepo userInfoRepo;
    private final NotificationLogRepo notificationLogRepo;

    public NotificationService(WebSocketMessageSender webSocketMessageSender,
                               UserInfoRepo userInfoRepo,
                               NotificationLogRepo notificationLogRepo) {
        this.webSocketMessageSender = webSocketMessageSender;
        this.userInfoRepo = userInfoRepo;
        this.notificationLogRepo = notificationLogRepo;
    }

    /** Send FCM to a set of tokens and persist a log row for each token attempt. */
    public void sendNotification(String title,
                                 String body,
                                 String sender,
                                 String iconUrl,
                                 Set<String> tokens,
                                 String notificationType,
                                 String referenceId,
                                 String targetUrl,
                                 String additionalData,
                                 String recipientEmail) {
        if (tokens == null || tokens.isEmpty()) {
            logger.info("No tokens for {}, skipping FCM.", recipientEmail);
            return;
        }

        for (String token : tokens) {
            boolean success = false;
            String errorMessage = null;

            try {
                Message msg = Message.builder()
                        .setToken(token)
                        .setNotification(Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .setImage(iconUrl != null && iconUrl.startsWith("http") ? iconUrl : null)
                                .build())
                        .putData("notificationType", safe(notificationType))
                        .putData("referenceId", safe(referenceId))
                        .putData("sender", safe(sender))
                        .putData("targetUrl", safe(targetUrl))
                        .putData("additionalData", safe(additionalData))
                        .putData("title", safe(title))
                        .putData("body", safe(body))
                        .putData("icon", safe(iconUrl))
                        .build();

                String response = FirebaseMessaging.getInstance().send(msg);
                logger.info("✅ FCM sent to {} -> {}", recipientEmail, response);
                success = true;
            } catch (FirebaseMessagingException e) {
                errorMessage = e.getMessage();
                logger.error("❌ FCM error for {}: {}", recipientEmail, errorMessage);
            } catch (Exception e) {
                errorMessage = e.getMessage();
                logger.error("❌ Unexpected FCM error for {}: {}", recipientEmail, errorMessage, e);
            } finally {
                notificationLogRepo.save(new NotificationLog(
                        recipientEmail,
                        notificationType,
                        token,
                        title,
                        body,
                        referenceId,
                        targetUrl,
                        Instant.now(),
                        success,
                        errorMessage
                ));
            }
        }
    }

    /** Log a socket-only delivery so we can backfill even if FCM was not used. */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl) {
        notificationLogRepo.save(new NotificationLog(
                recipientEmail,
                type,
                null,           // no token for socket writes
                title,
                body,
                referenceId,
                targetUrl,
                Instant.now(),
                true,           // socket write attempted
                null
        ));
    }

    /** Group Alerts — one socket send per recipient + FCM + logs */
    public void notifyGroupAlertChange(Group group, String newAlertStatus, String initiatedByEmail) {
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) return;

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String title = group.getGroupName();
        String body = "🚨 Important: " + owner + " here! Checking in on you. Click here and let me know your status.";
        String type = "alert";
        String referenceId = group.getGroupId();
        String targetUrl = "/status-now";

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        for (UserInfo user : users) {
            if (user.getUserEmail().equalsIgnoreCase(initiatedByEmail)) continue;

            // 1) Socket (single attempt)
            webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                    user.getUserEmail(), title, body, "/images/group-alert-icon.png",
                    type, targetUrl, referenceId, Instant.now()
            ));

            // Log socket delivery
            logSocketDelivery(user.getUserEmail(), type, title, body, referenceId, targetUrl);

            // 2) FCM (if token) + log
            if (user.getFcmtoken() != null && !user.getFcmtoken().isEmpty()) {
                sendNotification(
                        title,
                        body,
                        owner,
                        "/images/group-alert-icon.png",
                        Set.of(user.getFcmtoken()),
                        type,
                        referenceId,
                        targetUrl,
                        null,
                        user.getUserEmail()
                );
            }
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
