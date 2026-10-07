package io.sitprep.sitprepapi.notifications;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical app routes a notification may point at — the gameplan's deep-link
 * registry. Every builder here returns a path the FE router (`App.js`) actually
 * mounts; {@code NotificationRoutesTest} pins each one against the router's
 * pattern so a renamed route fails a test instead of stranding a tap.
 *
 * <p>Decisions recorded here rather than re-made per emitter:</p>
 * <ul>
 *   <li><b>Org group</b> → {@code /Linked/lg/4D-FwtX/{id}} (GroupUrlUtil's
 *       form; {@code /groups/{id}} renders the same page but the service worker
 *       already normalizes to this one, so new payloads emit only this).</li>
 *   <li><b>Household</b> → {@code /household/h/4D-FwtX/household/{id}/{tab}}.
 *       A household post lands on {@code chat}: the household page has no
 *       per-post focus, so the honest target is the conversation it is in.</li>
 *   <li><b>Community thread</b> → {@code /community/posts/{id}};
 *       {@code /community/tasks/{id}} is a compatibility alias only.</li>
 *   <li><b>Hazard</b> → {@code /hazards?alert={SOURCE-id}}, which HazardsPage
 *       expands on arrival. {@code /map?select=alert:} needs coordinates the
 *       payload does not carry, so it is not emitted (gameplan bug #2).</li>
 * </ul>
 */
public final class NotificationRoutes {

    private NotificationRoutes() {}

    public static final String INBOX = "/notifications";
    public static final String HOME = "/home";
    public static final String HAZARDS = "/hazards";
    public static final String PERSONAL_TASKS = "/me/tasks";
    public static final String GO_BAG = "/go-bag";
    /** The Ready for More journey (a "Remind me later" step coming due). */
    public static final String READY_FOR_MORE = "/ready-for-more";
    public static final String LOGIN = "/login";
    /**
     * The drill's own page. The notification knows the week, not which drill,
     * so the client picks this week's drill exactly as Home's sheet does and
     * replaces this route with /practice/drill/{id} (owner report 2026-10-03:
     * "/home?challenge=open" landed on Home, never on the drill).
     */
    public static final String DRILL = "/practice/this-week";
    public static final String TOKENS = "/profile?tab=tokens";
    public static final String COMMUNITY = "/community";
    public static final String MY_GROUPS = "/my-groups";
    public static final String WORK_ORDERS = "/work-orders";

    private static final String HOUSEHOLD_PREFIX = "/household/h/4D-FwtX/household/";
    private static final String GROUP_PREFIX = "/Linked/lg/4D-FwtX/";

    public static String hazard(String alertRef) {
        return blank(alertRef) ? HAZARDS : HAZARDS + "?alert=" + enc(alertRef);
    }

    public static String communityPost(String postId) {
        return blank(postId) ? COMMUNITY : "/community/posts/" + seg(postId);
    }

    public static String group(String groupId, boolean household) {
        if (blank(groupId)) return household ? HOME : MY_GROUPS;
        return household ? householdTab(groupId, "family") : GROUP_PREFIX + seg(groupId);
    }

    /**
     * The post's own thread. Org groups: {@code ?view=post&postId=} (OrgGroupPage
     * mounts the conversation only on view=post). Households: the chat tab with
     * {@code ?postId=} (HouseholdChatPage → HouseholdFeed.initialPostId).
     */
    public static String groupPost(String groupId, String postId, boolean household) {
        if (blank(groupId)) return household ? HOME : MY_GROUPS;
        if (household) return householdTab(groupId, "chat") + (blank(postId) ? "" : "?postId=" + enc(postId));
        return GROUP_PREFIX + seg(groupId) + "?view=post" + (blank(postId) ? "" : "&postId=" + enc(postId));
    }

    /** Org admin: the members sheet, where a pending join request is approved. */
    public static String groupMembers(String groupId) {
        return blank(groupId) ? MY_GROUPS : GROUP_PREFIX + seg(groupId) + "?view=admin&members=1";
    }

    /** Org organizer: the group's check-in status board. */
    public static String groupStatusBoard(String groupId) {
        return blank(groupId) ? MY_GROUPS : "/Groupstatus/" + seg(groupId);
    }

    /** A community thread with its replies open (CommunityPostDetailPage #comments). */
    public static String communityThread(String postId) {
        return blank(postId) ? COMMUNITY : communityPost(postId) + "#comments";
    }

    /** The personal task itself (MyTasksPage scrolls to + highlights ?task=). */
    public static String personalTask(String taskId) {
        return blank(taskId) ? PERSONAL_TASKS : PERSONAL_TASKS + "?task=" + enc(taskId);
    }

    /** {@code ?postId=} out of a group / household target URL. */
    public static String postIdFrom(String url) {
        if (blank(url)) return null;
        Matcher m = Pattern.compile("[?&]postId=([^&#]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String householdTab(String householdId, String tab) {
        if (blank(householdId)) return HOME;
        return HOUSEHOLD_PREFIX + seg(householdId) + (blank(tab) ? "" : "/" + tab);
    }

    public static String inviteRequest(String householdId, String requestId) {
        if (blank(householdId) || blank(requestId)) return householdTab(householdId, "family");
        return "/household/" + seg(householdId) + "/invite-requests/" + seg(requestId);
    }

    public static String workOrder(String id) {
        return blank(id) ? WORK_ORDERS : "/work-orders/" + seg(id);
    }

    public static String planActivation(String activationId) {
        return blank(activationId) ? HOME : "/deployedplan?activationId=" + enc(activationId);
    }

    /**
     * A direct message opens the CONVERSATION, not just the sender's profile:
     * there is no standalone messages route, and PublicProfilePage opens its
     * DMSheet on {@code ?message=open}. Before this a DM notification left the
     * reader on a profile, hunting for the Message button.
     */
    public static String directMessage(String identifier) {
        return blank(identifier) ? INBOX : profile(identifier) + "?message=open";
    }

    public static String profile(String identifier) {
        return blank(identifier) ? "/profile" : "/profile/" + seg(identifier);
    }

    // ── Legacy targetUrl → canonical ─────────────────────────────────────

    private static final Pattern BARE_GROUP = Pattern.compile("^/groups/([^/?#]+)/?([?#].*)?$");
    private static final Pattern TASK_ALIAS = Pattern.compile("^/community/tasks/([^/?#]+)/?([?#].*)?$");
    private static final Pattern HOUSEHOLD_POST = Pattern.compile("^/household/h/4D-FwtX/household/([^/?#]+)/?\\?postId=.*$");

    /**
     * Rewrites the aliases still present in emitters and old rows onto the
     * canonical form. Leaves anything else untouched — this is a normalizer,
     * not a validator; the FE resolver owns "is this a real route".
     */
    public static String canonicalize(String targetUrl) {
        if (blank(targetUrl)) return null;
        String url = targetUrl.trim();
        Matcher g = BARE_GROUP.matcher(url);
        if (g.matches()) return GROUP_PREFIX + g.group(1) + (g.group(2) == null ? "" : g.group(2));
        Matcher t = TASK_ALIAS.matcher(url);
        if (t.matches()) return "/community/posts/" + t.group(1) + (t.group(2) == null ? "" : t.group(2));
        if (url.equals("/Fema") || url.startsWith("/Fema?")) return HAZARDS + url.substring("/Fema".length());
        if (url.equals("/home?challenge=open")) return DRILL;
        // GroupUrlUtil + "?postId=" for a HOUSEHOLD: the bare household path
        // index-redirects to Family and drops the query, so the post's
        // conversation (chat) is the honest landing.
        Matcher h = HOUSEHOLD_POST.matcher(url);
        if (h.matches()) return householdTab(h.group(1), "chat") + "?postId=" + postIdFrom(url);
        return url;
    }

    /** {@code /household/h/4D-FwtX/household/{id}...} or {@code /household/{id}/invite-requests/...} → id. */
    public static String householdIdFrom(String url) {
        if (blank(url)) return null;
        Matcher m = Pattern.compile("^/household/(?:h/[^/]+/household/)?([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** {@code /household/{hid}/invite-requests/{rid}} → rid. */
    public static String inviteRequestIdFrom(String url) {
        if (blank(url)) return null;
        Matcher m = Pattern.compile("^/household/[^/]+/invite-requests/([^/?#]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern LINKED_GROUP = Pattern.compile("^/Linked/lg/4D-FwtX/([^/?#]+)");

    /** {@code /Linked/lg/4D-FwtX/{groupId}...} → groupId (group post / comment targets). */
    public static String linkedGroupIdFrom(String url) {
        if (blank(url)) return null;
        Matcher m = LINKED_GROUP.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Last path segment of {@code /community/posts/{id}} / {@code /work-orders/{id}}. */
    public static String trailingId(String url, String prefix) {
        if (blank(url) || !url.startsWith(prefix)) return null;
        String rest = url.substring(prefix.length());
        int cut = rest.indexOf('?');
        if (cut >= 0) rest = rest.substring(0, cut);
        cut = rest.indexOf('/');
        if (cut >= 0) rest = rest.substring(0, cut);
        return rest.isBlank() ? null : rest;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.trim(), StandardCharsets.UTF_8);
    }

    /** Path segment: encode, but URLEncoder's '+' for space is a query-string rule. */
    private static String seg(String s) {
        return enc(s).replace("+", "%20");
    }
}
