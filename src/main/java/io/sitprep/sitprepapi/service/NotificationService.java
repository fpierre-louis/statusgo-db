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
import io.sitprep.sitprepapi.notifications.NotificationEventType;
import io.sitprep.sitprepapi.notifications.NotificationPresentationBuilder;
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
import java.util.Map;
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

    /** P0-B: is this recipient likely hiding right now? Lazy — breaks a bean cycle. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ConcealmentSafetyService concealmentSafetyService;

    /**
     * Builds the inbox presentation contract (docs/epics/notification-ecosystem-completed).
     * Field-injected and optional so hand-built test instances keep working;
     * when absent, rows are written without one and normalized on read.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NotificationPresentationBuilder presentationBuilder;
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
     * The mention type string, as ONE literal shared by the emitter
     * ({@code GroupPostService}) and both readers here.
     *
     * <p>A string constant rather than a convention because the convention
     * failed: the emitter sent a spelling neither reader accepted, an
     * unmapped type maps to a null Category, and a null Category skips the
     * policy branches entirely instead of erroring. The failure mode of a
     * mistyped notification type is a SILENT WRONG BRANCH, so the type has to
     * be a thing the compiler can check rather than a thing a reviewer can
     * notice. Same reasoning as {@code HazardType}, one layer down.</p>
     */
    public static final String TYPE_POST_MENTION = "post_mention";

    /**
     * A Readiness Token unlock. Mapped to {@link Category#TOKEN_UNLOCKED} so even
     * a caller that forgot the category can't fall through to the null-lane
     * send path.
     */
    public static final String TYPE_TOKEN_UNLOCKED = "token_unlocked";

    /** The batched hazard path's type — the push and its Lane B inbox twin. */
    public static final String TYPE_HAZARD_ALERT = "hazard_alert";

    /** Error marker on a Lane B row: written to the inbox, deliberately not pushed. */
    static final String LANE_B_SILENT_INBOX = "Lane B (silent inbox)";

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
    static Category mapTypeToCategory(String notificationType) {
        if (notificationType == null) return null;
        // One vocabulary: the taxonomy registry owns type → policy category.
        // A null here is either an unregistered type (a test fails the build)
        // or a recorded no-policy type (hazard_alert, the reminder family).
        return NotificationEventType.forType(notificationType).policy();
    }

    /**
     * Single chokepoint for {@code NotificationLog} writes. Always
     * populates the new {@code lane} + {@code category} columns
     * (shipped 2026-04-29 for the inbox redesign) so the inbox
     * surface can render cleanly. Existing call sites pass null lane
     * + null category for unmapped types — the FE inbox treats null
     * lane as Lane B (silent inbox) per the spec.
     *
     * <p>Legacy overload — preserved for call sites that don't carry
     * actor identity (hazard fan-out, retention notices, etc.).
     * Delegates with a null {@code actorUserId} so older paths keep
     * working unchanged.</p>
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
        saveLogRow(recipientEmail, notificationType, token, title, body,
                referenceId, targetUrl, success, errorMessage, lane, category,
                /* actorUserId */ null);
    }

    /**
     * Actor-aware variant. {@code actorUserId} is the stable user id of
     * the person who triggered the notification (post author, commenter,
     * alert initiator, etc.) — read by the FE inbox so tapping the actor
     * avatar / name deep-links to {@code /profile/:identifier} via
     * {@code useProfileNav}. Nullable: system-originated rows (hazard
     * alerts, FEMA declarations) have no actor.
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
                            Category category,
                            String actorUserId) {
        saveLogRow(recipientEmail, notificationType, token, title, body,
                referenceId, targetUrl, /* additionalData */ null,
                success, errorMessage, lane, category, actorUserId);
    }

    private void saveLogRow(String recipientEmail,
                            String notificationType,
                            String token,
                            String title,
                            String body,
                            String referenceId,
                            String targetUrl,
                            String additionalData,
                            boolean success,
                            String errorMessage,
                            Lane lane,
                            Category category,
                            String actorUserId) {
        saveLogRow(recipientEmail, notificationType, token, title, body, referenceId, targetUrl,
                additionalData, success, errorMessage, lane, category, actorUserId,
                /* presentation — built from the row below */ null);
    }

    /**
     * Presentation-aware variant: callers that already built the presentation
     * for the banner / push (one build per send, not two) pass it in; null
     * means build it here from the row.
     */
    private void saveLogRow(String recipientEmail,
                            String notificationType,
                            String token,
                            String title,
                            String body,
                            String referenceId,
                            String targetUrl,
                            String additionalData,
                            boolean success,
                            String errorMessage,
                            Lane lane,
                            Category category,
                            String actorUserId,
                            Map<String, Object> presentation) {
        saveLogRow(recipientEmail, notificationType, token, title, body, referenceId, targetUrl,
                additionalData, success, errorMessage, lane, category, actorUserId, presentation,
                /* deferredReason */ null);
    }

    /**
     * The one writer. {@code deferredReason} marks a Lane B row that the
     * recipient's own quiet hours held (PushPolicyService.Decision) so the
     * morning catch-up can count it; null everywhere else.
     */
    private void saveLogRow(String recipientEmail,
                            String notificationType,
                            String token,
                            String title,
                            String body,
                            String referenceId,
                            String targetUrl,
                            String additionalData,
                            boolean success,
                            String errorMessage,
                            Lane lane,
                            Category category,
                            String actorUserId,
                            Map<String, Object> presentation,
                            PushPolicyService.DeferReason deferredReason) {
        NotificationLog row = new NotificationLog(
                recipientEmail,
                notificationType,
                token,
                title,
                body,
                referenceId,
                targetUrl,
                additionalData,
                Instant.now(),
                success,
                errorMessage
        );
        if (lane != null) row.setLane(lane.name());
        if (category != null) row.setCategory(category.name());
        if (actorUserId != null) row.setActorUserId(actorUserId);
        if (deferredReason != null) row.setDeferredReason(deferredReason.name());
        row.setPresentationJson(presentation != null ? presentation : presentationOf(row));
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
        m.put("additionalData", n.getAdditionalData());
        m.put("timestamp", n.getTimestamp());
        m.put("readAt", n.getReadAt());
        m.put("type", n.getType());
        m.put("lane", n.getLane());
        m.put("category", n.getCategory());
        m.put("errorMessage", n.getErrorMessage());
        m.put("archivedAt", n.getArchivedAt());
        // Actor identity — present on rows dispatched after the
        // 2026-06-05 column add; null on legacy rows + system-originated
        // (hazard alerts, FEMA declarations) where there's no actor.
        // The FE inbox card uses this to wire the actor avatar tap to
        // /profile/:identifier via useProfileNav.
        m.put("actorUserId", n.getActorUserId());
        // Same contract GET /api/notifications returns, so a live row renders
        // identically to a fetched one.
        m.put("presentation", n.getPresentationJson());
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
                recipientFcmTokenOrNull, /* categoryOverride */ null,
                /* actorUserId */ null);
    }

    /**
     * Actor-aware overload — passes a stable {@code actorUserId} all
     * the way to the {@code NotificationLog} row + the STOMP banner
     * frame so the FE inbox + banner can deep-link the actor avatar
     * to {@code /profile/:identifier}. Use when the caller knows who
     * triggered the notification (post author, commenter, alert
     * initiator, check-in starter, follow actor). Null on system-
     * originated paths.
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
                                     String actorUserId) {
        deliverPresenceAware(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, /* categoryOverride */ null,
                actorUserId);
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
                recipientFcmTokenOrNull, groupIdForMuteCheck, /* categoryOverride */ null,
                /* actorUserId */ null);
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
        deliverPresenceAwareForGroup(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, groupIdForMuteCheck, categoryOverride,
                /* actorUserId */ null);
    }

    /**
     * Actor-aware, group-scoped variant. {@code actorUserId} flows
     * through the mute-aware suppression log row AND the eventual
     * {@link #deliverPresenceAware} call so the inbox row carries it
     * either way. See the actor-aware {@link #deliverPresenceAware}
     * javadoc for the FE consumer (NotificationCard avatar tap).
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
                                             String groupIdForMuteCheck,
                                             Category categoryOverride,
                                             String actorUserId) {
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
                            title, body, referenceId, targetUrl, additionalData,
                            /* success */ false,
                            /* error */ reason,
                            Lane.B, categoryOverride != null
                                    ? categoryOverride
                                    : mapTypeToCategory(notificationType),
                            actorUserId);
                } catch (Exception e) {
                    logger.warn("Suppression-path log write failed for {} group={}: {}",
                            recipientEmail, groupIdForMuteCheck, e.getMessage());
                }
                return;
            }
        }
        deliverPresenceAware(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, categoryOverride, actorUserId);
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
        deliverPresenceAware(recipientEmail, title, body, senderName, iconUrl,
                notificationType, referenceId, targetUrl, additionalData,
                recipientFcmTokenOrNull, categoryOverride, /* actorUserId */ null);
    }

    /**
     * Category + actor-aware delivery — the full-fat chokepoint every
     * other overload eventually funnels into. {@code actorUserId} is
     * persisted on the {@code NotificationLog} row and also rides the
     * STOMP banner frame so the FE inbox + banner can deep-link the
     * actor avatar to {@code /profile/:identifier} via
     * {@code useProfileNav}.
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
                                     Category categoryOverride,
                                     String actorUserId) {

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
        PushPolicyService.Decision decision = (catEnum != null)
                ? pushPolicyService.decide(recipientEmail, catEnum, /* severity */ null)
                : null;
        Lane lane = decision != null ? decision.lane() : null;
        if (lane == Lane.DROP) {
            // User opted out of this category entirely (or master-switched
            // off). No log, no push, no socket. Suppressed per policy.
            return;
        }

        // One presentation per send: the banner frame, the push payload's
        // deepLinkRoute and the inbox row all carry the same contract.
        Map<String, Object> presentation = presentationFor(notificationType, catEnum, title, body,
                referenceId, targetUrl, additionalData, actorUserId);
        String deepLinkRoute = deepLinkRouteOf(presentation, targetUrl);

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
                        lane != null ? lane.name() : null,
                        // Actor id rides the banner frame so the FE
                        // NotificationBanner avatar tap deep-links to
                        // /profile/:actorUserId via useProfileNav.
                        actorUserId,
                        presentation
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
                    title, body, referenceId, targetUrl, additionalData,
                    /* success */ false,
                    /* error */ LANE_B_SILENT_INBOX,
                    lane, catEnum, actorUserId, presentation, decision.deferredBy());
            return;
        }

        // Lane A (or no-policy fallback) → FCM whenever a token exists,
        // whether or not the user currently holds a WebSocket session.
        if (recipientFcmTokenOrNull == null || recipientFcmTokenOrNull.isEmpty()) {
            logger.info("No FCM token for user {}, logging only.", recipientEmail);
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl, additionalData,
                    false, "No token", lane, catEnum, actorUserId, presentation);
            return;
        }

        boolean success = false;
        String errorMessage = null;

        // ── P0-B: DO NOT MAKE A HIDDEN PERSON'S PHONE AUDIBLE ────────────────
        //
        // Every push here was built with sound("default") at HIGH / apns-10.
        // One volume, always audible. That is right for almost everything and
        // wrong for the one case where being audible IS the danger: a member
        // concealed during a lockdown or violent-threat event.
        //
        // Quiet hours cannot answer this — they are a schedule the user set for
        // sleeping, and a lockdown is at 1pm on a Wednesday. So the decision is
        // made from the hazard, via the template's reviewed
        // `concealmentSensitive` flag, against the RECIPIENT's own location.
        // Deciding it here rather than at each call site means it covers every
        // category — nudge, check-in request, reminders — instead of the one
        // path somebody remembered.
        boolean silent = shouldSendSilently(recipientEmail);

        try {
            Delivery delivery = deliveryFor(silent, notificationType);
            AndroidConfig androidConfig = delivery.android();

            // iOS APNs block (correct API: use ApnsConfig + Aps; put custom keys via Aps or ApnsConfig.putCustomData)
            ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                    .putHeader("apns-priority", delivery.apnsPriority());

            Aps.Builder apsBuilder = Aps.builder()
                    .setMutableContent(true);      // enables notification service extension (if you have one)
            if (delivery.iosSound() != null) {
                apsBuilder.setSound(delivery.iosSound());
            }

            // Custom metadata inside "aps"
            apsBuilder.putCustomData("notificationType", safe(notificationType));
            apsBuilder.putCustomData("referenceId", safe(referenceId));
            apsBuilder.putCustomData("targetUrl", safe(targetUrl));
            apsBuilder.putCustomData("additionalData", safe(additionalData));
            apsBuilder.putCustomData("title", safe(title));
            apsBuilder.putCustomData("body", safe(body));
            apsBuilder.putCustomData("channelId", safe(channelId));
            apsBuilder.putCustomData("category", safe(category));
            // Canonical route for the native tap handler (targetUrl stays for
            // app builds that predate the resolver).
            apsBuilder.putCustomData("deepLinkRoute", safe(deepLinkRoute));
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
                    .putData("deepLinkRoute", safe(deepLinkRoute))
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
                    title, body, referenceId, targetUrl, additionalData,
                    success, errorMessage, lane, catEnum, actorUserId, presentation);
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
        sendNotification(title, body, sender, iconUrl, tokens, notificationType,
                referenceId, targetUrl, additionalData, recipientEmail,
                /* actorUserId */ null);
    }

    /**
     * Actor-aware overload of the legacy multi-token sender. Threads
     * {@code actorUserId} onto every {@code NotificationLog} row written
     * by the per-token loop so the FE inbox can deep-link the actor
     * avatar tap regardless of which dispatch path produced the row.
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
                                 String recipientEmail,
                                 String actorUserId) {
        // Apply policy at the top — same gating as deliverPresenceAware.
        // sendNotification is the legacy multi-token wrapper but the
        // policy still applies: a user who muted earthquakes shouldn't
        // get an FCM through the legacy path either.
        Category catEnum = mapTypeToCategory(notificationType);
        PushPolicyService.Decision decision = (catEnum != null && recipientEmail != null)
                ? pushPolicyService.decide(recipientEmail, catEnum, /* severity */ null)
                : null;
        Lane lane = decision != null ? decision.lane() : null;
        if (lane == Lane.DROP) return;
        // Lane B = silent inbox: skip FCM, write log row.
        if (lane == Lane.B) {
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl, additionalData,
                    false, LANE_B_SILENT_INBOX,
                    lane, catEnum, actorUserId, /* presentation */ null, decision.deferredBy());
            return;
        }
        // Lane C = ephemeral: this path is offline-FCM only, so Lane C
        // here is a no-op (banner happens elsewhere).
        if (lane == Lane.C) return;

        if (tokens == null || tokens.isEmpty()) {
            logger.info("No tokens for {}, skipping FCM.", recipientEmail);
            saveLogRow(recipientEmail, notificationType, null,
                    title, body, referenceId, targetUrl, additionalData,
                    false, "No tokens provided",
                    lane, catEnum, actorUserId);
            return;
        }

        String channelId = channelForType(notificationType);
        String category = categoryForType(notificationType);
        // App-icon badge for the recipient — identical for every device
        // token, so resolve it once outside the per-token loop.
        Integer badge = unreadBadgeFor(recipientEmail);
        // P0-B applies here too: this path sends "new member" pushes, and a
        // phone in a lockdown must not chime for those either. Same check as
        // deliverPresenceAware, decided once for all of the person's devices.
        Delivery delivery = deliveryFor(shouldSendSilently(recipientEmail), notificationType);

        for (String token : tokens) {
            boolean success = false;
            String errorMessage = null;

            try {
                AndroidConfig androidConfig = delivery.android();

                ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                        .putHeader("apns-priority", delivery.apnsPriority());

                Aps.Builder apsBuilder = Aps.builder()
                        .setMutableContent(true);
                if (delivery.iosSound() != null) {
                    apsBuilder.setSound(delivery.iosSound());
                }

                apsBuilder.putCustomData("notificationType", safe(notificationType));
                apsBuilder.putCustomData("referenceId", safe(referenceId));
                apsBuilder.putCustomData("targetUrl", safe(targetUrl));
                apsBuilder.putCustomData("additionalData", safe(additionalData));
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
                        title, body, referenceId, targetUrl, additionalData,
                        success, errorMessage, lane, catEnum, actorUserId);
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

        // Resolve the initiator's stable userId once outside the loop so
        // every fan-out row carries the same actor identity. The FE
        // NotificationCard uses this to deep-link the actor avatar to
        // /profile/:actorUserId via useProfileNav. Null when the initiator
        // can't be resolved (system-fired alert, deleted user); the FE
        // falls back to the title-derived initial.
        String actorUserId = initiatedByEmail == null ? null :
                userInfoRepo.findByUserEmailIgnoreCase(initiatedByEmail)
                        .map(UserInfo::getId)
                        .orElse(null);

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
                    alertCategory,
                    actorUserId
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

        // Resolve the initiator's stable userId so the FE NotificationCard
        // avatar tap deep-links to /profile/:actorUserId. One lookup per
        // fan-out, not per recipient.
        String actorUserId = initiatedByEmail == null ? null :
                userInfoRepo.findByUserEmailIgnoreCase(initiatedByEmail)
                        .map(UserInfo::getId)
                        .orElse(null);

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
                    Category.CHECK_IN_REQUEST,
                    actorUserId
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
        logSocketDelivery(recipientEmail, type, title, body, referenceId, targetUrl,
                lane, category, /* actorUserId */ null);
    }

    /**
     * Lane + actor-aware overload. {@code actorUserId} flows through
     * to {@code NotificationLog} so the FE inbox card can deep-link
     * the actor avatar straight to {@code /profile/:identifier}.
     */
    public void logSocketDelivery(String recipientEmail,
                                  String type,
                                  String title,
                                  String body,
                                  String referenceId,
                                  String targetUrl,
                                  Lane lane,
                                  Category category,
                                  String actorUserId) {
        saveLogRow(recipientEmail, type,
                /* token */ null,
                title, body, referenceId, targetUrl,
                /* success */ true,
                /* error */ null,
                lane, category, actorUserId);
    }

    /**
     * Writes an inbox row and nothing else: no STOMP banner, no FCM, no APNs.
     * The token passed to the row is null by construction and nothing on this
     * path references {@code FirebaseMessaging}, so the row cannot become a
     * push whatever lane it is given. The inbox's own STOMP {@code created}
     * event still fires so an open inbox shows the row live.
     *
     * <p>Policy is the caller's job — {@code TokenNotifier} checks
     * {@link PushPolicyService#evaluate} first so a user who switched the
     * inbox off gets no row.</p>
     */
    public void logInboxOnly(String recipientEmail,
                             String type,
                             String title,
                             String body,
                             String referenceId,
                             String targetUrl,
                             String additionalData,
                             Category category) {
        saveLogRow(recipientEmail, type,
                /* token */ null,
                title, body, referenceId, targetUrl, additionalData,
                /* success */ true,
                /* error */ null,
                Lane.B, category, /* actorUserId */ null);
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
     * in {@link #mapTypeToCategory} because the CALLER owns the lane:
     * {@code AlertDispatchService.pushSevereAlert} evaluates each recipient
     * against the hazard categories and hands only Lane A recipients to this
     * method. Its Lane B recipients (quiet hours, rate cap, push switched off)
     * go to {@link #logHazardAlertInboxOnly} instead — the same row, no FCM.
     * The APNs payload carries {@code interruption-level: time-sensitive}
     * so a Lane A send still breaks through Focus modes (see
     * {@link #applyIosLockScreenAffordances}).</p>
     */
    public record HazardBatchResult(int attempted, int delivered, int failed, String lastError) {}

    public HazardBatchResult sendHazardAlertBatch(List<UserInfo> recipients,
                                                  String title,
                                                  String body,
                                                  String referenceId,
                                                  String targetUrl) {
        return sendHazardAlertBatch(recipients, title, body, referenceId, targetUrl, null);
    }

    public HazardBatchResult sendHazardAlertBatch(List<UserInfo> recipients,
                                                  String title,
                                                  String body,
                                                  String referenceId,
                                                  String targetUrl,
                                                  String additionalData) {
        return sendHazardAlertBatch(recipients, title, body, referenceId, targetUrl,
                additionalData, /* concealmentSensitive */ false);
    }

    /**
     * @param concealmentSensitive the alert's reviewed template marks it a
     *        lockdown / violent-threat hazard. Such an alert keeps the plain
     *        system sound rather than SitPrep's longer alert tone (see
     *        {@link #hazardSoundFor}).
     */
    public HazardBatchResult sendHazardAlertBatch(List<UserInfo> recipients,
                                                  String title,
                                                  String body,
                                                  String referenceId,
                                                  String targetUrl,
                                                  String additionalData,
                                                  boolean concealmentSensitive) {
        if (recipients == null || recipients.isEmpty()) {
            return new HazardBatchResult(0, 0, 0, null);
        }

        final String type = TYPE_HAZARD_ALERT;
        // Identical for every recipient (no actor, no group), so built once.
        Map<String, Object> presentation = hazardPresentation(title, body,
                referenceId, targetUrl, additionalData);
        String deepLinkRoute = deepLinkRouteOf(presentation, targetUrl);
        List<String> batchTokens = new ArrayList<>();
        List<UserInfo> batchUsers = new ArrayList<>();
        int attempted = 0;
        int delivered = 0;
        int failed = 0;
        String lastError = null;

        for (UserInfo u : recipients) {
            String email = u != null ? u.getUserEmail() : null;
            if (email == null) continue;
            attempted++;

            // Foregrounded client → best-effort in-app STOMP banner.
            // Unlike before, this does NOT skip FCM: the recipient still
            // goes into the multicast batch below so a stale/phantom
            // WebSocket session can't swallow a life-safety weather
            // alert. The FE de-dupes the socket banner vs the FCM frame.
            if (presenceService.isUserOnline(email)) {
                try {
                    // hazard_alert is system-originated (NWS / USGS),
                    // not actor-originated — actorUserId is null.
                    webSocketMessageSender.sendInAppNotification(new NotificationPayload(
                            email, title, body, null, type, targetUrl, referenceId,
                            Instant.now(), null, /* actorUserId */ null, presentation));
                } catch (Exception e) {
                    logger.warn("Hazard-alert socket frame failed for {}: {}", email, e.getMessage());
                }
            }

            String token = u.getFcmtoken();
            if (token == null || token.isEmpty()) {
                saveLogRow(email, type, null, title, body, referenceId, targetUrl,
                        additionalData, false, "No token", null, null, null, presentation);
                failed++;
                lastError = "No token";
                continue;
            }
            batchTokens.add(token);
            batchUsers.add(u);
        }

        for (BatchRange range : hazardBatchRanges(batchTokens.size())) {
            int start = range.start();
            int end = range.end();
            List<String> tokens = batchTokens.subList(start, end);
            List<UserInfo> users = batchUsers.subList(start, end);
            MulticastMessage multicast = MulticastMessage.builder()
                    .addAllTokens(tokens)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putData("notificationType", type)
                    .putData("referenceId", safe(referenceId))
                    .putData("targetUrl", safe(targetUrl))
                    .putData("title", safe(title))
                    .putData("body", safe(body))
                    .putData("additionalData", safe(additionalData))
                    .putData("channelId", channelForType(type))
                    .putData("category", categoryForType(type))
                    .putData("deepLinkRoute", safe(deepLinkRoute))
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setNotification(AndroidNotification.builder().setSound("default").build())
                            .build())
                    .setApnsConfig(buildHazardApns(type, referenceId, title, body, targetUrl, additionalData, deepLinkRoute,
                            concealmentSensitive))
                    .build();

            try {
                BatchResponse resp = FirebaseMessaging.getInstance().sendEachForMulticast(multicast);
                List<SendResponse> responses = resp.getResponses();
                for (int i = 0; i < users.size(); i++) {
                    UserInfo u = users.get(i);
                    String token = tokens.get(i);
                    SendResponse response = i < responses.size() ? responses.get(i) : null;
                    if (response != null && response.isSuccessful()) {
                        delivered++;
                        saveLogRow(u.getUserEmail(), type, token, title, body, referenceId, targetUrl,
                                additionalData, true, null, null, null, null, presentation);
                    } else {
                        failed++;
                        FirebaseMessagingException ex = response == null ? null : response.getException();
                        String err = ex != null ? ex.getMessage() : "Unknown FCM error";
                        lastError = err;
                        saveLogRow(u.getUserEmail(), type, token, title, body, referenceId, targetUrl,
                                additionalData, false, err, null, null, null, presentation);
                        handleFcmDeliveryError(ex, u.getUserEmail(), token);
                    }
                }
                logger.info("📣 Hazard-alert multicast '{}' chunk {}-{}: {} delivered, {} failed",
                        referenceId, start, end, resp.getSuccessCount(), resp.getFailureCount());
            } catch (Exception e) {
                lastError = e.getMessage();
                failed += users.size();
                logger.error("❌ Hazard-alert multicast send failed for {} chunk {}-{}: {}",
                        referenceId, start, end, e.getMessage(), e);
                for (int i = 0; i < users.size(); i++) {
                    saveLogRow(users.get(i).getUserEmail(), type, tokens.get(i),
                            title, body, referenceId, targetUrl, additionalData,
                            false, e.getMessage(), null, null, null, presentation);
                }
            }
        }
        return new HazardBatchResult(attempted, delivered, failed, lastError);
    }

    /**
     * Lane B for a hazard alert: the inbox row a Lane A recipient would get,
     * and no FCM, no APNs, no in-app banner.
     *
     * <p><b>Why this exists.</b> Quiet hours and the rate cap demote a
     * non-critical hazard push (a Fire Warning, a warning NWS rated below
     * Severe) from Lane A to Lane B. {@code pushSevereAlert} used to keep only
     * Lane A, so a Lane B recipient got neither the push nor an inbox row —
     * the warning simply did not exist for them. Every other sender already
     * honoured Lane B with a row ({@link #deliverPresenceAware}'s Lane B
     * branch); this is that branch for the batched hazard path.</p>
     *
     * <p><b>Same row as the push.</b> Type {@code hazard_alert}, the same
     * title, body, referenceId, targetUrl, additionalData and the same
     * presentation ({@link #hazardPresentation}, built exactly as
     * {@link #sendHazardAlertBatch} builds it), so the inbox renders and
     * deep-links it identically. Only the delivery markers differ, and they
     * match the Lane B branch: lane {@code B}, the policy category, null token,
     * {@code success=false}, error {@code "Lane B (silent inbox)"}.</p>
     *
     * <p>Policy is the caller's job — pass only recipients
     * {@link PushPolicyService#evaluate} put in Lane B. Nothing here references
     * {@code FirebaseMessaging}, so a row written here cannot become a push.</p>
     *
     * @return the number of rows written
     */
    public int logHazardAlertInboxOnly(List<UserInfo> recipients,
                                       String title,
                                       String body,
                                       String referenceId,
                                       String targetUrl,
                                       String additionalData,
                                       Category category) {
        return logHazardAlertInboxOnly(recipients, title, body, referenceId, targetUrl,
                additionalData, category, Set.of());
    }

    /**
     * @param quietHoursDeferred lower-cased emails whose Lane B came from their
     *        own quiet hours ({@link PushPolicyService.Decision#deferredByQuietHours});
     *        their rows are marked for the morning catch-up.
     */
    public int logHazardAlertInboxOnly(List<UserInfo> recipients,
                                       String title,
                                       String body,
                                       String referenceId,
                                       String targetUrl,
                                       String additionalData,
                                       Category category,
                                       Set<String> quietHoursDeferred) {
        if (recipients == null || recipients.isEmpty()) return 0;
        Map<String, Object> presentation = hazardPresentation(title, body,
                referenceId, targetUrl, additionalData);
        int written = 0;
        for (UserInfo u : recipients) {
            String email = u != null ? u.getUserEmail() : null;
            if (email == null || email.isBlank()) continue;
            try {
                boolean quiet = quietHoursDeferred != null
                        && quietHoursDeferred.contains(email.trim().toLowerCase(java.util.Locale.ROOT));
                saveLogRow(email, TYPE_HAZARD_ALERT, /* token */ null,
                        title, body, referenceId, targetUrl, additionalData,
                        /* success */ false,
                        /* error */ LANE_B_SILENT_INBOX,
                        Lane.B, category, /* actorUserId */ null, presentation,
                        quiet ? PushPolicyService.DeferReason.QUIET_HOURS : null);
                written++;
            } catch (Exception e) {
                // One recipient's failed write must not cost the others theirs.
                logger.warn("Hazard-alert inbox row failed for {} ({}): {}",
                        email, referenceId, e.getMessage());
            }
        }
        return written;
    }

    /** The hazard presentation — one builder for the push and the inbox-only row. */
    private Map<String, Object> hazardPresentation(String title, String body, String referenceId,
                                                   String targetUrl, String additionalData) {
        // No actor, no group, no category: a hazard row's presentation is a
        // function of the alert alone, so a Lane A and a Lane B row match.
        return presentationFor(TYPE_HAZARD_ALERT, null, title, body,
                referenceId, targetUrl, additionalData, null);
    }

    static List<BatchRange> hazardBatchRanges(int recipientCount) {
        List<BatchRange> ranges = new ArrayList<>();
        for (int start = 0; start < Math.max(0, recipientCount); start += 500) {
            ranges.add(new BatchRange(start, Math.min(start + 500, recipientCount)));
        }
        return ranges;
    }

    record BatchRange(int start, int end) {}

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
                                       String targetUrl,
                                       String additionalData,
                                       String deepLinkRoute,
                                       boolean concealmentSensitive) {
        ApnsConfig.Builder apnsBuilder = ApnsConfig.builder()
                .putHeader("apns-priority", "10");
        Aps.Builder apsBuilder = Aps.builder()
                .setMutableContent(true)
                .setSound(hazardSoundFor(type, concealmentSensitive));
        // No aps.badge here: a hazard alert ships as one MulticastMessage
        // with a single shared payload, so a per-recipient unread count
        // can't be expressed. The badge corrects on the recipient's next
        // single-send push (deliverPresenceAware) or when they open the app.
        apsBuilder.putCustomData("notificationType", safe(type));
        apsBuilder.putCustomData("referenceId", safe(referenceId));
        apsBuilder.putCustomData("targetUrl", safe(targetUrl));
        apsBuilder.putCustomData("title", safe(title));
        apsBuilder.putCustomData("body", safe(body));
        apsBuilder.putCustomData("additionalData", safe(additionalData));
        apsBuilder.putCustomData("channelId", safe(channelForType(type)));
        apsBuilder.putCustomData("category", safe(categoryForType(type)));
        apsBuilder.putCustomData("deepLinkRoute", safe(deepLinkRoute));
        applyIosLockScreenAffordances(apsBuilder, type, referenceId);
        apnsBuilder.setAps(apsBuilder.build());
        return apnsBuilder.build();
    }

    // ---------------------- helpers ----------------------

    /** Presentation for a row that has not been persisted yet (send-time). */
    private Map<String, Object> presentationFor(String type, Category category, String title, String body,
                                                String referenceId, String targetUrl,
                                                String additionalData, String actorUserId) {
        if (presentationBuilder == null) return null;
        NotificationLog facts = new NotificationLog(null, type, null, title, body, referenceId,
                targetUrl, additionalData, null, false, null);
        if (category != null) facts.setCategory(category.name());
        facts.setActorUserId(actorUserId);
        return presentationOf(facts);
    }

    private Map<String, Object> presentationOf(NotificationLog row) {
        if (presentationBuilder == null) return null;
        try {
            return presentationBuilder.buildJson(row);
        } catch (Exception e) {
            // A presentation must never cost a delivery or an inbox row.
            logger.warn("Presentation build failed for type={}: {}", row.getType(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static String deepLinkRouteOf(Map<String, Object> presentation, String targetUrl) {
        if (presentation != null && presentation.get("deepLink") instanceof Map<?, ?> link
                && link.get("route") instanceof String route && !route.isBlank()) {
            return route;
        }
        return targetUrl;
    }

    /**
     * Should this recipient's device stay quiet?
     *
     * <p>True only when a live alert covering their last known position is
     * classified {@code concealmentSensitive} by its reviewed template.
     *
     * <p><b>Fails toward noise.</b> No location, no snapshot, no classification,
     * any exception — all mean "send normally". Silencing a reminder somebody
     * needed is its own harm, so the refusal to decide defaults to the ordinary
     * behaviour rather than to silence.
     */
    boolean shouldSendSilently(String recipientEmail) {
        if (concealmentSafetyService == null || recipientEmail == null || recipientEmail.isBlank()) {
            return false;
        }
        try {
            return userInfoRepo.findByUserEmailIgnoreCase(recipientEmail)
                    .map(concealmentSafetyService::isConcealmentSensitiveFor)
                    .orElse(false);
        } catch (RuntimeException ex) {
            return false;
        }
    }

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
     * Map notification types into Android channel IDs — read from the
     * {@link NotificationEventType} taxonomy. Keep the registry in sync with
     * native channel creation if a wrapper ever adds one.
     */
    private String channelForType(String type) {
        return NotificationEventType.forType(type).androidChannel();
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
     * (3) set the matching id on the event in {@link NotificationEventType}.</p>
     */
    private String categoryForType(String type) {
        return NotificationEventType.forType(type).iosCategory();
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
    /**
     * Which bundled sound an audible iOS push plays.
     *
     * <p>Safety pushes get SitPrep's own sounds so a user can tell "this is a
     * safety alert" from across the room without looking: the alert tone for
     * the time-sensitive types, the check-in tone for a check-in request.
     * Everything else stays the system default. The files ship in the iOS App
     * bundle (ios/App/App/sounds); a build without them plays the default, so
     * deploying this ahead of the app is harmless. The concealment path never
     * reaches here: silent means no sound key at all.
     */
    /**
     * How one person's push is delivered: audible, or silent because they may
     * be hiding (P0-B, {@link #shouldSendSilently}). Every per-person send
     * path builds its Android priority, APNs priority and iOS sound from
     * here, so a path cannot be audible where another is silent.
     *
     * <p>Silent = Android NORMAL with no sound or vibration, APNs priority 5
     * (considerate delivery; 10 wakes the device), and NO {@code aps.sound}
     * key at all ({@code iosSound} null). Omitting the key is what makes APNs
     * deliver silently; an empty string is not the same thing.
     */
    static Delivery deliveryFor(boolean silent, String notificationType) {
        AndroidConfig android = AndroidConfig.builder()
                .setPriority(silent ? AndroidConfig.Priority.NORMAL : AndroidConfig.Priority.HIGH)
                .setNotification(silent
                        ? AndroidNotification.builder()
                                .setDefaultSound(false)
                                .setDefaultVibrateTimings(false)
                                .build()
                        : AndroidNotification.builder()
                                .setSound("default")
                                .build())
                .build();
        return new Delivery(android, silent ? "5" : "10", silent ? null : iosSoundFor(notificationType));
    }

    record Delivery(AndroidConfig android, String apnsPriority, String iosSound) {}

    /**
     * The sound for a hazard multicast. A lockdown / violent-threat warning
     * (template {@code concealmentSensitive}) is SILENT BUT HAPTIC (owner
     * ruling 2026-10-04): it plays {@code sitprep-silent.caf}, half a second
     * of digital silence. APNs has no vibrate-only flag and a push with no
     * sound key does not vibrate, so a silent sound is what lets iOS fire
     * the notification haptic while nothing is heard. The multicast is one
     * payload for everyone, so this applies to every recipient.
     */
    static String hazardSoundFor(String type, boolean concealmentSensitive) {
        return concealmentSensitive ? SOUND_SILENT : iosSoundFor(type);
    }

    // SitPrep sonic identity v1 (FE docs/epics/haptics_and_sound/SOUND_CREATION_BRIEF.md).
    // File names must match ios/App/App/*.caf in the FE repo; a missing file
    // plays the system default.
    static final String SOUND_NOTE = "sitprep-note.caf";
    static final String SOUND_CHECKIN = "sitprep-checkin.caf";
    static final String SOUND_HOUSEHOLD = "sitprep-household.caf";
    static final String SOUND_HAZARD = "sitprep-hazard.caf";
    static final String SOUND_SILENT = "sitprep-silent.caf";

    static String iosSoundFor(String notificationType) {
        if ("check_in_request".equals(notificationType)) return SOUND_CHECKIN;
        // The sky, not my family: brighter and faster than the household tone.
        if ("hazard_alert".equals(notificationType)) return SOUND_HAZARD;
        if (isTimeSensitiveTypeStatic(notificationType)) return SOUND_HOUSEHOLD;
        // Everything routine: recognizably SitPrep, quiet, easy to ignore.
        return SOUND_NOTE;
    }

    private boolean isTimeSensitiveType(String type) {
        return isTimeSensitiveTypeStatic(type);
    }

    private static boolean isTimeSensitiveTypeStatic(String type) {
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
