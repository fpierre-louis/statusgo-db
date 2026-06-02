package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.NotificationPayload;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Presence-aware notifications:
 * - If user is ONLINE (WebSocket session active) -> deliver in-app via STOMP (+ log)
 * - Else, deliver via FCM (Android/Web via data + Notification; iOS via APNs block) (+ log)
 *
 * Legacy sendNotification(...) is kept for compatibility with callers that already have token sets.
 */
@Service
public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final WebSocketMessageSender webSocketMessageSender;
    private final UserInfoRepo userInfoRepo;
    private final NotificationLogRepo notificationLogRepo;
    private final WebSocketPresenceService presenceService;
    private final PushPolicyService pushPolicyService;
    private final GroupMuteService groupMuteService;

    public NotificationService(WebSocketMessageSender webSocketMessageSender,
                               UserInfoRepo userInfoRepo,
                               NotificationLogRepo notificationLogRepo,
                               WebSocketPresenceService presenceService,
                               PushPolicyService pushPolicyService,
                               GroupMuteService groupMuteService) {
        this.webSocketMessageSender = webSocketMessageSender;
        this.userInfoRepo = userInfoRepo;
        this.notificationLogRepo = notificationLogRepo;
        this.presenceService = presenceService;
        this.pushPolicyService = pushPolicyService;
        this.groupMuteService = groupMuteService;
    }

    /**
     * Decide push lane for an outgoing notification by mapping the
     * legacy free-form {@code notificationType} string onto a structured
     * {@link Category}. Returns null when the type isn't mapped — caller
     * should fall through to legacy behavior in that case (no policy
     * applied, but log row still written for audit).
     *
     * <p>Mapping is the boundary between the free-form caller API and
     * the policy chokepoint. Future call sites should pass {@link Category}
     * directly (overload below) so this best-effort lookup isn't needed.</p>
     */
    private Category mapTypeToCategory(String notificationType) {
        if (notificationType == null) return null;
        return switch (notificationType.toLowerCase(Locale.ROOT)) {
            // Group / household alert flips. The fan-out doesn't
            // distinguish household vs org by group type — defaulting
            // to ORG; household-specific flips can pass Category directly
            // via the overload to opt into critical-bypass quiet hours.
            case "alert", "group_status" -> Category.GROUP_ALERT_ORG;
            case "post_notification", "post_mention" -> Category.MENTION;
            case "comment_on_post", "comment_on_task" -> Category.COMMENT_REPLY;
            case "new_member" -> Category.NEW_MEMBER;
            case "pending_member" -> Category.PENDING_MEMBER_REQUEST;
            case "task_assigned" -> Category.TASK_ASSIGNED;
            case "plan_activation" -> Category.PLAN_ACTIVATION_RECEIVED;
            case "activation_ack" -> Category.ACTIVATION_ACK;
            case "check_in_request" -> Category.CHECK_IN_REQUEST;
            case "checkin_reminder", "checkin_auto_ended" -> Category.CHECK_IN_REVIEW;
            default -> null;
        };
    }

    /**
     * Single chokepoint for {@code NotificationLog} writes. Always
     * populates the new {@code lane} + {@code category} columns
     * (shipped 2026-04-29 for the inbox redesign) so the inbox
     * surface can render cleanly. Existing call sites pass null lane
     * + null category for unmapped types — the FE inbox treats null
     * lane as Lane B (silent inbox) per the spec.
     */
    private void saveLogRow(String recipientEmail,
                            String notificationType,
                            String token,
                            String title,
                            String body,
                            String referenceId,
                            String targetUrl,
                            boolean success,
                            String errorMessage,
                            Lane lane,
                            Category category) {
        NotificationLog row = new NotificationLog(
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
        );
        if (lane != null) row.setLane(lane.name());
        if (category != null) row.setCategory(category.name());
        NotificationLog saved = notificationLogRepo.save(row);

        // Live-update fan-out: prepend the new row in any open inbox
        // tab via STOMP. Per NOTIFICATIONS_INBOX.md the inbox page
        // listens on /topic/notifications/{userEmail} for kind-tagged
        // events; "created" carries the saved row so the FE can drop
        // it into the list without an extra round trip. Skip rows
        // whose lane is "DROP" — those weren't user-visible to begin
        // with (rate-limited or DND-suppressed entirely) and shouldn't
        // surface in the inbox.
        if (saved != null && lane != Lane.DROP) {
            try {
                webSocketMessageSender.sendInboxEvent(
                        recipientEmail,
                        java.util.Map.of(
                                "kind", "created",
                                "row", toInboxRowMap(saved)
                        )
                );
            } catch (Exception e) {
                // Broadcast failure must never break the persisted save.
                logger.warn("Inbox WS broadcast failed for {}: {}",
                        recipientEmail, e.getMessage());
            }
        }
    }

    /**
     * Handle FCM stale-token errors by nulling the recipient's
     * {@code fcmtoken} on UserInfo. Per
     * docs/PUSH_NOTIFICATION_POLICY.md "FCM token lifecycle":
     * <blockquote>
     *   On send: if FCM returns UNREGISTERED or INVALID_ARGUMENT,
     *   null the user's fcmtoken field. The next foreground app open
     *   refreshes via firebase.messaging().getToken() and PATCHes
     *   back.
     * </blockquote>
     *
     * <p>Token-match guard: if the stored fcmtoken doesn't match the
     * one we just tried, the user re-registered between our send and
     * this error returning. Don't clobber the fresh token because of
     * a stale-token failure.</p>
     */
    private void handleFcmDeliveryError(FirebaseMessagingException e,
                                        String recipientEmail,
                                        String triedToken) {
        if (e == null || recipientEmail == null || triedToken == null) return;
        MessagingErrorCode code = e.getMessagingErrorCode();
        if (code != MessagingErrorCode.UNREGISTERED
                && code != MessagingErrorCode.INVALID_ARGUMENT) {
            return;
        }
        try {
            userInfoRepo.findByUserEmailIgnoreCase(recipientEmail).ifPresent(u -> {
                if (triedToken.equals(u.getFcmtoken())) {
                    u.setFcmtoken(null);
                    userInfoRepo.save(u);
                    logger.info("Cleared stale FCM token for {} (code={})",
                            recipientEmail, code);
                }
            });
        } catch (Exception suppress) {
            // Token cleanup is opportunistic — never let it bubble
            // through and mask the original send failure.
            logger.warn("Failed to clear stale FCM token for {}: {}",
                    recipientEmail, suppress.getMessage());
        }
    }

    /**
     * Lightweight inbox-row payload for the WS push. Mirrors what the
     * existing {@code GET /api/notifications} endpoint returns so the FE
     * can drop the WS row straight into its list without a separate
     * shape conversion. {@code Map} keeps the wire shape decoupled from
     * the entity in case the inbox DTO diverges later.
     */
    private static java.util.Map<String, Object> toInboxRowMap(NotificationLog n) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", n.getId());
        m.put("recipientEmail", n.getRecipientEmail());
        m.put("notificationType", n.getType());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("referenceId", n.getReferenceId());
        m.put("targetUrl", n.getTargetUrl());
        m.put("timestamp", n.getTimestamp());
        m.put("readAt", n.getReadAt());
        m.put("lane", n.getLane());
        m.put("category", n.getCategory());
        m.put("errorMessage", n.getErrorMessage());
        m.put("archivedAt", n.getArchivedAt());
        return m;
    }

    /**
     * Core presence-aware delivery.
     * If online -> STOMP only (no FCM); if offline -> FCM (with APNs for iOS and data for Android/Web).
     * Adds channel/category hints for Android/iOS to tune UX without changing client logic.
     */
    public void deliverPresenceAware(String recipientEmail,
                                     String title,
                                     String body,
                                     String senderName,
                                     String iconUrl,
                                     String notificationType,
                                     String referenceId,
                                     String targetUrl,
                                     String additionalData,
                                     String recipientFcmTokenOrNull) {
        deliverPresenceAware(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, /* categoryOverride */ null);
    }

    /**
     * Group-scoped variant. Identical to {@link #deliverPresenceAware}
     * but consults {@link GroupMuteService#isMuted} against
     * {@code groupIdForMuteCheck} first — if the viewer has muted
     * the group, FCM + the STOMP banner are skipped and we write a
     * Lane-B-style "silent inbox" log row instead so the missed
     * message is recoverable on unmute. Mute is the user's signal
     * "stop pushing me", not "make this disappear."
     *
     * <p>Callers should be the ones that own a real {@code groupId}
     * (group post fan-out, group alert flip, group check-in). Other
     * notification types should keep using the legacy
     * {@link #deliverPresenceAware} method — the mute check would
     * mis-fire if {@code referenceId} carries something other than
     * a group id (e.g. activation id, comment id).</p>
     */
    public void deliverPresenceAwareForGroup(String recipientEmail,
                                             String title,
                                             String body,
                                             String senderName,
                                             String iconUrl,
                                             String notificationType,
                                             String referenceId,
                                             String targetUrl,
                                             String additionalData,
                                             String recipientFcmTokenOrNull,
                                             String groupIdForMuteCheck) {
        deliverPresenceAwareForGroup(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, groupIdForMuteCheck, /* categoryOverride */ null);
    }

    public void deliverPresenceAwareForGroup(String recipientEmail,
                                             String title,
                                             String body,
                                             String senderName,
                                             String iconUrl,
                                             String notificationType,
                                             String referenceId,
                                             String targetUrl,
                                             String additionalData,
                                             String recipientFcmTokenOrNull,
                                             String groupIdForMuteCheck,
                                             Category categoryOverride) {
        if (groupIdForMuteCheck != null && !groupIdForMuteCheck.isBlank()) {
            boolean muted = groupMuteService.isMuted(recipientEmail, groupIdForMuteCheck);
            boolean quiet = !muted && groupMuteService.isInQuietHours(recipientEmail, groupIdForMuteCheck);
            if (muted || quiet) {
                // Inbox row stays so the user can review what they
                // missed when the suppression clears (mute deadline
                // passes, or quiet-hours window ends). Errors are
                // swallowed — a logging hiccup mustn't block the
                // dispatch loop for other recipients.
                String reason = muted
                        ? "Muted by recipient (group=" + groupIdForMuteCheck + ")"
                        : "Quiet hours active (group=" + groupIdForMuteCheck + ")";
                try {
                    saveLogRow(recipientEmail, notificationType, recipientFcmTokenOrNull,
                            title, body, referenceId, targetUrl,
                            /* success */ false,
                            /* error */ reason,
                            Lane.B, categoryOverride != null
                                    ? categoryOverride
                                    : mapTypeToCategory(notificationType));
                } catch (Exception e) {
                    logger.warn("Suppression-path log write failed for {} group={}: {}",
                            recipientEmail, groupIdForMuteCheck, e.getMessage());
                }
                return;
            }
        }
        deliverPresenceAware(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, categoryOverride);
    }

    /**
     * Category-aware overload. Callers that know the policy category
     * up-front (e.g. household alert fan-out, which needs
     * {@code GROUP_ALERT_HOUSEHOLD} rather than the legacy
     * {@code GROUP_ALERT_ORG} default from
     * {@link #mapTypeToCategory(String)}) pass it explicitly so quiet-
     * hours critical-bypass works correctly. Legacy callers pass null
     * and get the same string-based mapping as before.
     */
    public void deliverPresenceAware(String recipientEmail,
                                     String title,
                                     String body,
                                     String senderName,
                                     String iconUrl,
                                     String notificationType,
                                     String referenceId,
                                     String targetUrl,
                                     String additionalData,
                                     String recipientFcmTokenOrNull,
                                     Category categoryOverride) {

        boolean online = presenceService.isUserOnline(recipientEmail);
        String channelId = channelForType(notificationType);
        String category = categoryForType(notificationType);

        // Apply policy: prefer the explicit categoryOverride when the
        // caller knows it (e.g. household vs org alert), fall back to
        // the legacy string mapping. Unmapped types still skip policy
        // entirely (lane = null) so back-compat is preserved — the
        // inbox FE treats null lane as B.
        Category catEnum = categoryOverride != null
                ? categoryOverride
                : mapTypeToCategory(notificationType);
        Lane lane = (catEnum != null)
                ? pushPolicyService.evaluate(recipientEmail, catEnum, /* severity */ null)
                : null;
        if (lane == Lane.DROP) {
            // User opted out of this category entirely (or master-switched
            // off). No log, no push, no socket. Suppressed per policy.
            return;
        }

        // Foregrounded client → push a STOMP in-app banner frame so the
        // user sees a toast immediately without waiting on FCM.
        //
        // As of 2026-05-18 this is a best-effort *addition*, not a
        // replacement: FCM still fires below for Lane A regardless of
        // presence. Presence is tracked from WebSocket connect/disconnect
        // events in an in-memory map with stale-pruning disabled and no
        // heartbeat (WebSocketPresenceService) — a mobile app suspended /
        // killed / network-dropped frequently never emits a clean
        // SessionDisconnectEvent, so it stays "online" forever and a
        // presence-gated FCM would be silently swallowed. The device push
        // must not depend on a signal we can't trust. The FE de-dupes the
        // socket banner against the FCM foreground frame.
        if (online) {
            try {
                webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                        recipientEmail,
                        title,
                        body,
                        iconUrl,
                        notificationType,
                        targetUrl,
                        referenceId,
                        Instant.now(),
                        // Lane is included on the STOMP frame so the FE
                        // can skip the optimistic unread-count bump on
                        // Lane C events (they don't earn an inbox row,
                        // so bumping then reconciling produces a flicker).
                        lane != null ? lane.name() : null
                ));
                logger.info("🟢 Socket banner sent to online user {}", recipientEmail);
            } catch (Exception e) {
                logger.warn("Socket notification failed for {}: {}", recipientEmail, e.getMessage());
            }
        }

        // Lane C is in-session-only ephemeral — no log row, never FCM.
        if (lane == Lane.C) return;

        // Lane B is the silent inbox: never FCM (online or offline), just
        // the log row so the inbox surface picks it up on next app open.
        if (lane == Lane.B) {
            saveLogRow(recipientEmail, notificationType, recipientFcmTokenOrNull,
                    title, body, referenceId, targetUrl,
                    /* success */ false,
                    /* error */ "Lane B (silent inbox)",
                    lane, catEnum);
            return;
        }

        // Lane A (or no-policy fallback) → FCM whenever a token exists,
        // whether or not the user currently holds a WebSocket session.
        if (recipientFcmTokenOrNull == null || recipientFcmTokenOrNull.isEmpty()) {
            logger.info("No FCM token for user {}, logging only.", recipientEmail);
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl,
                    false, "No token", lane, catEnum);
            return;
        }

        boolean success = false;
        String errorMessage = null;

        try {
            // ANDROID/Web data – keep your existing keys
            AndroidConfig androidConfig = AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder()
                            .setSound("default")
                            .build())
                    .build();

            // iOS APNs block (correct API: use ApnsConfig + Aps; put custom keys via Aps or ApnsConfig.putCustomData)
            ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                    .putHeader("apns-priority", "10"); // 10 = alert, 5 = background

            Aps.Builder apsBuilder = Aps.builder()
                    .setMutableContent(true)      // enables notification service extension (if you have one)
                    .setSound("default");

            // Custom metadata inside "aps"
            apsBuilder.putCustomData("notificationType", safe(notificationType));
            apsBuilder.putCustomData("referenceId", safe(referenceId));
            apsBuilder.putCustomData("targetUrl", safe(targetUrl));
            apsBuilder.putCustomData("title", safe(title));
            apsBuilder.putCustomData("body", safe(body));
            apsBuilder.putCustomData("channelId", safe(channelId));
            apsBuilder.putCustomData("category", safe(category));
            // iOS 15+ lock-screen affordances: interruption-level
            // (Focus-mode break-through), relevance-score (stack
            // ranking), thread-id (group related items).
            applyIosLockScreenAffordances(apsBuilder, notificationType, referenceId);
            // App-icon badge — recipient's unread inbox count + 1 for
            // this notification. Cleared to 0 by AppDelegate when the
            // app is opened. Null on a lookup failure → badge omitted
            // so iOS leaves the existing number untouched.
            Integer badge = unreadBadgeFor(recipientEmail);
            if (badge != null) apsBuilder.setBadge(badge);
            apnsBuilder.setAps(apsBuilder.build());

            Message msg = Message.builder()
                    .setToken(recipientFcmTokenOrNull)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .setImage(iconUrl != null && iconUrl.startsWith("http") ? iconUrl : null)
                            .build())
                    // Android/Web data
                    .putData("notificationType", safe(notificationType))
                    .putData("referenceId", safe(referenceId))
                    .putData("sender", safe(senderName))
                    .putData("targetUrl", safe(targetUrl))
                    .putData("additionalData", safe(additionalData))
                    .putData("title", safe(title))
                    .putData("body", safe(body))
                    .putData("icon", safe(iconUrl))
                    .putData("channelId", safe(channelId))
                    .putData("category", safe(category))
                    .setAndroidConfig(androidConfig)
                    .setApnsConfig(apnsBuilder.build()) // <-- iOS APNs
                    .build();

            String response = FirebaseMessaging.getInstance().send(msg);
            logger.info("🔵 FCM sent to {} -> {}", recipientEmail, response);
            success = true;
        } catch (FirebaseMessagingException e) {
            errorMessage = e.getMessage();
            logger.error("❌ FCM error for {}: {}", recipientEmail, errorMessage);
            handleFcmDeliveryError(e, recipientEmail, recipientFcmTokenOrNull);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            logger.error("❌ Unexpected FCM error for {}: {}", recipientEmail, errorMessage, e);
        } finally {
            saveLogRow(recipientEmail, notificationType, recipientFcmTokenOrNull,
                    title, body, referenceId, targetUrl,
                    success, errorMessage, lane, catEnum);
        }
    }

    /**
     * Legacy wrapper kept for backward compatibility.
     * Iterates the provided token set and sends one FCM per token, logging per attempt.
     */
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
        // Apply policy at the top — same gating as deliverPresenceAware.
        // sendNotification is the legacy multi-token wrapper but the
        // policy still applies: a user who muted earthquakes shouldn't
        // get an FCM through the legacy path either.
        Category catEnum = mapTypeToCategory(notificationType);
        Lane lane = (catEnum != null && recipientEmail != null)
                ? pushPolicyService.evaluate(recipientEmail, catEnum, /* severity */ null)
                : null;
        if (lane == Lane.DROP) return;
        // Lane B = silent inbox: skip FCM, write log row.
        if (lane == Lane.B) {
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl,
                    false, "Lane B (silent inbox)",
                    lane, catEnum);
            return;
        }
        // Lane C = ephemeral: this path is offline-FCM only, so Lane C
        // here is a no-op (banner happens elsewhere).
        if (lane == Lane.C) return;

        if (tokens == null || tokens.isEmpty()) {
            logger.info("No tokens for {}, skipping FCM.", recipientEmail);
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl,
                    false, "No tokens provided",
                    lane, catEnum);
            return;
        }

        String channelId = channelForType(notificationType);
        String category = categoryForType(notificationType);
        // App-icon badge for the recipient — identical for every device
        // token, so resolve it once outside the per-token loop.
        Integer badge = unreadBadgeFor(recipientEmail);

        for (String token : tokens) {
            boolean success = false;
            String errorMessage = null;

            try {
                AndroidConfig androidConfig = AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setSound("default")
                                .build())
                        .build();

                ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                        .putHeader("apns-priority", "10");

                Aps.Builder apsBuilder = Aps.builder()
                        .setMutableContent(true)
                        .setSound("default");

                apsBuilder.putCustomData("notificationType", safe(notificationType));
                apsBuilder.putCustomData("referenceId", safe(referenceId));
                apsBuilder.putCustomData("targetUrl", safe(targetUrl));
                apsBuilder.putCustomData("title", safe(title));
                apsBuilder.putCustomData("body", safe(body));
                apsBuilder.putCustomData("channelId", safe(channelId));
                apsBuilder.putCustomData("category", safe(category));
                // iOS 15+ lock-screen affordances — see helper docstring.
                applyIosLockScreenAffordances(apsBuilder, notificationType, referenceId);
                if (badge != null) apsBuilder.setBadge(badge);
                apnsBuilder.setAps(apsBuilder.build());

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
                        .putData("channelId", safe(channelId))
                        .putData("category", safe(category))
                        .setAndroidConfig(androidConfig)
                        .setApnsConfig(apnsBuilder.build())
                        .build();

                String response = FirebaseMessaging.getInstance().send(msg);
                logger.info("✅ FCM sent to {} -> {}", recipientEmail, response);
                success = true;
            } catch (FirebaseMessagingException e) {
                errorMessage = e.getMessage();
                logger.error("❌ FCM error for {}: {}", recipientEmail, errorMessage);
                handleFcmDeliveryError(e, recipientEmail, token);
            } catch (Exception e) {
                errorMessage = e.getMessage();
                logger.error("❌ Unexpected FCM error for {}: {}", recipientEmail, errorMessage, e);
            } finally {
                saveLogRow(recipientEmail, notificationType, token,
                        title, body, referenceId, targetUrl,
                        success, errorMessage, lane, catEnum);
            }
        }
    }

    /**
     * Presence-aware fan-out of a group alert (the Activate Check-in
     * trigger) to every member except the initiator.
     *
     * <p>Each recipient passes through {@link #deliverPresenceAware}
     * which applies the three-lane push policy — users who muted the
     * matching category get {@code Lane.DROP} and never see a banner /
     * FCM / inbox row. Online users get a STOMP frame only; offline
     * users with a live FCM token get an iOS time-sensitive APNs push.
     * Users with no token and no socket get a logged inbox row so they
     * find the alert on next app open.</p>
     *
     * <p>Household vs org routing: the policy category is computed from
     * {@code group.getGroupType()}. Household alerts route through
     * {@link Category#GROUP_ALERT_HOUSEHOLD} which is on the critical-
     * bypass list (a real household emergency at 3am needs to break
     * through Focus / quiet hours); org alerts route through
     * {@link Category#GROUP_ALERT_ORG} which respects quiet hours.
     * Before this split, every alert routed through ORG and household
     * alerts during quiet hours were quietly downgraded — a real bug
     * for the household use case.</p>
     */
    public void notifyGroupAlertChange(Group group, String newAlertStatus, String initiatedByEmail) {
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) {
            logger.info("📢 No member emails on group '{}', skipping alert fan-out.",
                    group != null ? group.getGroupName() : "(null)");
            return;
        }

        String owner = group.getOwnerName() != null ? group.getOwnerName() : "your group leader";
        String title = group.getGroupName();
        String body = "🚨 Important: " + owner + " here! Checking in on you. Click here and let me know your status.";
        String type = "alert";
        String referenceId = group.getGroupId();
        String targetUrl = "/status-now";

        // Household groups carry critical-bypass eligibility per
        // PushPolicyService.isCriticalBypass(...). Org / school /
        // neighborhood groups don't. Compute once outside the loop
        // since the group type is the same for every recipient.
        boolean isHousehold = HouseholdEventService.HOUSEHOLD_GROUP_TYPE
                .equalsIgnoreCase(group.getGroupType());
        Category alertCategory = isHousehold
                ? Category.GROUP_ALERT_HOUSEHOLD
                : Category.GROUP_ALERT_ORG;

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        int delivered = 0;
        for (UserInfo user : users) {
            if (user.getUserEmail() == null) continue;
            if (initiatedByEmail != null
                    && user.getUserEmail().equalsIgnoreCase(initiatedByEmail)) {
                continue;
            }

            deliverPresenceAware(
                    user.getUserEmail(),
                    title,
                    body,
                    owner,
                    "/images/group-alert-icon.png",
                    type,
                    referenceId,
                    targetUrl,
                    null,
                    user.getFcmtoken(),
                    alertCategory
            );
            delivered++;
        }

        logger.info("📢 Presence-aware alert fan-out completed for group '{}' (type={}, category={}): "
                        + "{} of {} members notified (initiator excluded).",
                group.getGroupName(), group.getGroupType(), alertCategory.name(),
                delivered, users.size());
    }

    /**
     * Fan out a non-emergency "please check in" ping to a group's
     * members. Phase 1 of {@code docs/BUSINESS_MODEL.md} — the family
     * check-in primitive.
     *
     * <p>Distinct from {@link #notifyGroupAlertChange}: this does NOT
     * flip the group's alert state and routes through the
     * {@code CHECK_IN_REQUEST} category — Lane A so it still reaches
     * people, but NOT on the critical-bypass list, so it respects each
     * recipient's quiet hours. A routine "everyone check in" should not
     * punch through a sleeping family at 3am.</p>
     *
     * <p>The initiator is excluded from the fan-out (they're the one
     * asking). {@code initiatorName} is used in the body copy so the
     * ping reads as personal ("Dana asked everyone to check in") rather
     * than system-generated.</p>
     */
    public void notifyCheckInRequest(Group group, String initiatedByEmail, String initiatorName) {
        if (group == null) return;
        List<String> memberEmails = group.getMemberEmails();
        if (memberEmails == null || memberEmails.isEmpty()) {
            logger.info("👋 No member emails on group '{}', skipping check-in-request fan-out.",
                    group.getGroupName());
            return;
        }

        String who = (initiatorName != null && !initiatorName.isBlank())
                ? initiatorName.trim()
                : "Someone in your household";
        String title = group.getGroupName() != null ? group.getGroupName() : "Check in";
        String body = who + " asked everyone to check in. Tap to share your status.";
        String referenceId = group.getGroupId();
        String targetUrl = "/status-now";

        List<UserInfo> users = userInfoRepo.findByUserEmailIn(memberEmails);

        int delivered = 0;
        for (UserInfo user : users) {
            if (user.getUserEmail() == null) continue;
            if (initiatedByEmail != null
                    && user.getUserEmail().equalsIgnoreCase(initiatedByEmail)) {
                continue;
            }
            deliverPresenceAware(
                    user.getUserEmail(),
                    title,
                    body,
                    who,
                    "/images/group-alert-icon.png",
                    "check_in_request",
                    referenceId,
                    targetUrl,
                    null,
                    user.getFcmtoken(),
                    Category.CHECK_IN_REQUEST
            );
            delivered++;
        }

        logger.info("👋 Check-in-request fan-out for group '{}': {} of {} members notified.",
                group.getGroupName(), delivered, users.size());
    }

    /** Log a socket-only delivery so we can backfill even if FCM was not used. */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl) {
        logSocketDelivery(recipientEmail, type, title, body, referenceId, targetUrl, null, null);
    }

    /**
     * Lane-aware overload. Used by {@code deliverPresenceAware} after
     * policy evaluation so the {@code NotificationLog} row carries the
     * lane + category for the inbox surface.
     */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl,
                                  Lane lane,
                                  Category category) {
        saveLogRow(recipientEmail, type,
                /* token */ null,
                title, body, referenceId, targetUrl,
                /* success */ true,
                /* error */ null,
                lane, category);
    }

    /**
     * Batched hazard-alert fan-out — the {@code AlertDispatchService}
     * severe-weather path. One {@link MulticastMessage} (identical
     * payload) is delivered to up to 500 device tokens in a single
     * {@link FirebaseMessaging#sendEachForMulticast} call — the
     * multicast form of {@code sendEach} — instead of N sequential
     * {@code .send()} round-trips.
     *
     * <p>Online recipients get a best-effort in-app STOMP banner AND
     * still go into the FCM multicast batch — a hazard alert is
     * life-safety, so a stale WebSocket session must not suppress the
     * device push (see the 2026-05-18 presence-gating fix). Offline
     * recipients with a live token go in the batch the same way. Every
     * recipient gets a {@code NotificationLog} row. Stale tokens
     * surfaced by the {@link BatchResponse} are nulled via
     * {@link #handleFcmDeliveryError}, same as the single-send path.</p>
     *
     * <p>Policy note: {@code hazard_alert} is intentionally un-mapped
     * in {@link #mapTypeToCategory} — life-safety weather warnings are
     * never quiet-hours-suppressed. The APNs payload carries
     * {@code interruption-level: time-sensitive} so it still breaks
     * through Focus modes (see {@link #applyIosLockScreenAffordances}).</p>
     */
    public void sendHazardAlertBatch(List<UserInfo> recipients,
                                     String title,
                                     String body,
                                     String referenceId,
                                     String targetUrl) {
        if (recipients == null || recipients.isEmpty()) return;

        final String type = "hazard_alert";
        List<String> batchTokens = new ArrayList<>();
        List<UserInfo> batchUsers = new ArrayList<>();

        for (UserInfo u : recipients) {
            String email = u != null ? u.getUserEmail() : null;
            if (email == null) continue;

            // Foregrounded client → best-effort in-app STOMP banner.
            // Unlike before, this does NOT skip FCM: the recipient still
            // goes into the multicast batch below so a stale/phantom
            // WebSocket session can't swallow a life-safety weather
            // alert. The FE de-dupes the socket banner vs the FCM frame.
            if (presenceService.isUserOnline(email)) {
                try {
                    webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                            email, title, body, null, type, targetUrl, referenceId,
                            Instant.now(), null));
                } catch (Exception e) {
                    logger.warn("Hazard-alert socket frame failed for {}: {}", email, e.getMessage());
                }
            }

            String token = u.getFcmtoken();
            if (token == null || token.isEmpty()) {
                saveLogRow(email, type, null, title, body, referenceId, targetUrl,
                        false, "No token", null, null);
                continue;
            }
            batchTokens.add(token);
            batchUsers.add(u);
        }

        if (batchTokens.isEmpty()) return;

        MulticastMessage multicast = MulticastMessage.builder()
                .addAllTokens(batchTokens)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .putData("notificationType", type)
                .putData("referenceId", safe(referenceId))
                .putData("targetUrl", safe(targetUrl))
                .putData("title", safe(title))
                .putData("body", safe(body))
                .putData("channelId", channelForType(type))
                .putData("category", categoryForType(type))
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setSound("default")
                                .build())
                        .build())
                .setApnsConfig(buildHazardApns(type, referenceId, title, body, targetUrl))
                .build();

        try {
            BatchResponse resp = FirebaseMessaging.getInstance().sendEachForMulticast(multicast);
            List<SendResponse> responses = resp.getResponses();
            for (int i = 0; i < responses.size() && i < batchUsers.size(); i++) {
                SendResponse r = responses.get(i);
                UserInfo u = batchUsers.get(i);
                String token = batchTokens.get(i);
                if (r.isSuccessful()) {
                    saveLogRow(u.getUserEmail(), type, token, title, body, referenceId, targetUrl,
                            true, null, null, null);
                } else {
                    FirebaseMessagingException ex = r.getException();
                    String err = ex != null ? ex.getMessage() : "Unknown FCM error";
                    saveLogRow(u.getUserEmail(), type, token, title, body, referenceId, targetUrl,
                            false, err, null, null);
                    handleFcmDeliveryError(ex, u.getUserEmail(), token);
                }
            }
            logger.info("📣 Hazard-alert multicast '{}': {} delivered, {} failed",
                    referenceId, resp.getSuccessCount(), resp.getFailureCount());
        } catch (Exception e) {
            logger.error("❌ Hazard-alert multicast send failed for {}: {}", referenceId, e.getMessage(), e);
            // Whole-batch failure — log a failed row per recipient so the
            // inbox still reflects the intent.
            for (int i = 0; i < batchUsers.size(); i++) {
                saveLogRow(batchUsers.get(i).getUserEmail(), type, batchTokens.get(i),
                        title, body, referenceId, targetUrl, false, e.getMessage(), null, null);
            }
        }
    }

    /**
     * APNs block for a {@code hazard_alert} multicast. Mirrors the APNs
     * config built inline in {@link #deliverPresenceAware} — priority 10,
     * mutable content, default sound, custom metadata, and the iOS 15+
     * lock-screen affordances (time-sensitive interruption level).
     */
    private ApnsConfig buildHazardApns(String type,
                                       String referenceId,
                                       String title,
                                       String body,
                                       String targetUrl) {
        ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                .putHeader("apns-priority", "10");
        Aps.Builder apsBuilder = Aps.builder()
                .setMutableContent(true)
                .setSound("default");
        // No aps.badge here: a hazard alert ships as one MulticastMessage
        // with a single shared payload, so a per-recipient unread count
        // can't be expressed. The badge corrects on the recipient's next
        // single-send push (deliverPresenceAware) or when they open the app.
        apsBuilder.putCustomData("notificationType", safe(type));
        apsBuilder.putCustomData("referenceId", safe(referenceId));
        apsBuilder.putCustomData("targetUrl", safe(targetUrl));
        apsBuilder.putCustomData("title", safe(title));
        apsBuilder.putCustomData("body", safe(body));
        apsBuilder.putCustomData("channelId", safe(channelForType(type)));
        apsBuilder.putCustomData("category", safe(categoryForType(type)));
        applyIosLockScreenAffordances(apsBuilder, type, referenceId);
        apnsBuilder.setAps(apsBuilder.build());
        return apnsBuilder.build();
    }

    // ---------------------- helpers ----------------------

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * App-icon badge number for an outgoing APNs push: the recipient's
     * current unread inbox count plus one for the notification being
     * delivered (its {@code NotificationLog} row is written by
     * {@code saveLogRow} after the send, so {@code countUnreadForUser}
     * doesn't see it yet).
     *
     * <p>Returns null when the count can't be resolved — null/blank
     * recipient or a DB hiccup. The caller then omits {@code aps.badge}
     * so iOS leaves the existing badge untouched rather than stamping a
     * wrong number. The icon badge is cleared to 0 in
     * {@code AppDelegate.applicationDidBecomeActive} when the user opens
     * the app; the next push re-stamps an accurate count.</p>
     */
    private Integer unreadBadgeFor(String recipientEmail) {
        if (recipientEmail == null || recipientEmail.isBlank()) return null;
        try {
            long badge = notificationLogRepo.countUnreadForUser(recipientEmail) + 1;
            return (int) Math.min(badge, Integer.MAX_VALUE);
        } catch (Exception e) {
            logger.warn("Badge count lookup failed for {}: {}", recipientEmail, e.getMessage());
            return null;
        }
    }

    /**
     * Map notification types into Android channel IDs.
     * Keep this in sync with your native channel creation if you add a wrapper.
     */
    private String channelForType(String type) {
        if (type == null) return "general";
        switch (type) {
            case "alert":
            case "group_status":
            case "hazard_alert":
            case "PLAN_ACTIVATION":
                return "alerts";
            case "comment_on_post":
            case "comment_on_task":
            case "comment_thread_reply":
                return "conversations";
            case "new_member":
            case "pending_member":
                return "membership";
            default:
                return "general";
        }
    }

    /**
     * Map notification types into iOS UNNotificationCategory identifiers.
     *
     * <p>These strings MUST match the identifiers registered in the iOS
     * native shell (see {@code ios/App/App/AppDelegate.swift}
     * {@code registerNotificationCategories()}). When iOS receives an
     * APNs payload with {@code aps.category = "GROUP_ALERT"}, it looks
     * up the matching registered category and renders its action buttons
     * on the lock screen / banner / Notification Center.</p>
     *
     * <p>String name carries iOS semantics now (it's a category id), not
     * a generic "hint" — the category name is load-bearing for the
     * action-button UX. Don't rename without updating AppDelegate.swift
     * AND the dispatcher in
     * {@code src/shared/notifications/NotificationActionDispatcher.jsx}.</p>
     *
     * <p>Currently registered categories with action buttons (v1):</p>
     * <ul>
     *   <li><b>GROUP_ALERT</b> — household / group alert flip; actions: SAFE / HELP / INJURED</li>
     * </ul>
     *
     * <p>Other categories return a sensible identifier so the payload
     * carries the meta info, but iOS won't render action buttons until
     * the category is also registered native-side. Adding more:
     * (1) declare the category + actions in AppDelegate.swift,
     * (2) add the dispatch case in NotificationActionDispatcher.jsx,
     * (3) return the matching id here.</p>
     */
    private String categoryForType(String type) {
        if (type == null) return "SYSTEM";
        switch (type) {
            // Group / household alert flip. Actions: SAFE / HELP / INJURED.
            // Registered in AppDelegate.swift.
            case "alert":
            case "group_status":
                return "GROUP_ALERT";
            // Plan activation received. Actions registration TODO —
            // returns canonical id now so the payload carries it for
            // future native registration.
            case "PLAN_ACTIVATION":
            case "plan_activation":
                return "PLAN_ACTIVATION";
            case "activation_ack":
                return "ACTIVATION_ACK";
            case "task_assigned":
                return "TASK_ASSIGNED";
            case "pending_member":
                return "PENDING_MEMBER";
            case "new_member":
                return "NEW_MEMBER";
            case "comment_on_post":
            case "comment_on_task":
            case "comment_thread_reply":
                return "COMMENT_REPLY";
            case "post_mention":
                return "MENTION";
            default:
                return "SYSTEM";
        }
    }

    /**
     * Whether this notification type warrants iOS
     * {@code interruption-level: "time-sensitive"} (iOS 15+). Time-sensitive
     * notifications break through Focus modes (Sleep, Work, etc.) without
     * needing the critical-alerts entitlement — appropriate for life-safety
     * categories where the user explicitly wants to be reachable.
     *
     * <p>Routine categories (comments, new members, task assignments) get
     * the default {@code "active"} level so a 2am comment doesn't punch
     * through Sleep Focus.</p>
     *
     * <p>Mirrors the policy in {@code docs/PUSH_NOTIFICATION_POLICY.md}'s
     * critical-bypass list, but loosened — time-sensitive ≠ critical;
     * critical alerts (DND bypass) require a separate Apple entitlement
     * we deliberately don't request for v1.</p>
     */
    private boolean isTimeSensitiveType(String type) {
        if (type == null) return false;
        switch (type) {
            case "alert":
            case "group_status":
            // hazard_alert = NWS Severe/Extreme weather warning fan-out
            // from AlertDispatchService. Life-safety: it should break
            // through Focus modes like the household alert flip does.
            case "hazard_alert":
            case "PLAN_ACTIVATION":
            case "plan_activation":
                return true;
            default:
                return false;
        }
    }

    /**
     * Apply the three iOS-native lock-screen affordances missing from the
     * pre-2026-05-08 payload:
     *
     * <ul>
     *   <li><b>{@code interruption-level}</b> — {@code "time-sensitive"} for
     *       alert categories so they break through Focus modes;
     *       {@code "active"} (default) for routine categories.</li>
     *   <li><b>{@code relevance-score}</b> — 0.0–1.0 hint to iOS notification
     *       stack ranking. Alerts score 1.0 so they outrank routine items
     *       when the lock screen is full.</li>
     *   <li><b>{@code thread-id}</b> — groups related notifications under
     *       one expandable lock-screen stack. We thread by {@code referenceId}
     *       so all alerts for one household, all acks for one activation, all
     *       comments on one post collapse into a single threaded summary.</li>
     * </ul>
     *
     * <p>Apple ignores unknown keys, so this is a forward-only payload tweak
     * — older iOS versions silently fall back to the prior behavior. None of
     * these keys require an entitlement; they're plain APNs payload fields.</p>
     */
    private void applyIosLockScreenAffordances(Aps.Builder apsBuilder,
                                                String notificationType,
                                                String referenceId) {
        boolean timeSensitive = isTimeSensitiveType(notificationType);
        apsBuilder.putCustomData("interruption-level", timeSensitive ? "time-sensitive" : "active");
        apsBuilder.putCustomData("relevance-score", timeSensitive ? 1.0 : 0.5);
        if (referenceId != null && !referenceId.isEmpty()) {
            apsBuilder.setThreadId(referenceId);
        }
    }
}
