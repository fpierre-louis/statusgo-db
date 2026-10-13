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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code renameGroup} changes the name and NOTHING else, behind the same gate
 * as the whole-group PUT it replaces (admin or owner).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupRenameTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;
    @Mock CheckInRequestService checkInRequestService;

    private GroupService service;

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService,
                notificationService, checkInRequestService);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Group stored() {
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupName("Smith Household");
        g.setGroupType("Household");
        g.setAlert("Active");
        g.setDescription("Our home");
        g.setLastUpdatedBy("mom@probe.app");
        g.setOwnerEmail("mom@probe.app");
        g.setAdminEmails(List.of("mom@probe.app", "dad@probe.app"));
        g.setMemberEmails(List.of("mom@probe.app", "dad@probe.app", "kid@probe.app"));
        g.setMemberCount(3);
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(g));
        return g;
    }

    @Test
    void allowed_admin_renamesAndOnlyTheNameChanges() {
        Group g = stored();

        Group out = service.renameGroup("hh-1", "  The Smiths  ", "DAD@probe.app");

        assertThat(out.getGroupName()).isEqualTo("The Smiths");
        assertThat(out.getUpdatedAt()).isNotNull();
        // Everything a whole-object PUT would have replayed is untouched.
        assertThat(out.getAlert()).isEqualTo("Active");
        assertThat(out.getDescription()).isEqualTo("Our home");
        assertThat(out.getMemberEmails()).hasSize(3);
        assertThat(out.getMemberCount()).isEqualTo(3);
        assertThat(out.getLastUpdatedBy()).as("alert frame reads this as the check-in starter")
                .isEqualTo("mom@probe.app");
        // No broadcast: a rename is not an alert transition.
        verifyNoInteractions(ws);
        verify(groupRepo).save(g);
    }

    @Test
    void allowed_owner() {
        stored();
        assertThat(service.renameGroup("hh-1", "Casa", "mom@probe.app").getGroupName()).isEqualTo("Casa");
    }

    @Test
    void forbidden_plainMember() {
        stored();
        assertThatThrownBy(() -> service.renameGroup("hh-1", "Kid's House", "kid@probe.app"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(groupRepo, never()).save(any());
    }

    @Test
    void forbidden_stranger() {
        stored();
        assertThatThrownBy(() -> service.renameGroup("hh-1", "Mine", "stranger@probe.app"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void blankOrTooLong_is400() {
        stored();
        for (String bad : new String[]{null, "", "   ", "x".repeat(GroupService.GROUP_NAME_MAX + 1)}) {
            assertThatThrownBy(() -> service.renameGroup("hh-1", bad, "mom@probe.app"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(service.renameGroup("hh-1", "x".repeat(GroupService.GROUP_NAME_MAX), "mom@probe.app")
                .getGroupName()).hasSize(GroupService.GROUP_NAME_MAX);
    }

    @Test
    void idempotent_sameNameWritesNothing() {
        stored();
        Group out = service.renameGroup("hh-1", " Smith Household ", "mom@probe.app");
        assertThat(out.getGroupName()).isEqualTo("Smith Household");
        verify(groupRepo, never()).save(any());
    }
}
