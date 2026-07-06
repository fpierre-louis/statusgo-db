package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.domain.PlanActivationAck;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationDetailDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.CreateActivationRequest;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.service.PlanActivationService.ActivationExpiredException;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the deployed-plan emergency-map assemblers + the IDOR-at-create
 * guard (docs/map/MAP_API_CONTRACT.md, MAP_PRIVACY_AND_SECURITY_REVIEW.md).
 * Pure Mockito — no Spring context, no DB.
 */
class PlanActivationMapServiceTest {

    private PlanActivationRepo activationRepo;
    private PlanActivationAckRepo ackRepo;
    private UserInfoRepo userInfoRepo;
    private MeetingPlaceRepo meetingPlaceRepo;
    private EvacuationPlanRepo evacuationPlanRepo;
    private OriginLocationRepo originLocationRepo;
    private EmergencyContactGroupRepo emergencyContactGroupRepo;
    private WebSocketMessageSender ws;
    private GroupRepo groupRepo;
    private NotificationService notificationService;
    private HouseholdAccessService householdAccess;
    private PlanActivationService service;

    private static final String OWNER = "owner@x.com";
    private static final String ACT_ID = "act-123";

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        ackRepo = mock(PlanActivationAckRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        meetingPlaceRepo = mock(MeetingPlaceRepo.class);
        evacuationPlanRepo = mock(EvacuationPlanRepo.class);
        originLocationRepo = mock(OriginLocationRepo.class);
        emergencyContactGroupRepo = mock(EmergencyContactGroupRepo.class);
        ws = mock(WebSocketMessageSender.class);
        groupRepo = mock(GroupRepo.class);
        notificationService = mock(NotificationService.class);
        householdAccess = mock(HouseholdAccessService.class);
        service = new PlanActivationService(activationRepo, ackRepo, userInfoRepo,
                meetingPlaceRepo, evacuationPlanRepo, originLocationRepo,
                emergencyContactGroupRepo, ws, groupRepo, notificationService, householdAccess);
        // createActivation registers an afterCommit synchronization; activate one
        // so the success path doesn't throw "synchronization not active".
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---- builders ----------------------------------------------------------
    private PlanActivation activation(Long meetingId, Long evacId, Double lat, Double lng, Instant expiresAt) {
        PlanActivation a = new PlanActivation();
        a.setId(ACT_ID);
        a.setOwnerEmail(OWNER);
        a.setOwnerName("Pat Owner");
        a.setMeetingPlaceId(meetingId);
        a.setEvacPlanId(evacId);
        a.setLat(lat);
        a.setLng(lng);
        a.setActivatedAt(Instant.now());
        a.setExpiresAt(expiresAt);
        return a;
    }

    private MeetingPlace meetingPlace(Long id, String owner, Double lat, Double lng) {
        MeetingPlace m = new MeetingPlace();
        m.setId(id);
        m.setOwnerEmail(owner);
        m.setName("Big Oak");
        m.setAddress("1 Oak St");
        m.setLat(lat);
        m.setLng(lng);
        return m;
    }

    private EvacuationPlan evac(Long id, String owner, Double lat, Double lng) {
        EvacuationPlan e = new EvacuationPlan();
        e.setId(id);
        e.setOwnerEmail(owner);
        e.setShelterName("Red Cross Shelter");
        e.setShelterAddress("5 Safe Ave");
        e.setLat(lat);
        e.setLng(lng);
        return e;
    }

    private Instant future() { return Instant.now().plus(24, ChronoUnit.HOURS); }
    private Instant past() { return Instant.now().minus(1, ChronoUnit.HOURS); }

    private MapPoiDto byLabel(List<MapPoiDto> pois, String placeLabel) {
        return pois.stream().filter(p -> placeLabel.equals(p.placeLabel())).findFirst().orElse(null);
    }

    // ---- authenticated owner/household map ---------------------------------

    @Test
    void ownerMap_ownerAllowed_returnsAllFourPoints_noPiiBleed() {
        PlanActivation a = activation(1L, 2L, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(meetingPlaceRepo.findById(1L)).thenReturn(Optional.of(meetingPlace(1L, OWNER, 40.1, -111.1)));
        when(evacuationPlanRepo.findById(2L)).thenReturn(Optional.of(evac(2L, OWNER, 40.2, -111.2)));
        when(originLocationRepo.findByOwnerEmailIgnoreCase(OWNER))
                .thenReturn(List.of(originRow(40.3, -111.3)));

        List<MapPoiDto> pois = service.getActivationMap(ACT_ID, OWNER);

        assertEquals(4, pois.size());
        assertNotNull(byLabel(pois, "meetup"));
        assertNotNull(byLabel(pois, "shelter-primary"));
        assertNotNull(byLabel(pois, "owner"));
        assertNotNull(byLabel(pois, "origin"));
        for (MapPoiDto p : pois) {
            assertEquals("proprietary:activation", p.source());
            // MapPoiDto boundary — no group/aid/external field bleed, no PII.
            assertNull(p.verified());
            assertNull(p.postId());
            assertNull(p.attribution());
            assertNull(p.category());
            assertNull(p.distanceKm());
            assertNull(p.externalMapUrl());
            assertNull(p.ownerUserId());
        }
        // description carries the address (never a coordinate / never PII).
        assertEquals("1 Oak St", byLabel(pois, "meetup").description());
    }

    @Test
    void ownerMap_householdCoMember_allowed() {
        PlanActivation a = activation(null, null, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(householdAccess.canReadPlanDataFor("member@x.com", OWNER)).thenReturn(true);

        assertDoesNotThrow(() -> service.getActivationMap(ACT_ID, "member@x.com"));
    }

    @Test
    void ownerMap_targetedRecipientById_allowed() {
        PlanActivation a = activation(null, null, 40.0, -111.0, future());
        a.getHouseholdMemberIds().add("uid-recipient");
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(householdAccess.canReadPlanDataFor("rec@x.com", OWNER)).thenReturn(false);
        UserInfo u = mock(UserInfo.class);
        when(u.getId()).thenReturn("uid-recipient");
        when(userInfoRepo.findByUserEmailIgnoreCase("rec@x.com")).thenReturn(Optional.of(u));

        assertDoesNotThrow(() -> service.getActivationMap(ACT_ID, "rec@x.com"));
    }

    @Test
    void ownerMap_unrelatedCaller_forbidden() {
        PlanActivation a = activation(1L, 2L, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(householdAccess.canReadPlanDataFor("attacker@x.com", OWNER)).thenReturn(false);
        when(userInfoRepo.findByUserEmailIgnoreCase("attacker@x.com")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getActivationMap(ACT_ID, "attacker@x.com"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void ownerMap_unknownId_notFound() {
        when(activationRepo.findById("nope")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getActivationMap("nope", OWNER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void ownerMap_expired_throwsExpired() {
        PlanActivation a = activation(1L, 2L, 40.0, -111.0, past());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        assertThrows(ActivationExpiredException.class, () -> service.getActivationMap(ACT_ID, OWNER));
    }

    // ---- public recipient map (data-minimized) -----------------------------

    @Test
    void recipientMap_returnsOnlyMeetingAndShelter_noHomeNoOrigin() {
        PlanActivation a = activation(1L, 2L, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(meetingPlaceRepo.findById(1L)).thenReturn(Optional.of(meetingPlace(1L, OWNER, 40.1, -111.1)));
        when(evacuationPlanRepo.findById(2L)).thenReturn(Optional.of(evac(2L, OWNER, 40.2, -111.2)));

        List<MapPoiDto> pois = service.getRecipientMap(ACT_ID);

        assertEquals(2, pois.size());
        assertNotNull(byLabel(pois, "meetup"));
        assertNotNull(byLabel(pois, "shelter-primary"));
        assertNull(byLabel(pois, "owner"));   // no owner rally point
        assertNull(byLabel(pois, "origin"));  // no home/origin
        // Data minimization proof: the recipient path never touches the home table.
        verify(originLocationRepo, never()).findByOwnerEmailIgnoreCase(anyString());
    }

    @Test
    void recipientMap_dropsNonFiniteCoordinates() {
        PlanActivation a = activation(1L, 2L, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(meetingPlaceRepo.findById(1L)).thenReturn(Optional.of(meetingPlace(1L, OWNER, null, null))); // no coords
        when(evacuationPlanRepo.findById(2L)).thenReturn(Optional.of(evac(2L, OWNER, 40.2, -111.2)));

        List<MapPoiDto> pois = service.getRecipientMap(ACT_ID);
        assertEquals(1, pois.size());
        assertEquals("shelter-primary", pois.get(0).placeLabel());
    }

    // ---- IDOR-at-create guard ----------------------------------------------

    @Test
    void createActivation_crossTenantMeetingPlace_forbidden_andNotSaved() {
        when(meetingPlaceRepo.findById(99L)).thenReturn(Optional.of(meetingPlace(99L, "victim@x.com", 1.0, 2.0)));
        when(householdAccess.canReadPlanDataFor(OWNER, "victim@x.com")).thenReturn(false);

        CreateActivationRequest req = new CreateActivationRequest(
                OWNER, 99L, null, null, null, null, null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createActivation(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(activationRepo, never()).save(any());
    }

    @Test
    void createActivation_crossTenantEvacPlan_forbidden_andNotSaved() {
        when(evacuationPlanRepo.findById(77L)).thenReturn(Optional.of(evac(77L, "victim@x.com", 1.0, 2.0)));
        when(householdAccess.canReadPlanDataFor(OWNER, "victim@x.com")).thenReturn(false);

        CreateActivationRequest req = new CreateActivationRequest(
                OWNER, null, 77L, null, null, null, null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createActivation(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(activationRepo, never()).save(any());
    }

    @Test
    void createActivation_ownPlans_succeeds() {
        when(meetingPlaceRepo.findById(1L)).thenReturn(Optional.of(meetingPlace(1L, OWNER, 40.1, -111.1)));
        when(householdAccess.canReadPlanDataFor(OWNER, OWNER)).thenReturn(true);
        when(userInfoRepo.findByUserEmailIgnoreCase(OWNER)).thenReturn(Optional.empty());
        when(activationRepo.save(any())).thenAnswer(inv -> {
            PlanActivation p = inv.getArgument(0);
            p.setId(ACT_ID);
            return p;
        });

        CreateActivationRequest req = new CreateActivationRequest(
                OWNER, 1L, null, null, null, null, null, null);

        assertDoesNotThrow(() -> service.createActivation(req));
        verify(activationRepo).save(any());
    }

    private OriginLocation originRow(Double lat, Double lng) {
        return new OriginLocation("Home", "9 Home Rd", lat, lng, OWNER);
    }

    private EmergencyContactGroup contactGroup(Long id) {
        EmergencyContact c = new EmergencyContact();
        c.setId(1L);
        c.setName("Aunt May");
        c.setRole("Aunt");
        c.setPhone("555-1");
        c.setAddress("1 Secret St");
        c.setMedicalInfo("diabetic");
        c.setEmail("may@x.com");
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setId(id);
        g.setName("Family");
        g.setContacts(List.of(c));
        return g;
    }

    // ---- SEC-3: audience-aware snapshot ------------------------------------

    @Test
    void getActivation_owner_seesFullDetail_withAcks() {
        PlanActivation a = activation(null, null, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        PlanActivationAck ack = mock(PlanActivationAck.class);
        when(ack.getLat()).thenReturn(40.9);
        when(ack.getLng()).thenReturn(-111.9);
        when(ackRepo.findByActivationIdOrderByAckedAtAsc(ACT_ID)).thenReturn(List.of(ack));

        ActivationDetailDto d = service.getActivation(ACT_ID, OWNER).orElseThrow();
        assertEquals(1, d.acks().size()); // owner sees the live roll-up
    }

    @Test
    void getActivation_recipient_minimized_acksEmpty_contactPiiStripped() {
        PlanActivation a = activation(null, null, 40.0, -111.0, future());
        a.getContactGroupIds().add(7L);
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(emergencyContactGroupRepo.findAllById(any())).thenReturn(List.of(contactGroup(7L)));

        // Guest caller (no token) → recipient view.
        ActivationDetailDto d = service.getActivation(ACT_ID, null).orElseThrow();

        assertTrue(d.acks().isEmpty()); // never other recipients' status/live location
        var c = d.emergencyContactGroups().get(0).contacts().get(0);
        assertEquals("Aunt May", c.name());
        assertEquals("555-1", c.phone());
        assertNull(c.address());     // PII stripped
        assertNull(c.medicalInfo()); // PII stripped
        assertNull(c.email());       // PII stripped
        // The recipient view must not even query the ack table.
        verify(ackRepo, never()).findByActivationIdOrderByAckedAtAsc(anyString());
    }

    @Test
    void getAcks_owner_allowed() {
        PlanActivation a = activation(null, null, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(ackRepo.findByActivationIdOrderByAckedAtAsc(ACT_ID)).thenReturn(List.of());
        assertDoesNotThrow(() -> service.getAcks(ACT_ID, OWNER));
    }

    @Test
    void getAcks_nonOwner_forbidden() {
        PlanActivation a = activation(null, null, 40.0, -111.0, future());
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
        when(householdAccess.canReadPlanDataFor("stranger@x.com", OWNER)).thenReturn(false);
        when(userInfoRepo.findByUserEmailIgnoreCase("stranger@x.com")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getAcks(ACT_ID, "stranger@x.com"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getAcks_unknownId_notFound() {
        when(activationRepo.findById("nope")).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getAcks("nope", OWNER));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }
}
