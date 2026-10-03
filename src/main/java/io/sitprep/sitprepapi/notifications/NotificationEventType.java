package io.sitprep.sitprepapi.notifications;

import io.sitprep.sitprepapi.service.PushPolicyService.Category;

import java.util.Locale;
import java.util.Set;

/**
 * THE notification taxonomy — one constant per event, and every other
 * vocabulary is read from here.
 *
 * <p>Before this registry the same event was described in four places that
 * drifted independently: {@code NotificationService.mapTypeToCategory} (push
 * policy), {@code channelForType} (Android), {@code categoryForType} (iOS action
 * category) and the frontend's {@code CATEGORY_VIS}. A type could be known to one
 * and unknown to another, and an unknown type fails SILENTLY — a null policy
 * category skips every lane branch and falls through to a full push (the
 * {@code post_mention} incident, 2026-08-22). Those three methods now delegate
 * here, and {@code NotificationEventTypeTest} fails the build when an emitted
 * type string is not registered.</p>
 *
 * <p>{@code policy == null} is a recorded decision, not a gap: those types take
 * the legacy no-policy send path today, and giving them a lane changes who gets
 * pushed — an owner call (EXEC-N1, "out of scope"). The test pins the list so it
 * cannot grow by accident.</p>
 */
public enum NotificationEventType {

    // ── Official / hazard ────────────────────────────────────────────────
    /** NWS / USGS / wildfire warnings from AlertDispatchService. Life-safety:
     *  intentionally un-mapped in push policy so quiet hours never suppress it
     *  (the dispatcher evaluates the hazard categories itself). */
    HAZARD_ALERT(Set.of("hazard_alert"),
            Set.of(Category.NWS_SEVERE_EXTREME, Category.NWS_MINOR, Category.USGS_QUAKE_MAJOR,
                    Category.USGS_QUAKE_MINOR, Category.WILDFIRE_NEAR, Category.FEMA_DECLARATION),
            null, "alerts", "SYSTEM",
            NotificationSourceType.OFFICIAL_ALERT, "HAZARD_WARNING", Priority.EMERGENCY, "HAZARD_ALERT", true),
    /** A verified agency's alert post, fanned out through the hazard batch path
     *  with referenceId {@code agency-alert:{postId}}. Resolved by that prefix. */
    AGENCY_ALERT(Set.of(), Set.of(),
            null, "alerts", "SYSTEM",
            NotificationSourceType.OFFICIAL_ALERT, "CIVIC", Priority.EMERGENCY, "COMMUNITY_POST", false),

    // ── Household / group safety ─────────────────────────────────────────
    GROUP_ALERT(Set.of("alert", "group_status"), Set.of(Category.GROUP_ALERT_ORG),
            Category.GROUP_ALERT_ORG, "alerts", "GROUP_ALERT",
            NotificationSourceType.GROUP, "SHIELD_ALERT", Priority.ATTENTION, "GROUP", false),
    /** Reached by CATEGORY: the fan-out sends type {@code alert} with the
     *  household category so quiet hours get the critical bypass. */
    GROUP_ALERT_HOUSEHOLD(Set.of(), Set.of(Category.GROUP_ALERT_HOUSEHOLD),
            Category.GROUP_ALERT_HOUSEHOLD, "alerts", "GROUP_ALERT",
            NotificationSourceType.HOUSEHOLD, "SHIELD_ALERT", Priority.EMERGENCY, "HOUSEHOLD", false),
    CHECK_IN_REQUEST(Set.of("check_in_request"), Set.of(Category.CHECK_IN_REQUEST),
            Category.CHECK_IN_REQUEST, "general", "SYSTEM",
            NotificationSourceType.GROUP, "CHECK_IN", Priority.ATTENTION, "GROUP", false),
    CHECK_IN_REVIEW(Set.of("checkin_reminder"), Set.of(Category.CHECK_IN_REVIEW),
            Category.CHECK_IN_REVIEW, "general", "SYSTEM",
            NotificationSourceType.GROUP, "CHECK_IN", Priority.ROUTINE, "GROUP", false),
    CHECK_IN_ENDED(Set.of("checkin_auto_ended"), Set.of(),
            Category.CHECK_IN_REVIEW, "general", "SYSTEM",
            NotificationSourceType.GROUP, "CHECK_IN", Priority.ROUTINE, "GROUP", false),
    /** Channel moved general → alerts (2026-10-03): the old map only knew the
     *  upper-case spelling no emitter sends, so real activations rode the
     *  general channel. */
    PLAN_ACTIVATION(Set.of("plan_activation"), Set.of(Category.PLAN_ACTIVATION_RECEIVED),
            Category.PLAN_ACTIVATION_RECEIVED, "alerts", "PLAN_ACTIVATION",
            NotificationSourceType.HOUSEHOLD, "PLAN_SEND", Priority.EMERGENCY, "PLAN_ACTIVATION", false),
    PLAN_ACTIVATION_ENDED(Set.of("plan_activation_ended"), Set.of(),
            Category.PLAN_ACTIVATION_RECEIVED, "general", "SYSTEM",
            NotificationSourceType.HOUSEHOLD, "ALL_CLEAR", Priority.ROUTINE, "PLAN_ACTIVATION", false),
    ACTIVATION_ACK(Set.of("activation_ack"), Set.of(Category.ACTIVATION_ACK),
            Category.ACTIVATION_ACK, "general", "ACTIVATION_ACK",
            NotificationSourceType.USER, "STATUS", Priority.ATTENTION, "PLAN_ACTIVATION", false),

    // ── Work + personal prep ─────────────────────────────────────────────
    TASK_ASSIGNED(Set.of("task_assigned"), Set.of(Category.TASK_ASSIGNED),
            Category.TASK_ASSIGNED, "general", "TASK_ASSIGNED",
            NotificationSourceType.GROUP, "TASK", Priority.ATTENTION, "WORK_ORDER", false),
    TASK_STATUS_CHANGE(Set.of(), Set.of(Category.TASK_STATUS_CHANGE),
            Category.TASK_STATUS_CHANGE, "general", "SYSTEM",
            NotificationSourceType.GROUP, "TASK", Priority.ROUTINE, "WORK_ORDER", false),
    TASK_REMINDER(Set.of("task_reminder"), Set.of(),
            null, "general", "SYSTEM",
            NotificationSourceType.SYSTEM, "TASK", Priority.ROUTINE, "PERSONAL_TASK", false),
    GO_BAG_EXPIRY(Set.of("gobag_expiry"), Set.of(),
            null, "general", "SYSTEM",
            NotificationSourceType.SYSTEM, "GO_BAG", Priority.ROUTINE, "GO_BAG", false),
    GUEST_EXPIRY(Set.of("guest_expiry_reminder"), Set.of(),
            null, "general", "SYSTEM",
            NotificationSourceType.SYSTEM, "ACCOUNT", Priority.ATTENTION, "ACCOUNT", false),

    // ── Membership ───────────────────────────────────────────────────────
    PENDING_MEMBER(Set.of("pending_member"), Set.of(Category.PENDING_MEMBER_REQUEST),
            Category.PENDING_MEMBER_REQUEST, "membership", "PENDING_MEMBER",
            NotificationSourceType.GROUP, "USER_PLUS", Priority.ATTENTION, "GROUP", false),
    NEW_MEMBER(Set.of("new_member"), Set.of(Category.NEW_MEMBER),
            Category.NEW_MEMBER, "membership", "NEW_MEMBER",
            NotificationSourceType.GROUP, "MEMBER", Priority.ROUTINE, "GROUP", false),

    // ── Social ───────────────────────────────────────────────────────────
    /** A new post in a group the viewer is in. Policy MENTION is historical
     *  (shared quiet-hours/rate treatment) and kept as-is. */
    GROUP_POST(Set.of("post_notification"), Set.of(),
            Category.MENTION, "general", "SYSTEM",
            NotificationSourceType.GROUP, "POST", Priority.ROUTINE, "GROUP_POST", true),
    MENTION(Set.of("post_mention"), Set.of(Category.MENTION),
            Category.MENTION, "general", "MENTION",
            NotificationSourceType.USER, "MENTION", Priority.ROUTINE, "POST", true),
    /** {@code reply_on_followed} and {@code comment_thread_reply} were unmapped
     *  (null lane → full FCM push, no quiet hours). Mapping them to
     *  COMMENT_REPLY puts them in Lane B, as the approved matrix specifies. */
    COMMENT_REPLY(Set.of("comment_on_post", "comment_on_task", "comment_thread_reply", "reply_on_followed"),
            Set.of(Category.COMMENT_REPLY),
            Category.COMMENT_REPLY, "conversations", "COMMENT_REPLY",
            NotificationSourceType.USER, "REPLY", Priority.ROUTINE, "POST", false),
    REACTION_ROLLUP(Set.of(), Set.of(Category.REACTION_ROLLUP),
            Category.REACTION_ROLLUP, "general", "SYSTEM",
            NotificationSourceType.USER, "REACTION", Priority.ROUTINE, "POST", false),
    AUTO_POST_LOCAL(Set.of(), Set.of(Category.AUTO_POST_LOCAL),
            Category.AUTO_POST_LOCAL, "general", "SYSTEM",
            NotificationSourceType.SYSTEM, "POST", Priority.ROUTINE, "POST", false),
    FOLLOW(Set.of("follow"), Set.of(Category.FOLLOW),
            Category.FOLLOW, "general", "SYSTEM",
            NotificationSourceType.USER, "FOLLOW", Priority.ROUTINE, "PROFILE", false),
    FOLLOW_INVITE(Set.of(), Set.of(Category.FOLLOW_INVITE),
            Category.FOLLOW_INVITE, "general", "SYSTEM",
            NotificationSourceType.USER, "FOLLOW", Priority.ROUTINE, "PROFILE", false),
    FOLLOW_ACCEPTED(Set.of("follow_accepted"), Set.of(Category.FOLLOW_ACCEPTED),
            Category.FOLLOW_ACCEPTED, "general", "SYSTEM",
            NotificationSourceType.USER, "FOLLOW_ACCEPTED", Priority.ROUTINE, "PROFILE", false),
    DIRECT_MESSAGE(Set.of("dm_message"), Set.of(Category.DIRECT_MESSAGE),
            Category.DIRECT_MESSAGE, "general", "SYSTEM",
            NotificationSourceType.USER, "MESSAGE", Priority.ATTENTION, "PROFILE", false),

    // ── Household rhythm + recognition ───────────────────────────────────
    WEEKLY_DRILL(Set.of("weekly_drill_kickoff", "weekly_drill_nudge"), Set.of(Category.WEEKLY_DRILL_REMINDER),
            Category.WEEKLY_DRILL_REMINDER, "general", "SYSTEM",
            NotificationSourceType.HOUSEHOLD, "PRACTICE", Priority.ROUTINE, "HOUSEHOLD", false),
    HOUSEHOLD_RITUAL(Set.of("household_ritual_reminder"), Set.of(Category.HOUSEHOLD_RITUAL_REMINDER),
            Category.HOUSEHOLD_RITUAL_REMINDER, "general", "SYSTEM",
            NotificationSourceType.HOUSEHOLD, "RITUAL", Priority.ROUTINE, "HOUSEHOLD", false),
    TOKEN_UNLOCKED(Set.of("token_unlocked"), Set.of(Category.TOKEN_UNLOCKED),
            Category.TOKEN_UNLOCKED, "general", "SYSTEM",
            NotificationSourceType.TOKEN, "TOKEN", Priority.ROUTINE, "TOKEN", false),

    /** Anything not registered. Rendered as a SitPrep system row; a test keeps
     *  every emitted type OFF this path. */
    UNKNOWN(Set.of(), Set.of(),
            null, "general", "SYSTEM",
            NotificationSourceType.SYSTEM, "SYSTEM", Priority.ROUTINE, null, false);

    public enum Priority {
        EMERGENCY, ATTENTION, ROUTINE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Prefix AgencyAlertDispatchService puts on the hazard-batch referenceId. */
    public static final String AGENCY_ALERT_REF_PREFIX = "agency-alert:";

    private final Set<String> types;
    private final Set<Category> categories;
    private final Category policy;
    private final String androidChannel;
    private final String iosCategory;
    private final NotificationSourceType sourceType;
    private final String badgeKind;
    private final Priority priority;
    private final String recordType;
    private final boolean thumbnailAllowed;

    NotificationEventType(Set<String> types, Set<Category> categories, Category policy,
                          String androidChannel, String iosCategory,
                          NotificationSourceType sourceType, String badgeKind, Priority priority,
                          String recordType, boolean thumbnailAllowed) {
        this.types = types;
        this.categories = categories;
        this.policy = policy;
        this.androidChannel = androidChannel;
        this.iosCategory = iosCategory;
        this.sourceType = sourceType;
        this.badgeKind = badgeKind;
        this.priority = priority;
        this.recordType = recordType;
        this.thumbnailAllowed = thumbnailAllowed;
    }

    /** Type-only lookup — what the send pipeline has before policy runs. */
    public static NotificationEventType forType(String type) {
        if (type == null) return UNKNOWN;
        String t = type.trim().toLowerCase(Locale.ROOT);
        for (NotificationEventType e : values()) {
            if (e.types.contains(t)) return e;
        }
        return UNKNOWN;
    }

    /**
     * Full resolution for a persisted row. Precedence:
     * <ol>
     *   <li>the agency-alert referenceId prefix (it rides the hazard type);</li>
     *   <li>the household alert flip — type {@code alert} sent with category
     *       {@code GROUP_ALERT_HOUSEHOLD} (the one event the type alone cannot
     *       tell apart);</li>
     *   <li>the type;</li>
     *   <li>the category alone (category-only events, legacy rows with a
     *       blank type).</li>
     * </ol>
     */
    public static NotificationEventType resolve(String type, String category, String referenceId) {
        if (referenceId != null && referenceId.startsWith(AGENCY_ALERT_REF_PREFIX)) return AGENCY_ALERT;
        NotificationEventType byType = forType(type);
        NotificationEventType byCategory = forCategory(category);
        if (byType == GROUP_ALERT && byCategory == GROUP_ALERT_HOUSEHOLD) return GROUP_ALERT_HOUSEHOLD;
        if (byType != UNKNOWN) return byType;
        return byCategory;
    }

    public static NotificationEventType forCategory(String category) {
        if (category == null || category.isBlank()) return UNKNOWN;
        for (NotificationEventType e : values()) {
            for (Category c : e.categories) {
                if (c.name().equalsIgnoreCase(category.trim())) return e;
            }
        }
        return UNKNOWN;
    }

    public Set<String> types() { return types; }
    public Set<Category> categories() { return categories; }
    public Category policy() { return policy; }
    public String androidChannel() { return androidChannel; }
    public String iosCategory() { return iosCategory; }
    public NotificationSourceType sourceType() { return sourceType; }
    public String badgeKind() { return badgeKind; }
    public Priority priority() { return priority; }
    public String recordType() { return recordType; }
    public boolean thumbnailAllowed() { return thumbnailAllowed; }
}
