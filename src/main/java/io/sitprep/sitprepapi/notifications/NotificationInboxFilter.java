package io.sitprep.sitprepapi.notifications;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The inbox's server-side filters (S2 contract §5):
 * {@code GET /api/notifications?filter=all|unread|needs_response|alerts}.
 *
 * <h2>alerts</h2>
 * <p>{@code type = 'hazard_alert'} — official NWS/USGS warnings and verified
 * agency alerts, which ride the same hazard batch. Never the legacy
 * {@code type = 'alert'}: that is a household or group check-in, a request
 * from people the viewer knows, not an official alert.</p>
 *
 * <h2>needs_response — the server port of the inbox's classifier</h2>
 * <p>The frontend's {@code NotificationsInboxPage.needsResponse}: an UNREAD
 * row whose presentation offers a {@code STATUS} or {@code MUTATION} action, or
 * whose event is a conversation ({@code MENTION}, {@code COMMENT_REPLY},
 * {@code DIRECT_MESSAGE}, {@code FOLLOW_INVITE}). The presentation is built by
 * {@link NotificationPresentationBuilder} from the row's event, so the rule can
 * be stated per event — which is what lets the database apply it:</p>
 * <ul>
 *   <li><b>always</b>: {@link #ALWAYS} — the check-in/alert STATUS buttons and
 *       the conversations;</li>
 *   <li><b>with a reference</b>: {@link #WITH_REFERENCE} — the check-in
 *       review's "Keep going" and a plan activation's ack are mutations the
 *       builder only offers when the row names its group / activation;</li>
 *   <li><b>pending member</b>: Approve/Decline exist for a household invite
 *       request (its target URL) or a group request naming the requester's
 *       email.</li>
 * </ul>
 * <p>Official alerts are never in it: their only action is navigation. The
 * agency prefix is checked first, exactly as {@link NotificationEventType#resolve}
 * does, so an agency alert can never read as anything else.</p>
 *
 * <p>The SQL form of this rule is {@code NotificationLogInboxQueryImpl}; the
 * Java form is {@link #needsResponse}. A test holds the two together and
 * against the presentation the builder really writes.</p>
 */
public enum NotificationInboxFilter {
    ALL("all"),
    UNREAD("unread"),
    NEEDS_RESPONSE("needs_response"),
    ALERTS("alerts");

    private final String wire;

    NotificationInboxFilter(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Null or blank → {@link #ALL}; an unknown value → null (the resource answers 400). */
    public static NotificationInboxFilter parse(String raw) {
        if (raw == null || raw.isBlank()) return ALL;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (NotificationInboxFilter f : values()) {
            if (f.wire.equals(v)) return f;
        }
        return null;
    }

    /** The row type the alerts filter selects. */
    public static final String ALERT_TYPE = "hazard_alert";

    /** Events whose rows always wait on the viewer. */
    public static final Set<NotificationEventType> ALWAYS = Collections.unmodifiableSet(EnumSet.of(
            NotificationEventType.GROUP_ALERT,
            NotificationEventType.GROUP_ALERT_HOUSEHOLD,
            NotificationEventType.CHECK_IN_REQUEST,
            NotificationEventType.MENTION,
            NotificationEventType.COMMENT_REPLY,
            NotificationEventType.DIRECT_MESSAGE,
            NotificationEventType.FOLLOW_INVITE));

    /** Events whose mutation exists only when the row carries a referenceId. */
    public static final Set<NotificationEventType> WITH_REFERENCE = Collections.unmodifiableSet(EnumSet.of(
            NotificationEventType.CHECK_IN_REVIEW,
            NotificationEventType.PLAN_ACTIVATION));

    /** The Approve/Decline event. */
    public static final Set<NotificationEventType> PENDING = Collections.unmodifiableSet(EnumSet.of(
            NotificationEventType.PENDING_MEMBER));

    /** Every type string the registry knows, lower-case — a known type decides the event, not the category. */
    public static Set<String> registeredTypes() {
        Set<String> out = new LinkedHashSet<>();
        for (NotificationEventType e : NotificationEventType.values()) out.addAll(e.types());
        return out;
    }

    /** The type strings of {@code events}, lower-case. */
    public static Set<String> typesOf(Set<NotificationEventType> events) {
        Set<String> out = new LinkedHashSet<>();
        for (NotificationEventType e : events) out.addAll(e.types());
        return out;
    }

    /** The category names of {@code events}, upper-case. */
    public static Set<String> categoriesOf(Set<NotificationEventType> events) {
        Set<String> out = new LinkedHashSet<>();
        for (NotificationEventType e : events) {
            for (Category c : e.categories()) out.add(c.name());
        }
        return out;
    }

    /** {@code /household/{hid}/invite-requests/{rid}} — the SQL form is a LIKE over the same shape. */
    public static final String INVITE_REQUEST_LIKE = "/household/%/invite-requests/%";

    /**
     * The Java form of the needs_response rule, for one row. Equivalent to the
     * SQL predicate, and to the frontend classifier over the presentation the
     * builder writes for the row.
     */
    public static boolean needsResponse(NotificationLog row) {
        if (row == null || row.getReadAt() != null) return false;
        NotificationEventType ev = NotificationEventType.resolve(row.getType(), row.getCategory(), row.getReferenceId());
        if (ALWAYS.contains(ev)) return true;
        boolean hasRef = row.getReferenceId() != null && !row.getReferenceId().isBlank();
        if (WITH_REFERENCE.contains(ev)) return hasRef;
        if (PENDING.contains(ev)) {
            String target = NotificationRoutes.canonicalize(row.getTargetUrl());
            if (target != null && NotificationRoutes.inviteRequestIdFrom(target) != null) return true;
            String data = row.getAdditionalData() == null ? null : row.getAdditionalData().trim();
            return hasRef && data != null && data.contains("@") && !data.startsWith("{");
        }
        return false;
    }

    /**
     * The frontend classifier, verbatim in Java: unread, and a STATUS or
     * MUTATION action, or a conversational event key. Used by the test that
     * proves {@link #needsResponse} matches it.
     */
    public static boolean clientClassifier(NotificationLog row, NotificationPresentation p) {
        if (row == null || row.getReadAt() != null || p == null) return false;
        if (p.actions() != null) {
            for (NotificationPresentation.Action a : p.actions()) {
                if ("STATUS".equals(a.kind()) || "MUTATION".equals(a.kind())) return true;
            }
        }
        return CONVERSATIONAL_EVENT_KEYS.contains(p.eventKey());
    }

    /** {@code CONVERSATIONAL_EVENTS} in the frontend inbox. */
    public static final Set<String> CONVERSATIONAL_EVENT_KEYS =
            Set.of("MENTION", "COMMENT_REPLY", "DIRECT_MESSAGE", "FOLLOW_INVITE");
}
