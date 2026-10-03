package io.sitprep.sitprepapi.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The inbox presentation contract (docs/epics/notification_ecosystem_gameplan.md,
 * "Proposed Notification Contract"). Every notification answers four questions,
 * and each block owns one of them:
 *
 * <ul>
 *   <li><b>What happened?</b> — {@code visual.badgeKind} (the event mark) +
 *       the row's own title/body.</li>
 *   <li><b>Who or what caused it?</b> — {@link Source} (the entity the row is
 *       FROM: an official source, a household, a group, a person, SitPrep) and
 *       {@link Actor} (the person who did it, when there is one).</li>
 *   <li><b>What should I do now?</b> — {@link Action}s, each with a route the
 *       client can fall back to.</li>
 *   <li><b>Where will this take me?</b> — {@link DeepLink}, a canonical app
 *       route that exists in the FE router.</li>
 * </ul>
 *
 * <p>Built in ONE place — {@link NotificationPresentationBuilder} — for both new
 * rows (stored in {@code notification_log.presentation_json}) and legacy rows
 * (built on read), so there is one shape on the wire.</p>
 *
 * <p>Nothing in here is invented: an actor block exists only when the row names
 * an actor the server can resolve; a thumbnail only when the record holds an
 * image. A missing block is the honest answer and the client renders the
 * source-type fallback.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationPresentation(
        int version,
        String eventKey,
        Source source,
        Actor actor,
        Media media,
        DeepLink deepLink,
        List<Action> actions,
        Visual visual
) {
    public static final int VERSION = 1;

    /** The entity the notification is FROM — drives the avatar. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(
            String entityType,   // NotificationSourceType name
            String entityId,
            String name,
            String avatarUrl,
            String fallbackKind, // what to draw when avatarUrl is null
            String groupType     // HOUSEHOLD / GROUP sources: Group.groupType, so the
                                 // client draws the same type art My Groups does
    ) {}

    /** The person who triggered it. Absent for system and official rows. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(
            String userId,
            String displayName,
            String avatarUrl,
            String profileRoute
    ) {}

    /** Purposeful imagery only: an attached post photo, or a hazard mark. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Media(
            String thumbnailUrl,
            String thumbnailKind, // POST_IMAGE | HAZARD_ICON
            String hazardKey,     // HazardType wire value, for the FE's own illustration
            String alt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeepLink(
            String route,
            String fallbackRoute,
            String recordType,
            String recordId
    ) {}

    /**
     * One inline action. {@code kind}:
     * <ul>
     *   <li>{@code NAVIGATE} — open {@code route}.</li>
     *   <li>{@code STATUS} — set the viewer's own status to {@code statusValue}.</li>
     *   <li>{@code MUTATION} — call the client API named by {@code endpointKey}
     *       with {@code params}; on failure the client opens {@code route}.</li>
     * </ul>
     * Every action carries a {@code route}, so an unknown kind or endpoint key
     * degrades to navigation instead of a dead button.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(
            String id,
            String label,
            String kind,
            String route,
            String tone,        // primary | secondary | danger
            String statusValue, // STATUS only
            String endpointKey, // MUTATION only
            Map<String, String> params
    ) {
        public static Action navigate(String id, String label, String route, String tone) {
            return new Action(id, label, "NAVIGATE", route, tone, null, null, null);
        }

        public static Action status(String id, String label, String statusValue, String route, String tone) {
            return new Action(id, label, "STATUS", route, tone, statusValue, null, null);
        }

        public static Action mutation(String id, String label, String endpointKey,
                                      Map<String, String> params, String route, String tone) {
            return new Action(id, label, "MUTATION", route, tone, null, endpointKey, params);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Visual(
            String avatarKind,  // OFFICIAL_SEAL | HOUSEHOLD | GROUP | USER_PHOTO | SITPREP_SEAL | TOKEN_MEDALLION | HAZARD_MARK | PRACTICE
            String badgeKind,   // NotificationEventType badge
            String priority,    // emergency | attention | routine
            boolean thumbnailPreferred
    ) {}
}
