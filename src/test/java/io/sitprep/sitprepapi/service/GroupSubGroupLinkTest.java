package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.SubGroupLinkDto;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Subgroup link / unlink writes BOTH sides together, behind admin-or-owner on
 * BOTH groups (what the two whole-group PUTs it replaces required between
 * them), and touches nothing but the two id lists.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupSubGroupLinkTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;
    @Mock CheckInRequestService checkInRequestService;

    private GroupService service;
    private Group parent;
    private Group child;

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService,
                notificationService, checkInRequestService);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        parent = group("g-district", "Lincoln District", "boss@probe.app", List.of("boss@probe.app", "both@probe.app"));
        child = group("g-school", "Lincoln Elementary", "principal@probe.app", List.of("principal@probe.app", "both@probe.app"));
        parent.setSubGroupIDs(new ArrayList<>(List.of("g-other")));
        child.setParentGroupIDs(new ArrayList<>());
    }

    private Group group(String id, String name, String owner, List<String> admins) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(name);
        g.setGroupType("School");
        g.setOwnerEmail(owner);
        g.setAdminEmails(admins);
        g.setMemberEmails(new ArrayList<>(admins));
        g.setMemberCount(admins.size());
        g.setDescription(name + " description");
        when(groupRepo.findByGroupId(id)).thenReturn(Optional.of(g));
        return g;
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    @Test
    void allowed_adminOfBoth_linksBothSides() {
        SubGroupLinkDto out = service.linkSubGroup("g-district", "g-school", "both@probe.app");

        assertThat(out.linked()).isTrue();
        assertThat(out.parentSubGroupIDs()).containsExactly("g-other", "g-school");
        assertThat(out.childParentGroupIDs()).containsExactly("g-district");
        assertThat(parent.getSubGroupIDs()).containsExactly("g-other", "g-school");
        assertThat(child.getParentGroupIDs()).containsExactly("g-district");
        // Only the link moved: names, rosters, counts are as they were.
        assertThat(parent.getGroupName()).isEqualTo("Lincoln District");
        assertThat(child.getMemberEmails()).containsExactly("principal@probe.app", "both@probe.app");
        verify(groupRepo).save(parent);
        verify(groupRepo).save(child);
    }

    @Test
    void forbidden_adminOfParentOnly() {
        assertStatus(() -> service.linkSubGroup("g-district", "g-school", "boss@probe.app"), HttpStatus.FORBIDDEN);
        verify(groupRepo, never()).save(any());
        assertThat(parent.getSubGroupIDs()).containsExactly("g-other");
    }

    @Test
    void forbidden_adminOfChildOnly() {
        assertStatus(() -> service.linkSubGroup("g-district", "g-school", "principal@probe.app"), HttpStatus.FORBIDDEN);
        verify(groupRepo, never()).save(any());
    }

    @Test
    void forbidden_memberOfBoth() {
        parent.setMemberEmails(List.of("boss@probe.app", "both@probe.app", "teacher@probe.app"));
        child.setMemberEmails(List.of("principal@probe.app", "both@probe.app", "teacher@probe.app"));
        assertStatus(() -> service.linkSubGroup("g-district", "g-school", "teacher@probe.app"), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.unlinkSubGroup("g-district", "g-school", "teacher@probe.app"), HttpStatus.FORBIDDEN);
    }

    @Test
    void selfLink_is400() {
        assertStatus(() -> service.linkSubGroup("g-school", "g-school", "both@probe.app"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void link_isIdempotent() {
        service.linkSubGroup("g-district", "g-school", "both@probe.app");
        SubGroupLinkDto again = service.linkSubGroup("g-district", "g-school", "both@probe.app");

        assertThat(again.parentSubGroupIDs()).containsExactly("g-other", "g-school");
        assertThat(again.childParentGroupIDs()).containsExactly("g-district");
        // The second call found both sides already linked and wrote nothing.
        verify(groupRepo, times(1)).save(parent);
        verify(groupRepo, times(1)).save(child);
    }

    @Test
    void unlink_removesBothSides_andIsIdempotent() {
        service.linkSubGroup("g-district", "g-school", "both@probe.app");

        SubGroupLinkDto out = service.unlinkSubGroup("g-district", "g-school", "both@probe.app");
        assertThat(out.linked()).isFalse();
        assertThat(out.parentSubGroupIDs()).containsExactly("g-other");
        assertThat(out.childParentGroupIDs()).isEmpty();

        SubGroupLinkDto again = service.unlinkSubGroup("g-district", "g-school", "both@probe.app");
        assertThat(again.parentSubGroupIDs()).containsExactly("g-other");
        verify(groupRepo, times(2)).save(parent); // link + first unlink only
    }

    @Test
    void unlink_clearsAHalfLink() {
        // The old two-PUT path could land one side without the other.
        child.setParentGroupIDs(new ArrayList<>(List.of("g-district")));

        SubGroupLinkDto out = service.unlinkSubGroup("g-district", "g-school", "both@probe.app");

        assertThat(out.childParentGroupIDs()).isEmpty();
        verify(groupRepo, never()).save(parent); // parent never listed the child
        verify(groupRepo).save(child);
    }
}
