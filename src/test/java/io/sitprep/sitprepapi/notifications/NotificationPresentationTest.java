package io.sitprep.sitprepapi.notifications;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.notifications.NotificationPresentation.Action;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The notification taxonomy + presentation contract (EXEC-N1). One class on
 * purpose (owner: lean verification) — registry coverage, the no-policy list,
 * representative builds per source type, legacy normalization, and the route
 * guarantee checked against the FE router's own patterns.
 */
class NotificationPresentationTest {

    /** Every type string an emitter in src/main sends (emitter audit, 2026-10-03). */
    static final List<String> EMITTED_TYPES = List.of(
            "hazard_alert", "alert", "group_status", "check_in_request", "checkin_reminder",
            "checkin_auto_ended", "plan_activation", "plan_activation_ended", "task_assigned",
            "task_reminder", "gobag_expiry", "guest_expiry_reminder", "pending_member", "new_member",
            "post_notification", "post_mention", "comment_on_post", "comment_on_task",
            "reply_on_followed", "follow", "follow_accepted", "dm_message", "weekly_drill_kickoff",
            "weekly_drill_nudge", "household_ritual_reminder", "token_unlocked");

    /**
     * Route patterns mounted in Status Now/src/App.js. A canonical route that
     * matches none of these would strand a tap — the deep-link guarantee.
     */
    static final List<Pattern> FE_ROUTES = List.of(
            "^/hazards(\\?alert=[^&]+)?$", "^/community/posts/[^/?]+$", "^/community$",
            "^/Linked/lg/4D-FwtX/[^/?]+(\\?postId=[^&]+)?$", "^/my-groups$",
            "^/household/h/4D-FwtX/household/[^/?]+/(chat|family|plan|timeline|about)$",
            "^/household/[^/]+/invite-requests/[^/?]+$", "^/work-orders(/[^/?]+)?$",
            "^/deployedplan\\?activationId=[^&]+$", "^/profile(/[^/?]+)?$", "^/profile\\?tab=tokens$",
            "^/home(\\?challenge=open)?$", "^/me/tasks$", "^/go-bag$", "^/login$", "^/notifications$"
    ).stream().map(Pattern::compile).toList();

    UserInfoRepo users;
    GroupRepo groups;
    NotificationPresentationBuilder builder;

    @BeforeEach
    void setUp() {
        users = mock(UserInfoRepo.class);
        groups = mock(GroupRepo.class);
        builder = new NotificationPresentationBuilder(users, groups, mock(GroupPostRepo.class), mock(PostRepo.class));

        UserInfo maya = new UserInfo();
        maya.setId("u_maya");
        maya.setUserFirstName("Maya");
        maya.setUserLastName("Chen");
        maya.setProfileImageUrl("https://cdn.example.com/maya.jpg");
        when(users.findById("u_maya")).thenReturn(Optional.of(maya));
        when(users.findAllById(any())).thenReturn(List.of(maya));

        Group household = new Group();
        household.setGroupId("hh1");
        household.setGroupName("Chen Household");
        household.setGroupType("Household");
        when(groups.findById("hh1")).thenReturn(Optional.of(household));
        when(groups.findAllById(any())).thenReturn(List.of(household));
    }

    // ── Taxonomy ─────────────────────────────────────────────────────────

    @Test
    void everyEmittedTypeIsRegistered_andOnlyRecordedTypesSkipPolicy() {
        for (String type : EMITTED_TYPES) {
            assertThat(NotificationEventType.forType(type)).as(type).isNotEqualTo(NotificationEventType.UNKNOWN);
        }
        // policy == null is a DECISION (EXEC-N1 out-of-scope). Growing this set
        // means a new type rides the no-policy push path — make that a choice.
        Set<NotificationEventType> noPolicy = EnumSet.noneOf(NotificationEventType.class);
        for (NotificationEventType e : NotificationEventType.values()) {
            if (e.policy() == null) noPolicy.add(e);
        }
        assertThat(noPolicy).containsExactlyInAnyOrder(
                NotificationEventType.HAZARD_ALERT, NotificationEventType.AGENCY_ALERT,
                NotificationEventType.TASK_REMINDER, NotificationEventType.GO_BAG_EXPIRY,
                NotificationEventType.GUEST_EXPIRY, NotificationEventType.UNKNOWN);
        // The household flip is type "alert" + the household category.
        assertThat(NotificationEventType.resolve("alert", "GROUP_ALERT_HOUSEHOLD", "hh1"))
                .isEqualTo(NotificationEventType.GROUP_ALERT_HOUSEHOLD);
        assertThat(NotificationEventType.resolve("hazard_alert", null, "agency-alert:42"))
                .isEqualTo(NotificationEventType.AGENCY_ALERT);
    }

    // ── Builds, one per source type ──────────────────────────────────────

    @Test
    void householdAlert_attributesTheHousehold_andOffersStatusActions() {
        NotificationPresentation p = builder.build(row("alert", "GROUP_ALERT_HOUSEHOLD", "hh1",
                "/status-now", null, "u_maya"));
        assertThat(p.source().entityType()).isEqualTo("HOUSEHOLD");
        assertThat(p.source().name()).isEqualTo("Chen Household");
        assertThat(p.actor().displayName()).isEqualTo("Maya Chen");
        assertThat(p.visual().priority()).isEqualTo("emergency");
        assertThat(p.deepLink().route()).isEqualTo("/household/h/4D-FwtX/household/hh1/family");
        assertThat(p.actions()).extracting(Action::kind, Action::statusValue)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("STATUS", "SAFE"),
                        org.assertj.core.groups.Tuple.tuple("STATUS", "HELP"));
        assertRoutable(p);
    }

    @Test
    void nwsWarning_isOfficial_focusesTheAlert_andCarriesAHazardMark() {
        NotificationPresentation p = builder.build(row("hazard_alert", null, "NWS-abc123", "/hazards",
                "{\"source\":\"NWS\",\"event\":\"Tornado Warning\",\"severity\":\"Extreme\"}", null));
        assertThat(p.source().entityType()).isEqualTo("OFFICIAL_ALERT");
        assertThat(p.source().name()).isEqualTo("National Weather Service");
        assertThat(p.visual().avatarKind()).isEqualTo("OFFICIAL_SEAL");
        assertThat(p.visual().priority()).isEqualTo("emergency");
        assertThat(p.media().hazardKey()).isEqualTo("tornado");
        assertThat(p.media().thumbnailUrl()).isNull(); // no invented snapshot
        assertThat(p.deepLink().route()).isEqualTo("/hazards?alert=NWS-abc123");
        assertThat(p.actor()).isNull();
        assertRoutable(p);
    }

    @Test
    void socialAndMembershipRows_routeToTheirRecord() {
        NotificationPresentation follow = builder.build(row("follow", "FOLLOW", "u_maya",
                "/profile/u_maya", null, "u_maya"));
        assertThat(follow.source().entityType()).isEqualTo("USER");
        assertThat(follow.source().avatarUrl()).isEqualTo("https://cdn.example.com/maya.jpg");
        assertRoutable(follow);

        // Legacy alias in an old emitter → canonical thread route.
        NotificationPresentation reply = builder.build(row("comment_on_task", null, "77",
                "/community/tasks/77", null, "u_maya"));
        assertThat(reply.deepLink().route()).isEqualTo("/community/posts/77");
        assertRoutable(reply);

        NotificationPresentation invite = builder.build(row("pending_member", null, "req9",
                "/household/hh1/invite-requests/req9", null, "u_maya"));
        assertThat(invite.source().name()).isEqualTo("Chen Household");
        assertThat(invite.actions()).extracting(Action::endpointKey)
                .containsExactly("approveHouseholdInvite", "declineHouseholdInvite");
        assertThat(invite.actions().get(0).params()).containsEntry("requestId", "req9");
        assertRoutable(invite);

        NotificationPresentation token = builder.build(row("token_unlocked", "TOKEN_UNLOCKED", null,
                "/profile?tab=tokens", "{\"tokenKey\":\"meeting_place\"}", null));
        assertThat(token.source().entityType()).isEqualTo("TOKEN");
        assertRoutable(token);
    }

    @Test
    void unknownType_fallsBackToTheInbox_neverNull() {
        NotificationPresentation p = builder.build(row("mystery", null, null, null, null, null));
        assertThat(p.eventKey()).isEqualTo("UNKNOWN");
        assertThat(p.deepLink().route()).isEqualTo("/notifications");
        assertThat(p.source().fallbackKind()).isEqualTo("SITPREP_SEAL");
    }

    // ── Storage + legacy ─────────────────────────────────────────────────

    @Test
    void storedPresentationRoundTrips_andLegacyRowsAreBuiltOnRead() {
        NotificationLog stored = row("alert", "GROUP_ALERT_HOUSEHOLD", "hh1", "/status-now", null, "u_maya");
        stored.setId(1L);
        Map<String, Object> json = builder.buildJson(stored);
        stored.setPresentationJson(json);
        assertThat(NotificationPresentationBuilder.fromMap(json)).isEqualTo(builder.build(stored));

        NotificationLog legacy = row("new_member", null, "hh1", "/household/h/4D-FwtX/household/hh1", null, null);
        legacy.setId(2L);
        Map<Long, NotificationPresentation> page = builder.forRows(List.of(stored, legacy));
        assertThat(page.get(1L).source().name()).isEqualTo("Chen Household");
        assertThat(page.get(2L).source().name()).isEqualTo("Chen Household");
        assertThat(page.get(2L).deepLink().route()).isEqualTo("/household/h/4D-FwtX/household/hh1/family");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static NotificationLog row(String type, String category, String ref, String target,
                                       String additionalData, String actorUserId) {
        NotificationLog r = new NotificationLog("viewer@example.com", type, null, "Title", "Body",
                ref, target, additionalData, Instant.now(), true, null);
        r.setCategory(category);
        r.setActorUserId(actorUserId);
        return r;
    }

    private static void assertRoutable(NotificationPresentation p) {
        assertThat(FE_ROUTES).as("deepLink " + p.deepLink().route())
                .anyMatch(rx -> rx.matcher(p.deepLink().route()).matches());
        for (Action a : p.actions()) {
            assertThat(FE_ROUTES).as(a.id() + " " + a.route()).anyMatch(rx -> rx.matcher(a.route()).matches());
        }
    }
}
