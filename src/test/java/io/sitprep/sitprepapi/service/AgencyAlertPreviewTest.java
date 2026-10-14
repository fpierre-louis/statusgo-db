package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.AgencyAlertPreviewDto;
import io.sitprep.sitprepapi.repo.AgencyAlertRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Group page AG-07: the composer's reach preview. A COUNT only, never emails;
 * the same gate as a send; and the same normalisation as the send snapshot,
 * so the number the operator sees is the number the send records.
 */
@ExtendWith(MockitoExtension.class)
class AgencyAlertPreviewTest {

    @Mock GroupRepo groupRepo;
    @Mock AgencyAlertRepo agencyAlertRepo;
    @Mock PostRepo postRepo;
    @Mock AgencyAuthorizationService authorizationService;

    private AgencyAlertService service() {
        return new AgencyAlertService(groupRepo, agencyAlertRepo, postRepo, authorizationService);
    }

    private static UserInfo user(String email) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        return u;
    }

    @Test
    void previewCountsDistinctRecipientsAndCarriesNoEmail() throws Exception {
        Group group = new Group();
        group.setGroupId("g-city");
        when(groupRepo.findByGroupId("g-city")).thenReturn(Optional.of(group));
        when(authorizationService.recipients(eq(group), any())).thenReturn(List.of(
                user(" Resident@Example.com "),
                user("resident@example.com"),   // same person, different case
                user("second@example.com"),
                user("   "),                     // blank: not a recipient
                user(null)));

        AgencyAlertPreviewDto dto = service().preview("g-city", "admin@city.gov");

        assertThat(dto.recipientCount()).isEqualTo(2);
        assertThat(dto.recencyDays()).isEqualTo(30);
        verify(authorizationService).requireAgencyPostingAllowed(group, "admin@city.gov");

        // Count only: the record has exactly these two components, and the wire
        // form carries no address of any recipient.
        assertThat(Arrays.stream(AgencyAlertPreviewDto.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("recipientCount", "recencyDays");
        String json = new ObjectMapper().writeValueAsString(dto);
        assertThat(json).doesNotContain("@").doesNotContain("example.com");

        // A preview writes nothing: no alert row, no post.
        verifyNoInteractions(agencyAlertRepo, postRepo);
    }

    @Test
    void previewUsesTheSendGateAndResolvesNoOneWhenRefused() {
        Group group = new Group();
        group.setGroupId("g-city");
        when(groupRepo.findByGroupId("g-city")).thenReturn(Optional.of(group));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Agency is not provisioned for SEND_AREA_ALERTS"))
                .when(authorizationService).requireAgencyPostingAllowed(group, "member@city.gov");

        assertThatThrownBy(() -> service().preview("g-city", "member@city.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(authorizationService, never()).recipients(any(), any());
        verifyNoInteractions(agencyAlertRepo, postRepo);
    }

    @Test
    void previewOfAnUnknownGroupIs404() {
        when(groupRepo.findByGroupId("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().preview("nope", "admin@city.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(authorizationService, agencyAlertRepo, postRepo);
    }
}
