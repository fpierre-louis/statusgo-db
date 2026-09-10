package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.LocationSharing;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.LiveLocationPoint;
import io.sitprep.sitprepapi.domain.LiveLocationSession;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationPointRequest;
import io.sitprep.sitprepapi.dto.LiveLocationDtos.StartLiveLocationSessionRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.LiveLocationPointRepo;
import io.sitprep.sitprepapi.repo.LiveLocationSessionRepo;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LiveLocationServiceTest {

    private static final String GROUP_ID = "g-1";
    private static final String ACTOR = "member@example.com";
    private static final String STRANGER = "stranger@example.com";

    private LiveLocationSessionRepo sessionRepo;
    private LiveLocationPointRepo pointRepo;
    private GroupRepo groupRepo;
    private UserInfoRepo userInfoRepo;
    private PlanActivationRepo activationRepo;
    private HouseholdResolver householdResolver;
    private WebSocketMessageSender ws;
    private LiveLocationService service;

    @BeforeEach
    void setUp() {
        sessionRepo = mock(LiveLocationSessionRepo.class);
        pointRepo = mock(LiveLocationPointRepo.class);
        groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        activationRepo = mock(PlanActivationRepo.class);
        householdResolver = mock(HouseholdResolver.class);
        ws = mock(WebSocketMessageSender.class);
        service = new LiveLocationService(
                sessionRepo, pointRepo, groupRepo, userInfoRepo, activationRepo, householdResolver, ws);

        when(sessionRepo.save(any(LiveLocationSession.class))).thenAnswer(inv -> {
            LiveLocationSession s = inv.getArgument(0);
            if (s.getId() == null) s.setId("session-1");
            return s;
        });
        when(pointRepo.save(any(LiveLocationPoint.class))).thenAnswer(inv -> {
            LiveLocationPoint p = inv.getArgument(0);
            p.setId(7L);
            if (p.getCapturedAt() == null) p.setCapturedAt(Instant.now());
            return p;
        });
        when(userInfoRepo.save(any(UserInfo.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void startRejectsNeverEvenWhenTheGroupIsActive() {
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.NEVER)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(true, ACTOR)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR, request()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void startAllowsCheckInOnlyOnlyDuringActiveAlert() {
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.CHECK_IN_ONLY)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(false, ACTOR)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR, request()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());

        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(true, ACTOR)));
        assertDoesNotThrow(() -> service.start(ACTOR, request()));
    }

    @Test
    void startRejectsGroupsTheCallerDoesNotBelongTo() {
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(true, STRANGER)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR, request()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void startCanResolveActiveActivationToOwnersHousehold() {
        PlanActivation activation = activation();
        when(activationRepo.findById("activation-1")).thenReturn(Optional.of(activation));
        when(householdResolver.baseHouseholdIdFor("owner@example.com")).thenReturn(GROUP_ID);
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.CHECK_IN_ONLY)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(false, ACTOR)));

        assertDoesNotThrow(() -> service.start(ACTOR,
                new StartLiveLocationSessionRequest(List.of(), 30, "activation-1", null)));
    }

    @Test
    void startRejectsEndedActivationContext() {
        PlanActivation activation = activation();
        activation.setEndedAt(Instant.now());
        when(activationRepo.findById("activation-1")).thenReturn(Optional.of(activation));
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.start(ACTOR,
                        new StartLiveLocationSessionRequest(List.of(), 30, "activation-1", null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(sessionRepo, never()).save(any());
    }

    @Test
    void startReturnsScopedUploadTokenAndStoresOnlyHash() {
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(true, ACTOR)));

        var dto = service.start(ACTOR, request());

        assertNotNull(dto.uploadToken());
        assertFalse(dto.uploadToken().isBlank());
        verify(sessionRepo).save(argThat(session ->
                session.getUploadTokenHash() != null
                        && !session.getUploadTokenHash().equals(dto.uploadToken())));
    }

    @Test
    void updatePointStoresLatestUserLocationAndBroadcastsToVisibleGroups() {
        LiveLocationSession session = activeSession();
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(false, ACTOR)));

        service.updatePoint(ACTOR, session.getId(),
                new LiveLocationPointRequest(40.76, -111.89, 9.0, 1.0, 180.0, Instant.now()));

        verify(userInfoRepo).save(argThat(u ->
                Double.valueOf(40.76).equals(u.getLastKnownLat())
                        && Double.valueOf(-111.89).equals(u.getLastKnownLng())
                        && u.getLastKnownLocationAt() != null));
        verify(ws).sendGroupMemberLocation(eq(GROUP_ID), any());
    }

    @Test
    void updatePointAcceptsSessionUploadToken() {
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group(true, ACTOR)));
        var dto = service.start(ACTOR, request());
        ArgumentCaptor<LiveLocationSession> sessionCaptor = ArgumentCaptor.forClass(LiveLocationSession.class);
        verify(sessionRepo).save(sessionCaptor.capture());
        LiveLocationSession session = sessionCaptor.getValue();
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));

        service.updatePointWithUploadToken(session.getId(), dto.uploadToken(),
                new LiveLocationPointRequest(40.76, -111.89, 9.0, 1.0, 180.0, Instant.now()));

        verify(pointRepo).save(any(LiveLocationPoint.class));
        verify(userInfoRepo, atLeastOnce()).save(any(UserInfo.class));
    }

    @Test
    void updatePointRejectsInvalidUploadToken() {
        LiveLocationSession session = activeSession();
        session.setUploadTokenHash("not-a-match");
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updatePointWithUploadToken(session.getId(), "wrong-token",
                        new LiveLocationPointRequest(40.76, -111.89, null, null, null, Instant.now())));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verify(pointRepo, never()).save(any());
    }

    @Test
    void listForGroupRequiresMembershipAndFreshPoints() {
        Group group = group(false, ACTOR);
        LiveLocationSession session = activeSession();
        LiveLocationPoint fresh = point(Instant.now());
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group));
        when(sessionRepo.findActiveForGroup(eq(GROUP_ID), any(Instant.class))).thenReturn(List.of(session));
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));
        when(pointRepo.findTopBySessionIdOrderByCapturedAtDesc(session.getId())).thenReturn(Optional.of(fresh));

        assertEquals(1, service.listForGroup(ACTOR, GROUP_ID).size());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listForGroup(STRANGER, GROUP_ID));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void listForGroupSuppressesStalePoints() {
        Group group = group(false, ACTOR);
        LiveLocationSession session = activeSession();
        when(groupRepo.findByGroupId(GROUP_ID)).thenReturn(Optional.of(group));
        when(sessionRepo.findActiveForGroup(eq(GROUP_ID), any(Instant.class))).thenReturn(List.of(session));
        when(userInfoRepo.findByUserEmailIgnoreCase(ACTOR)).thenReturn(Optional.of(user(LocationSharing.ALWAYS)));
        when(pointRepo.findTopBySessionIdOrderByCapturedAtDesc(session.getId()))
                .thenReturn(Optional.of(point(Instant.now().minus(20, ChronoUnit.MINUTES))));

        assertTrue(service.listForGroup(ACTOR, GROUP_ID).isEmpty());
    }

    @Test
    void listMineReturnsRecentSessionsWithoutUploadTokens() {
        LiveLocationSession session = activeSession();
        session.setUploadTokenHash("stored-token-hash");
        when(sessionRepo.findTop25ByUserEmailIgnoreCaseOrderByStartedAtDesc(ACTOR))
                .thenReturn(List.of(session));

        var rows = service.listMine(ACTOR);

        assertEquals(1, rows.size());
        assertEquals(session.getId(), rows.get(0).id());
        assertNull(rows.get(0).uploadToken());
        verify(sessionRepo).findTop25ByUserEmailIgnoreCaseOrderByStartedAtDesc(ACTOR);
    }

    private StartLiveLocationSessionRequest request() {
        return new StartLiveLocationSessionRequest(List.of(GROUP_ID), 30, null, null);
    }

    private UserInfo user(String mode) {
        UserInfo u = new UserInfo();
        u.setUserEmail(ACTOR);
        Map<String, String> prefs = new HashMap<>();
        prefs.put(GROUP_ID, mode);
        u.setGroupLocationSharing(prefs);
        return u;
    }

    private Group group(boolean active, String memberEmail) {
        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setGroupType("Neighborhood");
        g.setAlert(active ? "Active" : "Calm");
        g.setMemberEmails(List.of(memberEmail));
        return g;
    }

    private LiveLocationSession activeSession() {
        LiveLocationSession s = new LiveLocationSession();
        s.setId("session-1");
        s.setUserEmail(ACTOR);
        s.setGroupIds(new java.util.LinkedHashSet<>(List.of(GROUP_ID)));
        s.setStartedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        s.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        return s;
    }

    private LiveLocationPoint point(Instant capturedAt) {
        LiveLocationPoint p = new LiveLocationPoint();
        p.setSessionId("session-1");
        p.setUserEmail(ACTOR);
        p.setLat(40.76);
        p.setLng(-111.89);
        p.setCapturedAt(capturedAt);
        return p;
    }

    private PlanActivation activation() {
        PlanActivation a = new PlanActivation();
        a.setId("activation-1");
        a.setOwnerEmail("owner@example.com");
        a.setActivatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        a.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));
        return a;
    }
}
