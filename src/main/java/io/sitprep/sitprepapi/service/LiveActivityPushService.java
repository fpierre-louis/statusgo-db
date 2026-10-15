package io.sitprep.sitprepapi.service;

import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import io.sitprep.sitprepapi.domain.LiveActivityToken;
import io.sitprep.sitprepapi.repo.LiveActivityTokenRepo;
import io.sitprep.sitprepapi.service.LiveActivityStateBuilder.Situation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import io.sitprep.sitprepapi.domain.Group;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Sends ActivityKit pushes for the situation card through FCM HTTP v1
 * (live_activities GAMEPLAN §3.1, decision D4).
 *
 * <h4>What a hook may call</h4>
 * {@link #situationChanged}, {@link #situationStarted}, {@link #situationEnded}.
 * Each returns at once and NEVER throws: the work (DB reads, FCM calls) runs on
 * this service's own thread, so a status write, an alert toggle or a decay
 * sweep cannot be slowed or failed by a Live Activity. Callers invoke them
 * after commit.
 *
 * <h4>What a push is</h4>
 * {@code message.token} = the device's FCM token, {@code apns.live_activity_token}
 * = the activity's token, and three explicit headers ({@code apns-push-type:
 * liveactivity}, {@code apns-topic: <bundle>.push-type.liveactivity},
 * {@code apns-priority}). FCM's samples set only the priority; setting all
 * three removes the guesswork. The {@code aps} body carries {@code timestamp},
 * {@code event}, {@code content-state}, {@code stale-date} (update) or
 * {@code dismissal-date} (end), {@code relevance-score}, and an {@code alert}
 * only when it earns one.
 *
 * <h4>Priority and alerts (§3.4)</h4>
 * <ul>
 *   <li>Priority 5 by default: it does not draw on Apple's budget.</li>
 *   <li>Priority 10 + alert when help + injured RISES over what this service
 *       last sent for the group. The first push after a token registers sets
 *       the baseline and never alerts.</li>
 *   <li>An end is priority 10; it alerts only viewers who did not end it
 *       themselves (your own tap needs no chime).</li>
 *   <li>No alert at all while the viewer's sound policy is suppressed (a
 *       lockdown or concealment hazard at their location): an alert lights the
 *       screen and plays a sound. The same answer as
 *       {@code GET /api/me/sound-policy}.</li>
 * </ul>
 *
 * <p>These are UI for the person who started the check-in, not notifications:
 * they skip {@link PushPolicyService} lanes and write no inbox row. The push,
 * the inbox and the in-app banner stay the baseline; a Live Activity is never
 * the only channel.</p>
 */
@Service
public class LiveActivityPushService {

    private static final Logger log = LoggerFactory.getLogger(LiveActivityPushService.class);

    public static final String DEFAULT_APNS_TOPIC = "app.sitprep.ios.push-type.liveactivity";
    /** After this with no update, the card says "As of 9:31. Open SitPrep for the latest." */
    public static final Duration STALE_AFTER = Duration.ofMinutes(10);
    /** How long an ended card stays on the lock screen (HIG: 15–30 min). */
    public static final Duration DISMISS_AFTER = Duration.ofMinutes(15);

    public static final int PRIORITY_LOW = 5;
    public static final int PRIORITY_HIGH = 10;

    /** Sends one message; returns FCM's message id. */
    @FunctionalInterface
    public interface Transport {
        String send(Message message) throws FirebaseMessagingException;
    }

    /** What this service last sent for a group, so a rise can be told from a repeat. */
    record Sent(boolean plan, Integer pickup, int urgent) {}

    private final LiveActivityTokenRepo tokens;
    private final Supplier<LiveActivityStateBuilder> builder;
    private final Predicate<String> silenced;
    private final Transport transport;
    private final Executor executor;
    private final LiveActivityUpdateCoalescer coalescer;
    private final String apnsTopic;
    private final ScheduledExecutorService ownedExecutor;
    private final Map<String, Sent> lastSent = new ConcurrentHashMap<>();

    @Autowired
    public LiveActivityPushService(LiveActivityTokenRepo tokens,
                                   ObjectProvider<LiveActivityStateBuilder> builder,
                                   ObjectProvider<NotificationService> notifications,
                                   @Value("${app.liveActivity.coalesceSeconds:10}") long coalesceSeconds,
                                   @Value("${app.liveActivity.apnsTopic:" + DEFAULT_APNS_TOPIC + "}") String apnsTopic) {
        this(tokens, builder::getObject, email -> soundSuppressed(notifications, email),
                message -> FirebaseMessaging.getInstance().send(message),
                newExecutor(), Duration.ofSeconds(Math.max(1, coalesceSeconds)), apnsTopic);
    }

    private LiveActivityPushService(LiveActivityTokenRepo tokens,
                                    Supplier<LiveActivityStateBuilder> builder,
                                    Predicate<String> silenced,
                                    Transport transport,
                                    ScheduledExecutorService executor,
                                    Duration window,
                                    String apnsTopic) {
        this(tokens, builder, silenced, transport,
                (task, delay) -> executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS),
                executor, window, apnsTopic, executor);
    }

    /** The seams, for tests: a captured scheduler and a synchronous executor. */
    LiveActivityPushService(LiveActivityTokenRepo tokens,
                            Supplier<LiveActivityStateBuilder> builder,
                            Predicate<String> silenced,
                            Transport transport,
                            LiveActivityUpdateCoalescer.Scheduler scheduler,
                            Executor executor,
                            Duration window,
                            String apnsTopic,
                            ScheduledExecutorService ownedExecutor) {
        this.tokens = tokens;
        this.builder = builder;
        this.silenced = silenced;
        this.transport = transport;
        this.executor = executor;
        this.apnsTopic = apnsTopic == null || apnsTopic.isBlank() ? DEFAULT_APNS_TOPIC : apnsTopic;
        this.ownedExecutor = ownedExecutor;
        this.coalescer = new LiveActivityUpdateCoalescer(scheduler, window, this::flushKey);
    }

    // ── P2 collaborators (2026-10-14). Setter-injected and optional, so the
    // test seam constructor stays as it is; absent means push-to-start is off.
    private io.sitprep.sitprepapi.repo.GroupRepo groupRepo;
    private PushPolicyService pushPolicy;

    @Autowired(required = false)
    void setCheckInRequestCollaborators(io.sitprep.sitprepapi.repo.GroupRepo groupRepo,
                                        PushPolicyService pushPolicy) {
        this.groupRepo = groupRepo;
        this.pushPolicy = pushPolicy;
    }

    private static ScheduledExecutorService newExecutor() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "live-activity-push");
            t.setDaemon(true);
            return t;
        });
        ex.setRemoveOnCancelPolicy(true);
        return ex;
    }

    private static boolean soundSuppressed(ObjectProvider<NotificationService> notifications, String email) {
        NotificationService n = notifications.getIfAvailable();
        return n != null && n.shouldSendSilently(email);
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) ownedExecutor.shutdownNow();
    }

    LiveActivityUpdateCoalescer coalescer() {
        return coalescer;
    }

    // ---------------------------------------------------------------------
    // Hooks (return at once, never throw)
    // ---------------------------------------------------------------------

    /** The situation's numbers, plan flag or end time may have moved: one coalesced update. */
    public void situationChanged(String groupId) {
        if (groupId == null || groupId.isBlank()) return;
        try {
            coalescer.queue(LiveActivityUpdateCoalescer.key(LiveActivityToken.KIND_SITUATION, groupId));
        } catch (RuntimeException e) {
            log.warn("LiveActivity: could not queue an update for group {}: {}", groupId, e.getMessage());
        }
    }

    /**
     * A new check-in started. Tokens still live for this group belong to an
     * earlier check-in's card; they are retired so the new check-in never
     * pushes onto an old card. The device registers the new card's token
     * after the start returns.
     */
    public void situationStarted(String groupId) {
        situationStarted(groupId, null);
    }

    /**
     * A new check-in started, by {@code actorEmail} (null: unknown). Besides
     * retiring the earlier check-in's tokens, every OTHER member gets the
     * "You were asked to check in" card by push-to-start (GAMEPLAN §4 P2).
     */
    public void situationStarted(String groupId, String actorEmail) {
        if (groupId == null || groupId.isBlank()) return;
        retireSituationTokens(groupId);
        run("start check-in requests for " + groupId, () -> sendCheckInRequestStarts(groupId, actorEmail));
    }

    private void retireSituationTokens(String groupId) {
        run("retire tokens for " + groupId, () -> {
            lastSent.remove(groupId);
            int n = tokens.endLiveUpdateTokens(LiveActivityToken.KIND_SITUATION, groupId, Instant.now());
            if (n > 0) log.info("LiveActivity: a new check-in in {} retired {} earlier token(s)", groupId, n);
        });
    }

    /**
     * The check-in ended. Each following card gets its final state and is
     * dismissed {@link #DISMISS_AFTER} later; the tokens are then retired.
     *
     * @param startedAt the ended check-in's start, captured before the clear
     * @param reason    {@link LiveActivityStateBuilder#REASON_MANUAL}, {@link LiveActivityStateBuilder#REASON_DECAY}
     *                  or {@link LiveActivityStateBuilder#REASON_DELETED}
     * @param actorEmail who ended it (null for the sweep); only ever rendered as "you"
     */
    public void situationEnded(String groupId, Instant startedAt, String reason, String actorEmail) {
        endSituation(groupId, startedAt, reason, actorEmail, null);
    }

    /**
     * The check-in ended because its group is being deleted (BE-09, B5 review
     * 2026-10-14). {@code ended} was built inside the delete, before the row
     * went ({@link LiveActivityStateBuilder#endedSituation}): by the time this
     * runs the group is gone, the builder finds nothing, and without the
     * snapshot each card would only be retired, never ended (it would go stale
     * and stay on the lock screen until the app was opened). Register it
     * before the ordinary end hook: the first end retires the tokens, so the
     * second finds none and does nothing.
     */
    public void situationEnded(Situation ended, String reason, String actorEmail) {
        if (ended == null) return;
        endSituation(ended.groupId(), ended.startedAt(), reason, actorEmail, ended);
    }

    /**
     * Both end paths, one housekeeping: the pending coalesced update is
     * cancelled here, and {@code lastSent} is cleared by
     * {@link #sendSituationEnd} on this service's thread. {@code snapshot}
     * null reads the ended situation at send time.
     */
    private void endSituation(String groupId, Instant startedAt, String reason, String actorEmail,
                              Situation snapshot) {
        if (groupId == null || groupId.isBlank()) return;
        try {
            coalescer.cancel(LiveActivityUpdateCoalescer.key(LiveActivityToken.KIND_SITUATION, groupId));
        } catch (RuntimeException ignored) { }
        run("end " + groupId, () -> sendSituationEnd(groupId, startedAt, reason, actorEmail, snapshot));
        run("end check-in requests for " + groupId, () -> endCheckInRequests(
                tokens.findLiveUpdateTokens(LiveActivityToken.KIND_CHECKIN_REQUEST, groupId),
                CHECKIN_PHASE_ENDED, null, "The check-in ended."));
    }

    /**
     * {@code email} just wrote their status (any surface: the app, the
     * notification, the Watch, the card's own button). Their "asked to check
     * in" cards end as answered, so a card never keeps asking someone who
     * already said how they are.
     */
    public void memberAnswered(String email, String status) {
        if (email == null || email.isBlank()) return;
        String e = email.trim().toLowerCase(java.util.Locale.ROOT);
        run("end check-in requests for " + e, () -> endCheckInRequests(
                tokens.findLiveUpdateTokensForUser(LiveActivityToken.KIND_CHECKIN_REQUEST, e),
                CHECKIN_PHASE_SENT, status, null));
    }

    private void run(String what, Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("LiveActivity: {} failed: {}", what, e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("LiveActivity: could not run {}: {}", what, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Work (on this service's thread)
    // ---------------------------------------------------------------------

    private void flushKey(String key) {
        int colon = key.indexOf(':');
        String kind = colon < 0 ? key : key.substring(0, colon);
        String ref = colon < 0 ? null : key.substring(colon + 1);
        if (LiveActivityToken.KIND_SITUATION.equals(kind)) flushSituation(ref);
    }

    /** One update to every card following this group's running check-in. */
    void flushSituation(String groupId) {
        if (!tokens.existsLiveUpdateToken(LiveActivityToken.KIND_SITUATION, groupId)) return;
        Optional<Situation> found = builder.get().currentSituation(groupId);
        // Not Active any more: the end path has (or will have) the last word.
        if (found.isEmpty()) return;
        Situation s = found.get();
        List<LiveActivityToken> live = tokens.findLiveUpdateTokens(LiveActivityToken.KIND_SITUATION, groupId);
        if (live.isEmpty()) return;

        Instant now = Instant.now();
        Sent prev = lastSent.get(groupId);
        int urgent = s.urgent();
        boolean rose = prev != null && urgent > prev.urgent();
        Map<String, Object> state = LiveActivityStateBuilder.contentState(
                LiveActivityStateBuilder.PHASE_ACTIVE, s, now, null);

        for (LiveActivityToken t : live) {
            boolean alert = rose && !isSilenced(t.getUserEmail());
            Map<String, Object> aps = aps("update", state, now, now.plus(STALE_AFTER), null, urgent,
                    alert ? alert(LiveActivityStateBuilder.alertTitle(s),
                            LiveActivityStateBuilder.urgentAlertBody(s.rollup())) : null);
            deliver(t, buildMessage(t.getFcmToken(), t.getApnsToken(), apnsTopic, aps,
                    rose ? PRIORITY_HIGH : PRIORITY_LOW), now);
        }
        lastSent.put(groupId, new Sent(s.plan(), s.pickup(), urgent));
    }

    void sendSituationEnd(String groupId, Instant startedAt, String reason, String actorEmail) {
        sendSituationEnd(groupId, startedAt, reason, actorEmail, null);
    }

    /** {@code snapshot}: the ended situation built before its group was deleted; null reads it now. */
    private void sendSituationEnd(String groupId, Instant startedAt, String reason, String actorEmail,
                                  Situation snapshot) {
        Sent prev = lastSent.remove(groupId);
        List<LiveActivityToken> live = tokens.findLiveUpdateTokens(LiveActivityToken.KIND_SITUATION, groupId);
        if (live.isEmpty()) return;
        Instant now = Instant.now();
        List<Long> ids = live.stream().map(LiveActivityToken::getId).toList();

        Optional<Situation> found = snapshot != null
                ? Optional.of(snapshot)
                : builder.get().endedSituation(groupId, startedAt);
        if (found.isEmpty()) {
            // The group is gone: there are no honest numbers to end on. Retire
            // the tokens; the card goes stale and says so, and the app ends it.
            tokens.endByIds(ids, now);
            return;
        }
        Situation s = found.get();
        // An all-clear ends the plan in the same transaction, so "now" reads no
        // plan. The card was the plan variant if the last update said so.
        if (prev != null) {
            s = new Situation(s.groupId(), s.groupName(), s.groupType(), s.rollup(),
                    s.startedAt(), s.expiresAt(), prev.plan(), prev.pickup());
        }

        for (LiveActivityToken t : live) {
            boolean byViewer = actorEmail != null && actorEmail.trim().equalsIgnoreCase(t.getUserEmail());
            String note = LiveActivityStateBuilder.endedNote(reason, byViewer, startedAt, now);
            Map<String, Object> state = LiveActivityStateBuilder.contentState(
                    LiveActivityStateBuilder.PHASE_ENDED, s, now, note);
            boolean alert = !byViewer && !isSilenced(t.getUserEmail());
            Map<String, Object> aps = aps("end", state, now, null, now.plus(DISMISS_AFTER), s.urgent(),
                    alert ? alert(LiveActivityStateBuilder.alertTitle(s),
                            LiveActivityStateBuilder.endedAlertBody(s.rollup())) : null);
            deliver(t, buildMessage(t.getFcmToken(), t.getApnsToken(), apnsTopic, aps, PRIORITY_HIGH), now);
        }
        tokens.endByIds(ids, now);
    }

    // ── "You were asked to check in" (P2) ─────────────────────────────────

    static final String CHECKIN_PHASE_ASKED = "asked";
    static final String CHECKIN_PHASE_SENT = "sent";
    static final String CHECKIN_PHASE_ENDED = "ended";
    /** Must match the Swift type name exactly: push-to-start names it. */
    static final String CHECKIN_ATTRIBUTES_TYPE = "CheckInRequestAttributes";

    /**
     * Push-to-start one card per OTHER member's live push-to-start token.
     * Gated per member by {@link PushPolicyService#wouldPush} on the same
     * category the GROUP_ALERT push uses (household check-ins break quiet
     * hours, org ones respect them), without spending a second rate token.
     * Silent (D6): the GROUP_ALERT push beside it carries the sound.
     */
    void sendCheckInRequestStarts(String groupId, String actorEmail) {
        if (groupRepo == null || pushPolicy == null) return;
        Group g = groupRepo.findByGroupId(groupId).orElse(null);
        if (g == null || !"Active".equalsIgnoreCase(g.getAlert())) return;
        String actor = actorEmail == null ? null : actorEmail.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.Set<String> members = new java.util.LinkedHashSet<>();
        if (g.getMemberEmails() != null) {
            for (String m : g.getMemberEmails()) {
                if (m == null || m.isBlank()) continue;
                String e = m.trim().toLowerCase(java.util.Locale.ROOT);
                if (!e.equals(actor)) members.add(e);
            }
        }
        if (members.isEmpty()) return;
        boolean household = io.sitprep.sitprepapi.service.HouseholdEventService.HOUSEHOLD_GROUP_TYPE
                .equalsIgnoreCase(g.getGroupType());
        PushPolicyService.Category category = household
                ? PushPolicyService.Category.GROUP_ALERT_HOUSEHOLD
                : PushPolicyService.Category.GROUP_ALERT_ORG;
        Instant now = Instant.now();
        Instant askedAt = g.getAlertActivatedAt() != null ? g.getAlertActivatedAt() : now;
        Map<String, Object> attributes = checkInRequestAttributes(g, household, askedAt);
        Map<String, Object> state = checkInRequestState(CHECKIN_PHASE_ASKED, null, now, null);
        String name = g.getGroupName() == null || g.getGroupName().isBlank() ? "Your group" : g.getGroupName();

        for (LiveActivityToken t : tokens.findLivePushToStart(LiveActivityToken.KIND_CHECKIN_REQUEST, members)) {
            boolean push;
            try {
                push = pushPolicy.wouldPush(t.getUserEmail(), category, null);
            } catch (RuntimeException e) {
                push = false;   // refusing to decide is not consent to interrupt
            }
            if (!push) continue;
            Map<String, Object> aps = aps("start", state, now, null, null, 0,
                    silentAlert(name, "You were asked to check in."));
            aps.put("attributes-type", CHECKIN_ATTRIBUTES_TYPE);
            aps.put("attributes", attributes);
            deliver(t, buildMessage(t.getFcmToken(), t.getApnsToken(), apnsTopic, aps, PRIORITY_HIGH), now);
        }
    }

    /** End request cards: final state, dismissed {@link #DISMISS_AFTER} later, tokens retired. */
    private void endCheckInRequests(List<LiveActivityToken> live, String phase, String status, String note) {
        if (live == null || live.isEmpty()) return;
        Instant now = Instant.now();
        Map<String, Object> state = checkInRequestState(phase, status, now, note);
        for (LiveActivityToken t : live) {
            Map<String, Object> aps = aps("end", state, now, null, now.plus(DISMISS_AFTER), 0, null);
            deliver(t, buildMessage(t.getFcmToken(), t.getApnsToken(), apnsTopic, aps, PRIORITY_LOW), now);
        }
        tokens.endByIds(live.stream().map(LiveActivityToken::getId).toList(), now);
    }

    /** The static attributes. Keys are the Swift property names (Shared/ActivityAttributes.swift). */
    static Map<String, Object> checkInRequestAttributes(Group g, boolean household, Instant askedAt) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scope", household ? "household" : "group");
        a.put("groupId", g.getGroupId());
        a.put("groupName", g.getGroupName() == null ? "" : g.getGroupName());
        a.put("deepLink", io.sitprep.sitprepapi.notifications.NotificationRoutes.group(g.getGroupId(), household));
        a.put("askedAtEpoch", askedAt.getEpochSecond());
        return a;
    }

    /** The content state. Keys are the Swift property names; no names of people, ever. */
    static Map<String, Object> checkInRequestState(String phase, String status, Instant asOf, String note) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("phase", phase);
        s.put("status", status);
        s.put("asOfEpoch", asOf.getEpochSecond());
        s.put("note", note);
        return s;
    }

    /** Apple requires an alert on push-to-start; it carries no sound (the GROUP_ALERT push has it). */
    static Map<String, Object> silentAlert(String title, String body) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", title);
        a.put("body", body);
        return a;
    }

    private boolean isSilenced(String email) {
        try {
            return silenced.test(email);
        } catch (RuntimeException e) {
            return false;   // like shouldSendSilently: refusing to decide is not silence
        }
    }

    private void deliver(LiveActivityToken t, Message message, Instant now) {
        try {
            transport.send(message);
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                // INVALID_ARGUMENT is also what a malformed payload returns, so
                // it is logged loudly: a payload bug would retire every token.
                log.warn("LiveActivity: {} for token {} (user {}), retiring it: {}",
                        code, t.getId(), t.getUserEmail(), e.getMessage());
                try {
                    tokens.endByIds(List.of(t.getId()), now);
                } catch (RuntimeException inner) {
                    log.warn("LiveActivity: could not retire token {}: {}", t.getId(), inner.getMessage());
                }
            } else {
                log.warn("LiveActivity: FCM error {} for token {}: {}", code, t.getId(), e.getMessage());
            }
        } catch (RuntimeException e) {
            log.warn("LiveActivity: send failed for token {}: {}", t.getId(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Payload (pure)
    // ---------------------------------------------------------------------

    /** The {@code aps} dictionary of an ActivityKit push. */
    static Map<String, Object> aps(String event, Map<String, Object> contentState, Instant now,
                                   Instant staleDate, Instant dismissalDate, int urgent,
                                   Map<String, Object> alert) {
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("timestamp", now.getEpochSecond());
        aps.put("event", event);
        aps.put("content-state", contentState);
        if (staleDate != null) aps.put("stale-date", staleDate.getEpochSecond());
        if (dismissalDate != null) aps.put("dismissal-date", dismissalDate.getEpochSecond());
        aps.put("relevance-score", urgent > 0 ? 100 : 50);
        if (alert != null) aps.put("alert", alert);
        return aps;
    }

    static Map<String, Object> alert(String title, String body) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", title);
        a.put("body", body);
        a.put("sound", "default");
        return a;
    }

    static Message buildMessage(String fcmToken, String apnsToken, String apnsTopic,
                                Map<String, Object> aps, int priority) {
        ApnsConfig apns = ApnsConfig.builder()
                .setLiveActivityToken(apnsToken)
                .putHeader("apns-push-type", "liveactivity")
                .putHeader("apns-topic", apnsTopic)
                .putHeader("apns-priority", String.valueOf(priority))
                .setAps(Aps.builder().putAllCustomData(new LinkedHashMap<>(aps)).build())
                .build();
        return Message.builder()
                .setToken(fcmToken)
                .setApnsConfig(apns)
                .build();
    }

    /** For the token service: a new situation token gets one server-confirmed update. */
    void tokenRegistered(LiveActivityToken t) {
        if (t != null && LiveActivityToken.KIND_SITUATION.equals(t.getKind())
                && LiveActivityToken.TYPE_UPDATE.equals(t.getTokenType())) {
            situationChanged(t.getReferenceId());
        }
    }
}
