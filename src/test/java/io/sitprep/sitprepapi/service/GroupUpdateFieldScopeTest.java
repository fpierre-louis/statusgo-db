package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BE-01 step 1 / AD-06: the whole-group PUT edits the group's details and
 * nothing an admin must not own. Before 2026-10-13 any admin could PUT
 * {@code ownerEmail} = themselves and become OWNER, rewrite the roster, flip
 * the alert, write sub/parent links past the no-loop walk, and retype a
 * household.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupUpdateFieldScopeTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;
    @Mock CheckInRequestService checkInRequestService;

    private GroupService service;
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService,
                notificationService, checkInRequestService);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Group stored(String type) {
        Group g = new Group();
        g.setGroupId("g-1");
        g.setGroupName("Maple Street");
        g.setGroupType(type);
        g.setPrivacy("Private");
        g.setAlert("Not Active");
        g.setCreatedAt(CREATED);
        g.setLastUpdatedBy("owner@x.com");
        g.setOwnerEmail("owner@x.com");
        g.setOwnerName("Olive Owner");
        g.setAdminEmails(List.of("owner@x.com", "admin@x.com"));
        g.setMemberEmails(List.of("owner@x.com", "admin@x.com", "m@x.com"));
        g.setPendingMemberEmails(List.of("p@x.com"));
        g.setSubGroupIDs(List.of("child-1"));
        g.setParentGroupIDs(List.of());
        g.setMemberCount(3);
        when(groupRepo.findByGroupId("g-1")).thenReturn(Optional.of(g));
        return g;
    }

    /** What a hostile admin sends: every server-owned field rewritten. */
    private Group hostileBody(String type) {
        Group b = new Group();
        b.setGroupName("Renamed");
        b.setDescription("New description");
        b.setGroupType(type);
        b.setPrivacy("Private");
        b.setAlert("Active");
        b.setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"));
        b.setLastUpdatedBy("admin@x.com");
        b.setOwnerEmail("admin@x.com");
        b.setOwnerName("Admin Takeover");
        b.setAdminEmails(List.of("admin@x.com", "stranger@x.com"));
        b.setMemberEmails(List.of("admin@x.com", "stranger@x.com", "victim@x.com"));
        b.setPendingMemberEmails(List.of());
        b.setSubGroupIDs(List.of("child-1", "loop-target"));
        b.setParentGroupIDs(List.of("child-1"));
        b.setMemberCount(99);
        return b;
    }

    @Test
    void adminPut_cannotTouchOwnerRosterAlertLinksOrTimestamps() {
        stored("HOA/Neighborhood");

        Group out = service.updateGroupByPublicId("g-1", hostileBody("HOA/Neighborhood"));

        // The editable details did change.
        assertThat(out.getGroupName()).isEqualTo("Renamed");
        assertThat(out.getDescription()).isEqualTo("New description");
        // Everything else is exactly as stored.
        assertThat(out.getOwnerEmail()).isEqualTo("owner@x.com");
        assertThat(out.getOwnerName()).isEqualTo("Olive Owner");
        assertThat(out.getAdminEmails()).containsExactly("owner@x.com", "admin@x.com");
        assertThat(out.getMemberEmails()).containsExactly("owner@x.com", "admin@x.com", "m@x.com");
        assertThat(out.getPendingMemberEmails()).containsExactly("p@x.com");
        assertThat(out.getAlert()).isEqualTo("Not Active");
        assertThat(out.getAlertActivatedAt()).isNull();
        assertThat(out.getSubGroupIDs()).containsExactly("child-1");
        assertThat(out.getParentGroupIDs()).isEmpty();
        assertThat(out.getCreatedAt()).isEqualTo(CREATED);
        assertThat(out.getLastUpdatedBy()).isEqualTo("owner@x.com");
        assertThat(out.getMemberCount()).isEqualTo(3);
        // No alert frame, no check-in asks, no pushes to an injected roster.
        verifyNoInteractions(ws, checkInRequestService, notificationService);
    }

    @Test
    void household_cannotBeRetyped() {
        stored("Household");
        Group out = service.updateGroupByPublicId("g-1", hostileBody("Business"));
        assertThat(out.getGroupType()).isEqualTo("Household");
    }

    @Test
    void orgGroup_cannotBecomeAHousehold() {
        stored("Church");
        Group out = service.updateGroupByPublicId("g-1", hostileBody("Household"));
        assertThat(out.getGroupType()).isEqualTo("Church");
    }

    @Test
    void orgGroup_canStillChangeItsOrgType() {
        stored("Church");
        Group out = service.updateGroupByPublicId("g-1", hostileBody("Religious Organization"));
        assertThat(out.getGroupType()).isEqualTo("Religious Organization");
    }
}
