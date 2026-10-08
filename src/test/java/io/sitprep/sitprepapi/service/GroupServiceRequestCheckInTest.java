package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.resource.GroupResource;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Household drawer gameplan §5.1 — "Ask everyone" is about to become the
 * drawer's one-tap top control, so in a household it asks everyone BUT the
 * caller, once per 10 minutes per household, and a second ask learns who sent
 * the first. Org groups are untouched: no cooldown, whole roster recorded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupServiceRequestCheckInTest {

    private static final String DIONE = "dione@x.com";
    private static final String MAYA = "maya@x.com";
    private static final String JONAH = "jonah@x.com";

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
        UserInfo dione = new UserInfo();
        dione.setUserEmail(DIONE);
        dione.setUserFirstName("Dione");
        when(userInfoRepo.findByUserEmailIgnoreCase(DIONE)).thenReturn(Optional.of(dione));
        when(householdEventService.activeCheckInRequest(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Group group(String id, String type) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id);
        g.setGroupType(type);
        g.setOwnerEmail(DIONE);
        g.setAdminEmails(List.of(DIONE));
        g.setMemberEmails(List.of(DIONE, MAYA, JONAH));
        when(groupRepo.findByGroupId(id)).thenReturn(Optional.of(g));
        return g;
    }

    @SuppressWarnings("unchecked")
    private Collection<String> recordedAsked(Group g) {
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(checkInRequestService).recordAsked(same(g), asked.capture(), eq(MAYA));
        return asked.getValue();
    }

    @Test
    void aHouseholdAskExcludesTheCallerAndEmitsAnEvent() {
        Group hh = group("hh-1", "Household");

        GroupService.AskEveryoneResult result = service.requestCheckIn("hh-1", MAYA);

        assertThat(result.sent()).isTrue();
        assertThat(recordedAsked(hh)).containsExactlyInAnyOrder(DIONE, JONAH);
        verify(householdEventService).recordCheckInRequest(eq("hh-1"), eq(MAYA),
                argThat(c -> c.containsAll(List.of(DIONE, JONAH)) && !c.contains(MAYA)), any());
        verify(notificationService).notifyCheckInRequest(same(hh), eq(MAYA), any());
    }

    @Test
    void aSecondHouseholdAskInsideTheCooldownSendsNothingAndNamesWhoAsked() {
        group("hh-1", "Household");
        HouseholdEvent first = new HouseholdEvent();
        first.setHouseholdId("hh-1");
        first.setKind(HouseholdEventService.KIND_CHECKIN_REQUEST);
        first.setActorEmail(DIONE);
        Instant at = Instant.now().minus(Duration.ofMinutes(3));
        first.setAt(at);
        when(householdEventService.activeCheckInRequest("hh-1")).thenReturn(Optional.of(first));

        GroupService.AskEveryoneResult result = service.requestCheckIn("hh-1", MAYA);

        assertThat(result.sent()).isFalse();
        assertThat(result.requestedByName()).isEqualTo("Dione");
        assertThat(result.requestedByEmail()).isEqualTo(DIONE);
        assertThat(result.requestedAt()).isEqualTo(at);
        assertThat(result.retryAt()).isEqualTo(at.plus(Duration.ofMinutes(10)));
        verify(checkInRequestService, never()).recordAsked(any(), any(), any());
        verify(notificationService, never()).notifyCheckInRequest(any(), any(), any());
        verify(householdEventService, never()).recordCheckInRequest(any(), any(), any(), any());
    }

    @Test
    void anOrgGroupIsUnchanged_noCooldownAndTheWholeRosterRecorded() {
        Group org = group("org-1", "HOA/Neighborhood");
        org.setAdminEmails(List.of(DIONE, MAYA));
        // Even with a household-style record standing, an org group never reads it.
        when(householdEventService.activeCheckInRequest("org-1"))
                .thenReturn(Optional.of(new HouseholdEvent()));

        GroupService.AskEveryoneResult result = service.requestCheckIn("org-1", MAYA);

        assertThat(result.sent()).isTrue();
        assertThat(recordedAsked(org)).containsExactly(DIONE, MAYA, JONAH);
        verify(householdEventService, never()).activeCheckInRequest(any());
        verify(householdEventService, never()).recordCheckInRequest(any(), any(), any(), any());
    }

    // ── the wire: 429 + Retry-After + who asked ──────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void theResourceAnswers429WithRetryAfterAndRequestedBy() {
        GroupService mockService = mock(GroupService.class);
        GroupResource resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", mockService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                MAYA, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        Instant at = Instant.now().minus(Duration.ofMinutes(4));
        when(mockService.requestCheckIn("hh-1", MAYA)).thenReturn(new GroupService.AskEveryoneResult(
                false, DIONE, "Dione", at, at.plus(Duration.ofMinutes(10))));

        ResponseEntity<?> res = resource.requestCheckIn("hh-1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        long retry = Long.parseLong(res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retry).isBetween(350L, 360L);
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("requestedAt", at);
        assertThat((Map<String, Object>) body.get("requestedBy"))
                .containsEntry("name", "Dione").containsEntry("email", DIONE);

        when(mockService.requestCheckIn("hh-1", MAYA)).thenReturn(GroupService.AskEveryoneResult.SENT);
        assertThat(resource.requestCheckIn("hh-1").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
