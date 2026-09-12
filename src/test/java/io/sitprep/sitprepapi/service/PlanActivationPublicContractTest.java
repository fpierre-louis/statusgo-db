package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationDetailDto;
import io.sitprep.sitprepapi.dto.PublicActivationDtos.PublicActivationDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The contract for an ANONYMOUS activation-link holder.
 *
 * <p>── WHY THIS FILE EXISTS ────────────────────────────────────────────────
 *
 * <p>{@code GET /api/plans/activations/{id}} is {@code permitAll} — the
 * activation id is a bearer capability that travels by SMS and gets forwarded.
 * Before this suite, that route answered with the household's own DTO narrowed
 * by nulling fields, and it published the household's HOME ADDRESS
 * ({@code evacPlan.origin}), the meeting place's phone number and private
 * note, the shelter's phone and info, and the owner's internal user id.
 *
 * <p>Two kinds of test here, and both are needed:
 *
 * <ul>
 *   <li><b>Forbidden:</b> sensitive values must be absent from the SERIALIZED
 *       JSON. Asserting on the object graph would miss a field added to a
 *       nested record.</li>
 *   <li><b>Required:</b> the recipient must still be able to act. A privacy
 *       suite that only checks absence will happily pass on an empty response
 *       while the shared plan becomes useless — and this one carries evacuation
 *       instructions.</li>
 * </ul>
 *
 * <p>The allowlist test below is the durable one: it pins the exact field set
 * of the public record, so ADDING a field to it fails until somebody states the
 * intent. That is the mechanism that would have caught the original leak.
 */
class PlanActivationPublicContractTest {

    private static final String ACT_ID = "act-pub-1";
    private static final String OWNER = "owner@x.com";
    private static final String STRANGER = "stranger@x.com";

    // Values chosen to be unmistakable in a raw payload.
    private static final String HOME_ORIGIN = "MARKER-HOME-ORIGIN-1600-PRIVATE-LANE";
    private static final String MEET_PHONE = "+15550001111";
    private static final String MEET_NOTE = "MARKER-MEET-PRIVATE-NOTE";
    private static final String SHELTER_PHONE = "+15550002222";
    private static final String SHELTER_INFO = "MARKER-SHELTER-PRIVATE-INFO";
    private static final String CONTACT_PHONE = "+15550003333";
    private static final String CONTACT_MEDICAL = "MARKER-CONTACT-MEDICALINFO";

    private PlanActivationRepo activationRepo;
    private MeetingPlaceRepo meetingPlaceRepo;
    private EvacuationPlanRepo evacuationPlanRepo;
    private EmergencyContactGroupRepo contactGroupRepo;
    private PlanActivationService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        meetingPlaceRepo = mock(MeetingPlaceRepo.class);
        evacuationPlanRepo = mock(EvacuationPlanRepo.class);
        contactGroupRepo = mock(EmergencyContactGroupRepo.class);

        service = new PlanActivationService(activationRepo, mock(PlanActivationAckRepo.class),
                mock(UserInfoRepo.class), meetingPlaceRepo, evacuationPlanRepo,
                mock(OriginLocationRepo.class), contactGroupRepo,
                mock(EmergencyContactRepo.class), mock(WebSocketMessageSender.class),
                mock(GroupRepo.class), mock(NotificationService.class),
                mock(HouseholdAccessService.class), mock(HouseholdResolver.class),
                mock(GoBagService.class), mock(HouseholdEventService.class), mock(GroupService.class), mock(ActivationDirectiveResolver.class));

        mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        MeetingPlace m = new MeetingPlace();
        m.setId(4242L);
        m.setName("Oak Park");
        m.setAddress("12 Oak St");
        m.setPhoneNumber(MEET_PHONE);
        m.setAdditionalInfo(MEET_NOTE);
        m.setLat(40.5);
        m.setLng(-111.9);

        EvacuationPlan e = new EvacuationPlan();
        e.setId(9191L);
        e.setName("Primary evac");
        e.setOrigin(HOME_ORIGIN);
        e.setDestination("Aunt Rosa's");
        e.setShelterName("County Shelter");
        e.setShelterAddress("900 Civic Way");
        e.setShelterPhoneNumber(SHELTER_PHONE);
        e.setShelterInfo(SHELTER_INFO);
        e.setTravelMode("driving");
        e.setLat(40.6);
        e.setLng(-111.8);

        EmergencyContact c = new EmergencyContact();
        c.setId(77L);
        c.setName("Neighbour Chris");
        c.setRole("Neighbor");
        c.setPhone(CONTACT_PHONE);
        c.setMedicalInfo(CONTACT_MEDICAL);
        c.setSubjectType("user");
        c.setSubjectId("ada@example.com");
        c.setSubjectName("Ada");
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setId(5L);
        g.setName("Doctors");
        g.setContacts(List.of(c));

        when(meetingPlaceRepo.findById(4242L)).thenReturn(Optional.of(m));
        when(evacuationPlanRepo.findById(9191L)).thenReturn(Optional.of(e));
        when(contactGroupRepo.findAllById(any())).thenReturn(List.of(g));
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(activation()));
    }

    private PlanActivation activation() {
        PlanActivation a = new PlanActivation();
        a.setId(ACT_ID);
        a.setOwnerEmail(OWNER);
        a.setOwnerUserId("11111111-2222-3333-4444-555555555555");
        a.setOwnerName("Dana");
        a.setMeetingPlaceId(4242L);
        a.setEvacPlanId(9191L);
        a.setContactGroupIds(new HashSet<>(Set.of(5L)));
        a.setMeetingMode("PRIMARY");
        a.setEvacMode("PRIMARY");
        a.setOperationalMode("EVACUATING");
        a.setMovementDirective("evacuate");
        // A directive with no governing alert behind it is dropped to "none" by
        // design (RC-1: SitPrep never states an instruction it cannot attribute
        // to an issuer). So the fixture carries the alert the order came from —
        // which is also what makes the provenance assertion below meaningful.
        a.setGoverningAlertSource("NWS");
        a.setGoverningAlertId("urn:oid:2.49.0.1.840.0.TEST");
        a.setGoverningAlertEvent("Flash Flood Warning");
        a.setGoverningAlertHeadline("Flash Flood Warning issued for the county");
        a.setGoverningAlertLifecycleState("active");
        a.setMessagePreview("Head to Aunt Rosa's now.");
        a.setActivatedAt(Instant.now());
        a.setExpiresAt(Instant.now().plus(3, ChronoUnit.HOURS));
        return a;
    }

    /** The anonymous payload, serialized exactly as the wire would carry it. */
    private String anonymousJson() throws Exception {
        Object dto = service.getActivation(ACT_ID, null).orElseThrow();
        assertThat(dto).isInstanceOf(PublicActivationDto.class);
        return mapper.writeValueAsString(dto);
    }

    // ── FORBIDDEN ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a link holder must NOT receive")
    class Forbidden {

        @Test
        @DisplayName("the household's home address — the field this remediation exists for")
        void noOrigin() throws Exception {
            assertThat(anonymousJson()).doesNotContain(HOME_ORIGIN);
        }

        @Test
        @DisplayName("phone numbers for the meeting place, the shelter, or any contact")
        void noPhoneNumbers() throws Exception {
            String json = anonymousJson();
            assertThat(json).doesNotContain(MEET_PHONE);
            assertThat(json).doesNotContain(SHELTER_PHONE);
            assertThat(json).doesNotContain(CONTACT_PHONE);
        }

        @Test
        @DisplayName("free-text notes the household wrote for itself")
        void noPrivateNotes() throws Exception {
            String json = anonymousJson();
            assertThat(json).doesNotContain(MEET_NOTE);
            assertThat(json).doesNotContain(SHELTER_INFO);
        }

        @Test
        @DisplayName("medical information, at all")
        void noMedicalInfo() throws Exception {
            String json = anonymousJson();
            assertThat(json).doesNotContain(CONTACT_MEDICAL);
            assertThat(json).doesNotContain("medicalInfo");
        }

        @Test
        @DisplayName("the emergency contact book — not even names")
        void noContacts() throws Exception {
            String json = anonymousJson();
            assertThat(json).doesNotContain("Neighbour Chris");
            assertThat(json).doesNotContain("emergencyContactGroups");
        }

        @Test
        @DisplayName("internal identity: owner user id, subject linkage, or row ids")
        void noInternalIdentifiers() throws Exception {
            String json = anonymousJson();
            assertThat(json).doesNotContain("ownerUserId");
            assertThat(json).doesNotContain("11111111-2222-3333-4444-555555555555");
            assertThat(json).doesNotContain("subjectId");
            assertThat(json).doesNotContain("subjectType");
            assertThat(json).doesNotContain("ada@example.com");
            // Row ids for the household's own plan records.
            assertThat(json).doesNotContain("4242");
            assertThat(json).doesNotContain("9191");
        }

        @Test
        @DisplayName("other recipients' welfare, go-bag contents, or the ack roll-up")
        void noHouseholdOperationalState() throws Exception {
            String json = anonymousJson();
            assertThat(json).doesNotContain("goBags");
            assertThat(json).doesNotContain("acks");
            assertThat(json).doesNotContain("ackRollup");
            assertThat(json).doesNotContain("checkInSummary");
        }

        @Test
        @DisplayName("RC-3 emergency support data (absent before, and must stay absent)")
        void noEmergencySupport() throws Exception {
            String json = anonymousJson();
            for (String f : List.of("supportProfiles", "supportAssignments", "mobilityNote",
                    "equipmentNote", "supportNote", "serviceAnimal", "communicationNeeds",
                    "refrigeratedMedication", "criticalMedication", "cannotUseStairs",
                    "needsEvacuationAssistance", "accessibleTransportNeeded", "preferredLanguage")) {
                assertThat(json).as("RC-3 field %s", f).doesNotContain(f);
            }
        }
    }

    // ── REQUIRED ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a link holder MUST still receive")
    class Required {

        @Test
        @DisplayName("who sent it, and the household's own message")
        void provenanceAndMessage() throws Exception {
            JsonNode n = mapper.readTree(anonymousJson());
            assertThat(n.get("ownerName").asText()).isEqualTo("Dana");
            assertThat(n.get("messagePreview").asText()).isEqualTo("Head to Aunt Rosa's now.");
        }

        @Test
        @DisplayName("the movement directive and the action it produces (RC-1)")
        void movementDirectiveSurvives() throws Exception {
            JsonNode s = mapper.readTree(anonymousJson()).get("activeSituation");
            assertThat(s.get("movementDirective").asText()).isEqualTo("evacuate");
            assertThat(s.get("operationalMode").asText()).isEqualTo("EVACUATING");
            assertThat(s.get("primaryActionKind").asText()).isEqualTo("evacuate");
            assertThat(s.get("primaryAction").asText()).isNotBlank();
        }

        @Test
        @DisplayName("the issuing authority, so the order reads as the county's and not SitPrep's")
        void alertProvenanceSurvives() throws Exception {
            JsonNode g = mapper.readTree(anonymousJson()).get("activeSituation").get("governingAlert");
            assertThat(g).isNotNull();
            assertThat(g.get("source").asText()).isEqualTo("NWS");
            assertThat(g.get("event").asText()).isEqualTo("Flash Flood Warning");
            assertThat(g.get("headline").asText()).isNotBlank();
        }

        @Test
        @DisplayName("the suppression, so withholding a destination does not read as an omission")
        void suppressionIsExplained() throws Exception {
            JsonNode s = mapper.readTree(anonymousJson()).get("activeSituation");
            assertThat(s.get("suppressedAction").asText()).isNotBlank();
            assertThat(s.get("suppressedReason").asText()).isNotBlank();
        }

        @Test
        @DisplayName("where to go — names, street addresses and coordinates")
        void destinationsSurvive() throws Exception {
            JsonNode n = mapper.readTree(anonymousJson());
            assertThat(n.get("meetingPlace").get("name").asText()).isEqualTo("Oak Park");
            assertThat(n.get("meetingPlace").get("address").asText()).isEqualTo("12 Oak St");
            assertThat(n.get("meetingPlace").get("lat").asDouble()).isEqualTo(40.5);
            JsonNode ep = n.get("evacPlan");
            assertThat(ep.get("destination").asText()).isEqualTo("Aunt Rosa's");
            assertThat(ep.get("shelterName").asText()).isEqualTo("County Shelter");
            assertThat(ep.get("shelterAddress").asText()).isEqualTo("900 Civic Way");
            assertThat(ep.get("travelMode").asText()).isEqualTo("driving");
        }

        @Test
        @DisplayName("lifecycle, so an ended or expired plan stops reading as current")
        void lifecycleSurvives() throws Exception {
            JsonNode n = mapper.readTree(anonymousJson());
            assertThat(n.has("closed")).isTrue();
            assertThat(n.has("endedAt")).isTrue();
            assertThat(n.has("expiresAt")).isTrue();
            assertThat(n.get("activeSituation").get("status").asText()).isEqualTo("active");
        }

        @Test
        @DisplayName("a LIVE situation does not claim it has ended")
        void liveSituationHasNoEndedAt() throws Exception {
            // The three timestamps on ActiveSituationDto were passed shifted, so
            // endedAt received getActivatedAt() and every live activation
            // reported itself ended. The recipient view reads endedAt to decide
            // whether to render "This is over." — so a household mid-evacuation
            // told its link holders the emergency was finished.
            JsonNode s = mapper.readTree(anonymousJson()).get("activeSituation");
            assertThat(s.get("status").asText()).isEqualTo("active");
            assertThat(s.get("endedAt").isNull()).as("a live situation has no endedAt").isTrue();
            assertThat(s.get("activatedAt").isNull()).as("and it does have an activatedAt").isFalse();
        }

        @Test
        @DisplayName("capabilities as booleans — never as an identifier to compare")
        void capabilitiesAreServerComputed() throws Exception {
            JsonNode n = mapper.readTree(anonymousJson());
            assertThat(n.get("viewerCanEnd").asBoolean()).isFalse();
            assertThat(n.get("viewerIsOwner").asBoolean()).isFalse();
        }
    }

    // ── THE ALLOWLIST ITSELF ────────────────────────────────────────────────

    @Test
    @DisplayName("the public record's field set is pinned — adding one fails until it is stated")
    void publicContractIsPinned() {
        Set<String> approved = Set.of(
                "activationId", "ownerName", "activatedAt", "expiresAt", "closed", "endedAt",
                "viewerCanEnd", "viewerIsOwner", "meetingMode", "evacMode", "messagePreview",
                "meetingPlace", "evacPlan", "activeSituation");
        Set<String> actual = new TreeSet<>();
        for (RecordComponent rc : PublicActivationDto.class.getRecordComponents()) {
            actual.add(rc.getName());
        }
        assertThat(actual)
                .as("A new field on the PUBLIC activation contract is a disclosure decision. "
                        + "If it belongs, add it to `approved` in this test with intent.")
                .containsExactlyInAnyOrderElementsOf(approved);
    }

    @Test
    @DisplayName("an authenticated household reader still gets the full snapshot")
    void authenticatedReaderIsUnaffected() {
        Object dto = service.getActivation(ACT_ID, OWNER).orElseThrow();
        assertThat(dto).isInstanceOf(ActivationDetailDto.class);
        ActivationDetailDto d = (ActivationDetailDto) dto;
        // The household keeps everything the recipient lost.
        assertThat(d.evacPlan().origin()).isEqualTo(HOME_ORIGIN);
        assertThat(d.meetingPlace().phoneNumber()).isEqualTo(MEET_PHONE);
        assertThat(d.emergencyContactGroups()).isNotEmpty();
        assertThat(d.ownerUserId()).isNotBlank();
        assertThat(d.viewerIsOwner()).isTrue();
    }

    @Test
    @DisplayName("a signed-in stranger is a link holder, not a household reader")
    void signedInStrangerGetsPublicProjection() {
        Object dto = service.getActivation(ACT_ID, STRANGER).orElseThrow();
        assertThat(dto).isInstanceOf(PublicActivationDto.class);
        assertThat(((PublicActivationDto) dto).viewerIsOwner()).isFalse();
    }

    @Test
    @DisplayName("authenticated contact snapshots are unchanged — this did not narrow the household")
    void householdContactsKeepTheirDetail() {
        ActivationDetailDto d = (ActivationDetailDto) service.getActivation(ACT_ID, OWNER).orElseThrow();
        var contact = d.emergencyContactGroups().get(0).contacts().get(0);
        assertThat(contact.phone()).isEqualTo(CONTACT_PHONE);
        assertThat(contact.medicalInfo()).isEqualTo(CONTACT_MEDICAL);
    }
}
