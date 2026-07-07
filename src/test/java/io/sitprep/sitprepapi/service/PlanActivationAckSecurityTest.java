package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.PlanActivationAck;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.AckDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.AckRequest;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Ack-injection hardening on the un-authed
 * {@code POST /api/plans/activations/{id}/acks} path (2026-07-06
 * location-audit fix): when the owner targeted specific recipients,
 * {@code recipientEmail} must resolve to one of them; coordinates are
 * bounds-checked; untargeted (bare share link) activations keep the open
 * link-possession contract. Pure Mockito — no Spring context, no DB.
 */
class PlanActivationAckSecurityTest {

    private static final String ACT_ID = "act-1";
    private static final String OWNER = "owner@x.com";
    private static final String TARGETED_MEMBER = "spouse@x.com";
    private static final String TARGETED_CONTACT = "grandma@x.com";
    private static final String ATTACKER = "attacker@x.com";

    private PlanActivationRepo activationRepo;
    private PlanActivationAckRepo ackRepo;
    private UserInfoRepo userInfoRepo;
    private EmergencyContactRepo emergencyContactRepo;
    private EmergencyContactGroupRepo emergencyContactGroupRepo;
    private PlanActivationService service;

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        ackRepo = mock(PlanActivationAckRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        emergencyContactRepo = mock(EmergencyContactRepo.class);
        emergencyContactGroupRepo = mock(EmergencyContactGroupRepo.class);
        service = new PlanActivationService(activationRepo, ackRepo, userInfoRepo,
                mock(MeetingPlaceRepo.class), mock(EvacuationPlanRepo.class),
                mock(OriginLocationRepo.class), emergencyContactGroupRepo,
                emergencyContactRepo, mock(WebSocketMessageSender.class),
                mock(GroupRepo.class), mock(NotificationService.class),
                mock(HouseholdAccessService.class));
        TransactionSynchronizationManager.initSynchronization();

        // Common happy-path stubs for the upsert.
        when(ackRepo.findByActivationIdAndRecipientEmailIgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(ackRepo.save(any(PlanActivationAck.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private PlanActivation activation() {
        PlanActivation a = new PlanActivation();
        a.setId(ACT_ID);
        a.setOwnerEmail(OWNER);
        a.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        return a;
    }

    /** Activation addressed to one household member (UserInfo id "u-2"). */
    private PlanActivation targetedActivation() {
        PlanActivation a = activation();
        a.getHouseholdMemberIds().add("u-2");
        UserInfo member = new UserInfo();
        member.setId("u-2");
        member.setUserEmail(TARGETED_MEMBER);
        when(userInfoRepo.findAllById(Set.of("u-2"))).thenReturn(List.of(member));
        return a;
    }

    private static AckRequest ack(String email, Double lat, Double lng) {
        return new AckRequest(email, null, "safe", lat, lng);
    }

    @Test
    void targetedActivation_rejectsForeignRecipientEmailWith403() {
        PlanActivation targeted = targetedActivation();
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(targeted));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordAck(ACT_ID, ack(ATTACKER, 33.75, -84.39)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(ackRepo, never()).save(any());
    }

    @Test
    void targetedActivation_acceptsTargetedHouseholdMember() {
        PlanActivation targeted = targetedActivation();
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(targeted));

        AckDto dto = service.recordAck(ACT_ID, ack(TARGETED_MEMBER, 33.75, -84.39));

        assertEquals(TARGETED_MEMBER, dto.recipientEmail());
        verify(ackRepo).save(any(PlanActivationAck.class));
    }

    @Test
    void targetedActivation_acceptsOwner() {
        PlanActivation targeted = targetedActivation();
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(targeted));

        assertDoesNotThrow(() -> service.recordAck(ACT_ID, ack(OWNER, null, null)));
    }

    @Test
    void targetedActivation_resolvesDirectContactEmails() {
        PlanActivation a = activation();
        a.getContactIds().add(7L);
        EmergencyContact c = mock(EmergencyContact.class);
        when(c.getEmail()).thenReturn(TARGETED_CONTACT);
        when(emergencyContactRepo.findAllById(Set.of(7L))).thenReturn(List.of(c));
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));

        assertDoesNotThrow(() -> service.recordAck(ACT_ID, ack(TARGETED_CONTACT, null, null)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.recordAck(ACT_ID, ack(ATTACKER, null, null)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void untargetedActivation_keepsLinkPossessionContract() {
        // Bare share link: no recipient sets. Any identity may ack —
        // the link itself is the audience.
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(activation()));

        assertDoesNotThrow(() -> service.recordAck(ACT_ID, ack("anyone@x.com", 40.76, -111.89)));
    }

    @Test
    void contactGroupsOnlyActivation_staysUntargeted_householdAndGuestsCanAck() {
        // The FE's real payload: it auto-attaches every saved contact group
        // so the recipient view can render its "Key contacts" card. That
        // must NOT narrow the ack audience (2026-07-07 fix) — household
        // co-members (never in householdMemberIds) and guest device-id
        // identities ack via link possession, exactly as the activation
        // push invites them to.
        PlanActivation a = activation();
        a.getContactGroupIds().add(7L);
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));

        assertDoesNotThrow(() -> service.recordAck(ACT_ID, ack("spouse@x.com", 33.75, -84.39)));
        assertDoesNotThrow(() -> service.recordAck(ACT_ID, ack("guest-abc123", null, null)));
        verify(ackRepo, times(2)).save(any(PlanActivationAck.class));
    }

    @Test
    void outOfBoundsCoordinatesRejectedWith400() {
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(activation()));

        assertThrows(IllegalArgumentException.class,
                () -> service.recordAck(ACT_ID, ack("anyone@x.com", 91.0, -84.39)));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordAck(ACT_ID, ack("anyone@x.com", 33.75, -181.0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordAck(ACT_ID, ack("anyone@x.com", 33.75, null)));
        verify(ackRepo, never()).save(any());
    }

    @Test
    void nullCoordinatePairStillAllowed_locationIsOptionalOnAcks() {
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(activation()));

        assertDoesNotThrow(() -> service.recordAck(ACT_ID, ack("anyone@x.com", null, null)));
    }
}
