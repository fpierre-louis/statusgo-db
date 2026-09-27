package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.AgencyAlert;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.AgencyAlertResultDto;
import io.sitprep.sitprepapi.dto.SendAgencyAlertRequest;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgencyAlertServiceTest {

    @Mock GroupRepo groupRepo;
    @Mock AgencyAlertRepo agencyAlertRepo;
    @Mock PostRepo postRepo;
    @Mock AgencyAuthorizationService authorizationService;
    @Mock NotificationService notificationService;

    @Test
    void bodyOnlyAlertIsRejectedBeforeAnyRecordOrPushIsCreated() {
        Group group = new Group();
        group.setGroupId("g-city");
        when(groupRepo.findByGroupId("g-city")).thenReturn(Optional.of(group));
        AgencyAlertService service = new AgencyAlertService(
                groupRepo, agencyAlertRepo, postRepo, authorizationService);

        SendAgencyAlertRequest request = new SendAgencyAlertRequest(
                "   ", "Boil water until further notice", "emergency", null, "key-1", null);

        assertThatThrownBy(() -> service.send("g-city", "admin@city.gov", request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("title is required");
        verifyNoInteractions(agencyAlertRepo, postRepo, notificationService);
    }

    @Test
    void sendCommitsNormalizedRecipientSnapshotAndReturnsQueued() {
        Group group = new Group();
        group.setGroupId("g-city");
        group.setJurisdictionZips(List.of("80202"));
        when(groupRepo.findByGroupId("g-city")).thenReturn(Optional.of(group));

        UserInfo first = new UserInfo();
        first.setUserEmail(" Resident@Example.com ");
        UserInfo duplicate = new UserInfo();
        duplicate.setUserEmail("resident@example.com");
        UserInfo second = new UserInfo();
        second.setUserEmail("second@example.com");
        when(authorizationService.recipients(eq(group), any())).thenReturn(List.of(first, duplicate, second));
        when(agencyAlertRepo.saveAndFlush(any(AgencyAlert.class))).thenAnswer(invocation -> {
            AgencyAlert alert = invocation.getArgument(0);
            alert.setId(41L);
            return alert;
        });
        when(postRepo.save(any(Post.class))).thenAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            post.setId(99L);
            return post;
        });
        when(agencyAlertRepo.save(any(AgencyAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgencyAlertService service = new AgencyAlertService(
                groupRepo, agencyAlertRepo, postRepo, authorizationService);
        AgencyAlertResultDto result = service.send("g-city", "admin@city.gov",
                new SendAgencyAlertRequest("Boil water", "Use bottled water", "emergency",
                        List.of("80202"), "key-2", null));

        assertThat(result.status()).isEqualTo("QUEUED");
        assertThat(result.recipientCount()).isEqualTo(2);
        assertThat(result.deliveredCount()).isZero();
        var alertCaptor = org.mockito.ArgumentCaptor.forClass(AgencyAlert.class);
        verify(agencyAlertRepo).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getRecipientEmails())
                .containsExactly("resident@example.com", "second@example.com");
        assertThat(alertCaptor.getValue().getDispatchStatus()).isEqualTo(AgencyAlert.DispatchStatus.QUEUED);
        verifyNoInteractions(notificationService);
    }

    @Test
    void historyRequiresAgencyAdminBeforeReadingAlertRows() {
        Group group = new Group();
        group.setGroupId("g-city");
        when(groupRepo.findByGroupId("g-city")).thenReturn(Optional.of(group));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin required"))
                .when(authorizationService).requireAgencyAdmin(group, "staff@city.gov");
        AgencyAlertService service = new AgencyAlertService(
                groupRepo, agencyAlertRepo, postRepo, authorizationService);

        assertThatThrownBy(() -> service.list("g-city", "staff@city.gov", 5))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(agencyAlertRepo, never()).findByPublisherGroupIdOrderByCreatedAtDesc(anyString(), any());
    }
}
