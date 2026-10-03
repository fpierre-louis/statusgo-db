package io.sitprep.sitprepapi.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.HazardType;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupPost;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.DtoImages;
import io.sitprep.sitprepapi.notifications.NotificationEventType.Priority;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.Action;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.Actor;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.DeepLink;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.Media;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.Source;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.Visual;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.HouseholdEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the {@link NotificationPresentation} for a notification — the ONE place
 * that turns a row's free-form fields into the typed contract.
 *
 * <p>Two callers, one code path:</p>
 * <ul>
 *   <li><b>Write</b> — {@code NotificationService.saveLogRow} calls
 *       {@link #build(NotificationLog)} and stores the result in
 *       {@code presentation_json}. Post thumbnails are looked up here, once.</li>
 *   <li><b>Read</b> — {@link #forRows(List)} returns the stored presentation, or
 *       builds one for a legacy row (null column) with batched actor + group
 *       lookups: two queries per page, never N. Legacy rows get no thumbnail —
 *       the per-row post lookup is a write-time cost only.</li>
 * </ul>
 *
 * <p>Every lookup is best-effort. A failure degrades to the event's defaults
 * (source-type avatar, route from the taxonomy) — a presentation is never the
 * reason a notification fails to send or the inbox fails to load.</p>
 */
@Component
public class NotificationPresentationBuilder {

    private static final Logger log = LoggerFactory.getLogger(NotificationPresentationBuilder.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private static final Map<String, String> OFFICIAL_SOURCE_NAMES = Map.of(
            "NWS", "National Weather Service",
            "USGS", "U.S. Geological Survey",
            "FEMA", "FEMA");

    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final GroupPostRepo groupPostRepo;
    private final PostRepo postRepo;

    public NotificationPresentationBuilder(UserInfoRepo userInfoRepo,
                                           GroupRepo groupRepo,
                                           GroupPostRepo groupPostRepo,
                                           PostRepo postRepo) {
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.groupPostRepo = groupPostRepo;
        this.postRepo = postRepo;
    }

    // ── Entry points ─────────────────────────────────────────────────────

    /** Write path: full enrichment including a post thumbnail. */
    public NotificationPresentation build(NotificationLog row) {
        if (row == null) return null;
        Lookups lookups = new Lookups(
                id -> safe(() -> userInfoRepo.findById(id).orElse(null)),
                id -> safe(() -> groupRepo.findById(id).orElse(null)),
                true);
        return build(row, lookups);
    }

    /** Write path, serialized for {@code notification_log.presentation_json}. */
    public Map<String, Object> buildJson(NotificationLog row) {
        return toMap(build(row));
    }

    /**
     * Read path for a page of rows. Stored presentations are returned as-is;
     * legacy rows are built with ONE batched user lookup and ONE batched group
     * lookup for the whole page.
     */
    public Map<Long, NotificationPresentation> forRows(List<NotificationLog> rows) {
        Map<Long, NotificationPresentation> out = new HashMap<>();
        if (rows == null || rows.isEmpty()) return out;
        Map<Long, NotificationPresentation> stored = new HashMap<>();
        List<NotificationLog> legacy = new ArrayList<>();
        Set<String> actorIds = new HashSet<>();
        Set<String> groupIds = new HashSet<>();
        for (NotificationLog r : rows) {
            if (r.getActorUserId() != null) actorIds.add(r.getActorUserId());
            NotificationPresentation sp = fromMap(r.getPresentationJson());
            if (sp != null) {
                stored.put(r.getId(), sp);
                if (sp.actor() != null && sp.actor().userId() != null) actorIds.add(sp.actor().userId());
                if (sp.source() != null && isPersonOrGroup(sp.source()) && sp.source().entityId() != null) {
                    (isGroupSource(sp.source()) ? groupIds : actorIds).add(sp.source().entityId());
                }
            } else {
                legacy.add(r);
                String gid = groupIdFor(NotificationEventType.resolve(r.getType(), r.getCategory(), r.getReferenceId()),
                        r, parse(r.getAdditionalData()));
                if (gid != null) groupIds.add(gid);
            }
        }
        // Two queries for the whole page, shared by both kinds of row.
        Map<String, UserInfo> users = batch(actorIds, ids -> userInfoRepo.findAllById(ids), UserInfo::getId);
        Map<String, Group> groups = batch(groupIds, ids -> groupRepo.findAllById(ids), Group::getGroupId);
        Lookups lookups = new Lookups(users::get, groups::get, false);
        for (NotificationLog r : legacy) out.put(r.getId(), build(r, lookups));
        stored.forEach((id, p) -> out.put(id, withCurrentIdentity(p, users, groups)));
        return out;
    }

    /**
     * A stored presentation is a snapshot from send time; the person's photo
     * and the group's name/logo are not. The owner asked for the CURRENT
     * profile image (2026-10-03), so identity is re-read on every page —
     * everything else (route, actions, badge) stays as sent.
     */
    static NotificationPresentation withCurrentIdentity(NotificationPresentation p,
                                                       Map<String, UserInfo> users,
                                                       Map<String, Group> groups) {
        if (p == null) return null;
        Actor actor = p.actor();
        if (actor != null && actor.userId() != null && users.containsKey(actor.userId())) {
            actor = actorOf(users.get(actor.userId()));
        }
        Source source = p.source();
        if (source != null && source.entityId() != null) {
            if (isGroupSource(source) && groups.containsKey(source.entityId())) {
                source = groupSource(NotificationSourceType.valueOf(source.entityType()), groups.get(source.entityId()));
            } else if ("USER".equals(source.entityType()) && users.containsKey(source.entityId())) {
                Actor current = actorOf(users.get(source.entityId()));
                source = new Source(source.entityType(), current.userId(), current.displayName(),
                        current.avatarUrl(), source.fallbackKind(), null);
            }
        }
        if (actor == p.actor() && source == p.source()) return p;
        return new NotificationPresentation(p.version(), p.eventKey(), source, actor, p.media(),
                p.deepLink(), p.actions(), p.visual());
    }

    private static boolean isGroupSource(Source s) {
        return "HOUSEHOLD".equals(s.entityType()) || "GROUP".equals(s.entityType());
    }

    private static boolean isPersonOrGroup(Source s) {
        return isGroupSource(s) || "USER".equals(s.entityType());
    }

    /** Single-row read (STOMP {@code created} frames carry the stored value). */
    public NotificationPresentation forRow(NotificationLog row) {
        if (row == null) return null;
        NotificationPresentation stored = fromMap(row.getPresentationJson());
        return stored != null ? stored : forRows(List.of(row)).get(row.getId());
    }

    // ── Core ─────────────────────────────────────────────────────────────

    record Lookups(Function<String, UserInfo> user,
                   Function<String, Group> group,
                   boolean thumbnails) {}

    NotificationPresentation build(NotificationLog row, Lookups lookups) {
        try {
            return assemble(row, lookups);
        } catch (RuntimeException e) {
            // Never let presentation break a send or a page. Fall back to the
            // bare taxonomy defaults for this row.
            log.warn("Notification presentation build failed for type={} ref={}: {}",
                    row.getType(), row.getReferenceId(), e.getMessage());
            NotificationEventType ev = NotificationEventType.resolve(row.getType(), row.getCategory(), row.getReferenceId());
            String route = Objects.requireNonNullElse(NotificationRoutes.canonicalize(row.getTargetUrl()), NotificationRoutes.INBOX);
            return new NotificationPresentation(NotificationPresentation.VERSION, ev.name(),
                    new Source(ev.sourceType().name(), null, null, null, ev.sourceType().avatarKind(), null),
                    null, null,
                    new DeepLink(route, NotificationRoutes.INBOX, ev.recordType(), row.getReferenceId()),
                    List.of(),
                    new Visual(ev.sourceType().avatarKind(), ev.badgeKind(), ev.priority().wire(), false));
        }
    }

    private NotificationPresentation assemble(NotificationLog row, Lookups lookups) {
        NotificationEventType ev = NotificationEventType.resolve(row.getType(), row.getCategory(), row.getReferenceId());
        Map<String, Object> data = parse(row.getAdditionalData());
        String ref = row.getReferenceId();
        String target = NotificationRoutes.canonicalize(row.getTargetUrl());

        UserInfo actorUser = row.getActorUserId() == null ? null : lookups.user().apply(row.getActorUserId());
        Actor actor = actorUser == null ? null : actorOf(actorUser);

        String groupId = groupIdFor(ev, row, data);
        Group group = groupId == null ? null : lookups.group().apply(groupId);
        boolean household = group != null
                ? HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType())
                : ev.sourceType() == NotificationSourceType.HOUSEHOLD;

        NotificationSourceType sourceType = sourceTypeFor(ev, group, household);
        Source source = sourceFor(ev, sourceType, row, data, group, actor);

        Priority priority = ev.priority();
        String badge = ev.badgeKind();
        Media media = null;
        String route;
        String fallback;
        List<Action> actions = new ArrayList<>();

        switch (ev) {
            case HAZARD_ALERT -> {
                String hazardKey = hazardKeyFor(str(data, "event"), str(data, "source"), ref);
                badge = hazardBadge(row.getCategory(), str(data, "source"), ref, hazardKey);
                priority = hazardPriority(row.getCategory(), str(data, "severity"));
                if (hazardKey != null) {
                    media = new Media(null, "HAZARD_ICON", hazardKey, str(data, "event"));
                }
                route = NotificationRoutes.hazard(ref);
                fallback = NotificationRoutes.HAZARDS;
                actions.add(Action.navigate("VIEW_WARNING",
                        priority == Priority.EMERGENCY ? "View warning" : "Review", route, "primary"));
            }
            case AGENCY_ALERT -> {
                String postId = ref.substring(NotificationEventType.AGENCY_ALERT_REF_PREFIX.length());
                route = NotificationRoutes.communityPost(postId);
                fallback = NotificationRoutes.COMMUNITY;
                actions.add(Action.navigate("VIEW_ALERT", "View alert", route, "primary"));
            }
            case GROUP_ALERT, GROUP_ALERT_HOUSEHOLD, CHECK_IN_REQUEST -> {
                route = NotificationRoutes.group(groupId, household);
                fallback = household ? NotificationRoutes.HOME : NotificationRoutes.MY_GROUPS;
                if (household && ev == NotificationEventType.GROUP_ALERT) priority = Priority.EMERGENCY;
                actions.add(Action.status("SAFE", "I'm safe", "SAFE", route, "primary"));
                actions.add(Action.status("HELP", "Need help", "HELP", route, "danger"));
            }
            case CHECK_IN_REVIEW -> {
                route = NotificationRoutes.group(groupId, household);
                fallback = household ? NotificationRoutes.HOME : NotificationRoutes.MY_GROUPS;
                if (groupId != null) {
                    actions.add(Action.mutation("KEEP_GOING", "Keep going", "continueCheckIn",
                            Map.of("groupId", groupId), route, "primary"));
                }
                actions.add(Action.navigate("REVIEW", "Review", route, actions.isEmpty() ? "primary" : "secondary"));
            }
            case CHECK_IN_ENDED -> {
                route = NotificationRoutes.group(groupId, household);
                fallback = household ? NotificationRoutes.HOME : NotificationRoutes.MY_GROUPS;
                actions.add(Action.navigate("REVIEW", "Review", route, "primary"));
            }
            case PLAN_ACTIVATION -> {
                route = NotificationRoutes.planActivation(ref);
                fallback = NotificationRoutes.HOME;
                if (ref != null && !ref.isBlank()) {
                    actions.add(Action.mutation("ACK_SAFE", "I'm safe", "ackActivation",
                            Map.of("activationId", ref, "status", "SAFE"), route, "primary"));
                    actions.add(Action.mutation("ACK_PICKUP", "Need pickup", "ackActivation",
                            Map.of("activationId", ref, "status", "PICKUP"), route, "secondary"));
                }
            }
            case PLAN_ACTIVATION_ENDED -> {
                route = NotificationRoutes.planActivation(ref);
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("VIEW_PLAN", "View plan", route, "primary"));
            }
            case ACTIVATION_ACK -> {
                route = NotificationRoutes.planActivation(ref);
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("VIEW_ROLLUP", "View check-ins", route, "primary"));
            }
            case TASK_ASSIGNED, TASK_STATUS_CHANGE -> {
                route = NotificationRoutes.workOrder(ref);
                fallback = NotificationRoutes.WORK_ORDERS;
                actions.add(Action.navigate("OPEN", "Open", route, "primary"));
            }
            case TASK_REMINDER -> {
                route = NotificationRoutes.PERSONAL_TASKS;
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("OPEN_TASKS", "Open tasks", route, "primary"));
            }
            case GO_BAG_EXPIRY -> {
                route = NotificationRoutes.GO_BAG;
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("REVIEW_BAG", "Review bag", route, "primary"));
            }
            case GUEST_EXPIRY -> {
                route = NotificationRoutes.LOGIN;
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("SIGN_IN", "Sign in", route, "primary"));
            }
            case PENDING_MEMBER -> {
                String requestId = NotificationRoutes.inviteRequestIdFrom(target);
                if (requestId != null) {
                    String hid = NotificationRoutes.householdIdFrom(target);
                    route = NotificationRoutes.inviteRequest(hid, requestId);
                    fallback = NotificationRoutes.householdTab(hid, "family");
                    Map<String, String> p = Map.of("householdId", hid, "requestId", requestId);
                    actions.add(Action.mutation("APPROVE", "Approve", "approveHouseholdInvite", p, route, "primary"));
                    actions.add(Action.mutation("DECLINE", "Decline", "declineHouseholdInvite", p, route, "secondary"));
                } else {
                    route = NotificationRoutes.group(groupId, household);
                    fallback = NotificationRoutes.MY_GROUPS;
                    String email = row.getAdditionalData() == null ? null : row.getAdditionalData().trim();
                    if (groupId != null && email != null && email.contains("@") && !email.startsWith("{")) {
                        Map<String, String> p = Map.of("groupId", groupId, "email", email);
                        actions.add(Action.mutation("APPROVE", "Approve", "approveGroupMember", p, route, "primary"));
                        actions.add(Action.mutation("DECLINE", "Decline", "rejectGroupMember", p, route, "secondary"));
                    } else {
                        actions.add(Action.navigate("REVIEW", "Review", route, "primary"));
                    }
                }
            }
            case NEW_MEMBER -> {
                route = NotificationRoutes.group(groupId, household);
                fallback = NotificationRoutes.MY_GROUPS;
                actions.add(Action.navigate("VIEW_GROUP", household ? "Open household" : "View group", route, "primary"));
            }
            case GROUP_POST -> {
                String postId = data.isEmpty() ? blankToNull(row.getAdditionalData()) : null;
                route = NotificationRoutes.groupPost(groupId, postId, household);
                fallback = NotificationRoutes.group(groupId, household);
                if (lookups.thumbnails() && postId != null) media = groupPostImage(postId);
                actions.add(Action.navigate("OPEN", "Open", route, "primary"));
            }
            case MENTION, COMMENT_REPLY -> {
                // Group comment / mention emitters write the ORG group URL for
                // every group. For a household that opens the household inside
                // the org-group shell; its conversation is the household chat.
                route = household && groupId != null
                        ? NotificationRoutes.householdTab(groupId, "chat")
                        : (target != null ? target : NotificationRoutes.INBOX);
                fallback = NotificationRoutes.COMMUNITY;
                if (lookups.thumbnails() && ev.thumbnailAllowed()) media = mentionImage(row, target);
                actions.add(Action.navigate("REPLY", "Reply", route, "primary"));
            }
            case REACTION_ROLLUP, AUTO_POST_LOCAL -> {
                route = target != null ? target : NotificationRoutes.COMMUNITY;
                fallback = NotificationRoutes.COMMUNITY;
                actions.add(Action.navigate("VIEW_POST", "View post", route, "primary"));
            }
            case FOLLOW, FOLLOW_INVITE, FOLLOW_ACCEPTED -> {
                route = target != null ? target
                        : NotificationRoutes.profile(row.getActorUserId());
                fallback = "/profile";
                actions.add(Action.navigate("VIEW_PROFILE", "View profile", route, "primary"));
            }
            case DIRECT_MESSAGE -> {
                String peer = row.getActorUserId() != null ? row.getActorUserId()
                        : NotificationRoutes.trailingId(target, "/profile/");
                route = NotificationRoutes.directMessage(peer);
                fallback = NotificationRoutes.INBOX;
                actions.add(Action.navigate("REPLY", "Reply", route, "primary"));
            }
            case WEEKLY_DRILL -> {
                route = NotificationRoutes.DRILL;
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("OPEN_DRILL", "Open drill", route, "primary"));
            }
            case HOUSEHOLD_RITUAL -> {
                route = NotificationRoutes.householdTab(groupId, "family");
                fallback = NotificationRoutes.HOME;
                actions.add(Action.navigate("OPEN_HOUSEHOLD", "Open household", route, "primary"));
            }
            case TOKEN_UNLOCKED -> {
                route = NotificationRoutes.TOKENS;
                fallback = "/profile";
                actions.add(Action.navigate("VIEW_TOKENS", "View tokens", route, "primary"));
            }
            default -> {
                route = target != null ? target : NotificationRoutes.INBOX;
                fallback = NotificationRoutes.INBOX;
            }
        }

        return new NotificationPresentation(
                NotificationPresentation.VERSION,
                ev.name(),
                source,
                actor,
                media,
                new DeepLink(route, fallback, ev.recordType(), recordIdFor(ev, ref, route)),
                List.copyOf(actions),
                new Visual(avatarKindFor(ev, sourceType), badge, priority.wire(),
                        media != null && media.thumbnailUrl() != null));
    }

    // ── Identity ─────────────────────────────────────────────────────────

    private static Actor actorOf(UserInfo u) {
        String name = String.join(" ",
                u.getUserFirstName() == null ? "" : u.getUserFirstName().trim(),
                u.getUserLastName() == null ? "" : u.getUserLastName().trim()).trim();
        return new Actor(u.getId(), name.isEmpty() ? null : name,
                DtoImages.avatar(u.getProfileImageUrl()), NotificationRoutes.profile(u.getId()));
    }

    /** Where each event keeps its group/household id (see the emitter audit in EXEC-N1). */
    static String groupIdFor(NotificationEventType ev, NotificationLog row, Map<String, Object> data) {
        String ref = blankToNull(row.getReferenceId());
        return switch (ev) {
            case GROUP_ALERT, GROUP_ALERT_HOUSEHOLD, CHECK_IN_REQUEST, CHECK_IN_REVIEW, CHECK_IN_ENDED,
                 NEW_MEMBER, GROUP_POST, WEEKLY_DRILL, HOUSEHOLD_RITUAL -> ref;
            case PENDING_MEMBER -> {
                String hid = NotificationRoutes.inviteRequestIdFrom(row.getTargetUrl()) != null
                        ? NotificationRoutes.householdIdFrom(row.getTargetUrl()) : null;
                yield hid != null ? hid : ref;
            }
            // TaskAssignmentService sends the task's groupId as additionalData.
            case TASK_ASSIGNED -> data.isEmpty() ? blankToNull(row.getAdditionalData()) : str(data, "groupId");
            case PLAN_ACTIVATION, PLAN_ACTIVATION_ENDED -> str(data, "householdId");
            // Only to learn whether the thread lives in a HOUSEHOLD (→ chat).
            case MENTION, COMMENT_REPLY -> NotificationRoutes.linkedGroupIdFrom(row.getTargetUrl());
            default -> null;
        };
    }

    private static NotificationSourceType sourceTypeFor(NotificationEventType ev, Group group, boolean household) {
        NotificationSourceType base = ev.sourceType();
        if (base == NotificationSourceType.GROUP || base == NotificationSourceType.HOUSEHOLD) {
            return household ? NotificationSourceType.HOUSEHOLD : NotificationSourceType.GROUP;
        }
        return base;
    }

    private static Source sourceFor(NotificationEventType ev, NotificationSourceType type,
                                    NotificationLog row, Map<String, Object> data,
                                    Group group, Actor actor) {
        String fallbackKind = type.avatarKind();
        return switch (type) {
            case OFFICIAL_ALERT -> {
                if (ev == NotificationEventType.AGENCY_ALERT) {
                    yield new Source(type.name(), row.getReferenceId(), null, null, fallbackKind, null);
                }
                String src = officialSourceCode(str(data, "source"), row.getReferenceId());
                yield new Source(type.name(), row.getReferenceId(),
                        src == null ? null : OFFICIAL_SOURCE_NAMES.get(src), null, fallbackKind, null);
            }
            case HOUSEHOLD, GROUP -> group == null
                    ? new Source(type.name(), null, null, null, fallbackKind, null)
                    : groupSource(type, group);
            case USER -> actor == null
                    ? new Source(type.name(), null, null, null, fallbackKind, null)
                    : new Source(type.name(), actor.userId(), actor.displayName(), actor.avatarUrl(), fallbackKind, null);
            case TOKEN -> new Source(type.name(), str(data, "tokenKey"), null, null, fallbackKind, null);
            default -> new Source(type.name(), null, "SitPrep", null, fallbackKind, null);
        };
    }

    /**
     * A weekly drill is attributed to the household but DRAWN as practice: the
     * owner asked for the drill's own imagery (2026-10-03). The row carries the
     * week, not which drill, so the art is the practice illustration — never a
     * hazard picture that would be a guess.
     */
    private static String avatarKindFor(NotificationEventType ev, NotificationSourceType sourceType) {
        return ev == NotificationEventType.WEEKLY_DRILL ? "PRACTICE" : sourceType.avatarKind();
    }

    private static Source groupSource(NotificationSourceType type, Group group) {
        return new Source(type.name(), group.getGroupId(), blankToNull(group.getGroupName()),
                DtoImages.avatar(group.getLogoImageUrl()), type.avatarKind(), blankToNull(group.getGroupType()));
    }

    private static String recordIdFor(NotificationEventType ev, String ref, String route) {
        if (ev == NotificationEventType.AGENCY_ALERT && ref != null) {
            return ref.substring(NotificationEventType.AGENCY_ALERT_REF_PREFIX.length());
        }
        return blankToNull(ref);
    }

    // ── Hazards ──────────────────────────────────────────────────────────

    static String officialSourceCode(String source, String referenceId) {
        String s = blankToNull(source);
        if (s == null && referenceId != null) {
            int dash = referenceId.indexOf('-');
            if (dash > 0) s = referenceId.substring(0, dash);
        }
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * NWS event text → the {@link HazardType} wire value the FE's
     * {@code HazardIllustration} draws. Keyword match on the official event
     * name; anything unmatched gets no mark rather than a wrong one.
     */
    static String hazardKeyFor(String event, String source, String referenceId) {
        if ("USGS".equals(officialSourceCode(source, referenceId))) return HazardType.EARTHQUAKE.wire();
        if (event == null) return null;
        String e = event.toLowerCase(Locale.ROOT);
        if (e.contains("tornado")) return HazardType.TORNADO.wire();
        if (e.contains("hurricane") || e.contains("tropical storm")) return HazardType.HURRICANE.wire();
        if (e.contains("flood")) return HazardType.FLOOD.wire();
        if (e.contains("fire") || e.contains("red flag")) return HazardType.WILDFIRE.wire();
        if (e.contains("earthquake")) return HazardType.EARTHQUAKE.wire();
        if (e.contains("blizzard") || e.contains("winter storm") || e.contains("ice storm")) return HazardType.BLIZZARD.wire();
        if (e.contains("heat")) return HazardType.HEAT.wire();
        return null;
    }

    private static String hazardBadge(String category, String source, String ref, String hazardKey) {
        if ("FEMA_DECLARATION".equals(category) || "FEMA".equals(officialSourceCode(source, ref))) return "CIVIC";
        if ("USGS".equals(officialSourceCode(source, ref)) || HazardType.EARTHQUAKE.wire().equals(hazardKey)) return "QUAKE";
        if ("WILDFIRE_NEAR".equals(category) || HazardType.WILDFIRE.wire().equals(hazardKey)) return "WILDFIRE";
        if ("NWS_MINOR".equals(category)) return "HAZARD_ADVISORY";
        return "HAZARD_WARNING";
    }

    private static Priority hazardPriority(String category, String severity) {
        if ("NWS_MINOR".equals(category) || "USGS_QUAKE_MINOR".equals(category)
                || "FEMA_DECLARATION".equals(category)) {
            return Priority.ATTENTION;
        }
        if (severity != null && (severity.equalsIgnoreCase("Minor") || severity.equalsIgnoreCase("Moderate"))) {
            return Priority.ATTENTION;
        }
        // The severe batch path (no category on the row) is Severe/Extreme only.
        return Priority.EMERGENCY;
    }

    // ── Thumbnails (write path only) ─────────────────────────────────────

    private Media groupPostImage(String postId) {
        Long id = parseLong(postId);
        if (id == null) return null;
        GroupPost post = safe(() -> groupPostRepo.findById(id).orElse(null));
        String url = post == null ? null : DtoImages.cover(post.getImageKey());
        return url == null ? null : new Media(url, "POST_IMAGE", null, "Photo attached to the post");
    }

    private Media mentionImage(NotificationLog row, String target) {
        String communityId = NotificationRoutes.trailingId(target, "/community/posts/");
        if (communityId != null) {
            Long id = parseLong(communityId);
            Post post = id == null ? null : safe(() -> postRepo.findById(id).orElse(null));
            if (post == null || post.isAuthorHidden()) return null;
            List<String> keys = post.getImageKeys();
            String url = keys == null || keys.isEmpty() ? null : DtoImages.cover(keys.get(0));
            return url == null ? null : new Media(url, "POST_IMAGE", null, "Photo attached to the post");
        }
        // Group-post mention: GroupPostService puts the post id in additionalData.
        return groupPostImage(row.getAdditionalData());
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    public static Map<String, Object> toMap(NotificationPresentation p) {
        if (p == null) return null;
        return JSON.convertValue(p, MAP);
    }

    public static NotificationPresentation fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return null;
        try {
            return JSON.convertValue(m, NotificationPresentation.class);
        } catch (IllegalArgumentException e) {
            return null; // unreadable stored shape → rebuilt from the row
        }
    }

    /** additionalData as a JSON object; empty map when it is plain text or absent. */
    static Map<String, Object> parse(String additionalData) {
        if (additionalData == null) return Map.of();
        String s = additionalData.trim();
        if (!s.startsWith("{")) return Map.of();
        try {
            Map<String, Object> m = JSON.readValue(s, MAP);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Map<String, Object> data, String key) {
        Object v = data.get(key);
        return v == null ? null : blankToNull(String.valueOf(v));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static <T> Map<String, T> batch(Set<String> ids,
                                            Function<Collection<String>, List<T>> fetch,
                                            Function<T, String> key) {
        if (ids.isEmpty()) return Map.of();
        List<T> found = safe(() -> fetch.apply(ids));
        if (found == null) return Map.of();
        return found.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(key, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static <T> T safe(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            log.debug("Notification presentation lookup failed: {}", e.getMessage());
            return null;
        }
    }
}
