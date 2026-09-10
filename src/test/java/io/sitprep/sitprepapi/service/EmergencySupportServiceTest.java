package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.dto.EmergencySupportDtos.*;
import io.sitprep.sitprepapi.repo.EmergencyContactRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportAssignmentRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * RC-3 Phase 2 · Emergency Need Profile + prepared Support Plan.
 *
 * <p>The tests that matter most here are the ones about what this feature must
 * NOT do: leak a support profile to a group admin, imply a helper has agreed,
 * or quietly store a value the enum does not define.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmergencySupportServiceTest {

    @Mock EmergencySupportProfileRepo profileRepo;
    @Mock EmergencySupportAssignmentRepo assignmentRepo;
    @Mock EmergencyContactRepo contactRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock HouseholdAccessService access;

    private EmergencySupportService service;

    private static final String HH = "hh-1";
    private static final String ADMIN = "admin@probe.app";
    private static final String GRANDMA = "grandma@probe.app";

    @BeforeEach
    void setUp() {
        service = new EmergencySupportService(
                profileRepo, assignmentRepo, contactRepo, userInfoRepo, access);
        when(profileRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(assignmentRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(profileRepo.findByHouseholdIdAndSubjectTypeAndSubjectId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(assignmentRepo.findByHouseholdIdAndSubjectTypeAndSubjectIdAndRole(
                anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
    }

    private static SupportProfileRequest request() {
        return new SupportProfileRequest(
                true, true, "walker",
                List.of("TEXT_NOT_VOICE"), null,
                true, "oxygen concentrator",
                false, true, true,
                false, null, "Panics at sirens; use her name.");
    }

    // ── Authorization ────────────────────────────────────────────────────

    @Test
    void readingRequiresHouseholdMembership() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanReadHousehold("stranger@probe.app", HH);

        assertThatThrownBy(() -> service.listProfiles(HH, "stranger@probe.app"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void editingSomebodyElsesProfileRequiresHouseholdAdmin() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanAdminHousehold("member@probe.app", HH);

        assertThatThrownBy(() -> service.upsertProfile(
                HH, "manual", "bobby", request(), "member@probe.app"))
                .isInstanceOf(ResponseStatusException.class);
    }

    /** A person's own support needs are theirs to state. */
    @Test
    void aPersonMayEditTheirOwnProfileWithoutBeingAnAdmin() {
        service.upsertProfile(HH, "user", GRANDMA, request(), GRANDMA);

        verify(access).requireCanReadHousehold(GRANDMA, HH);
        verify(access, never()).requireCanAdminHousehold(anyString(), anyString());
    }

    /** Self is not a bypass: they still have to be in this household. */
    @Test
    void selfEditStillRequiresHouseholdMembership() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanReadHousehold(GRANDMA, HH);

        assertThatThrownBy(() -> service.upsertProfile(HH, "user", GRANDMA, request(), GRANDMA))
                .isInstanceOf(ResponseStatusException.class);
    }

    /**
     * A manual member has no account to authenticate as, so there is no self
     * branch for them — their household admin is the only editor.
     */
    @Test
    void aManualSubjectAlwaysRequiresAdminEvenWhenTheIdLooksLikeTheCaller() {
        service.upsertProfile(HH, "manual", ADMIN, request(), ADMIN);
        verify(access).requireCanAdminHousehold(ADMIN, HH);
    }

    // ── Field handling ───────────────────────────────────────────────────

    @Test
    void storesTheOperationalFields() {
        service.upsertProfile(HH, "manual", "grandma-1", request(), ADMIN);

        ArgumentCaptor<EmergencySupportProfile> saved =
                ArgumentCaptor.forClass(EmergencySupportProfile.class);
        verify(profileRepo).save(saved.capture());
        EmergencySupportProfile p = saved.getValue();

        assertThat(p.isNeedsEvacuationAssistance()).isTrue();
        assertThat(p.isCannotUseStairs()).isTrue();
        assertThat(p.getMobilityNote()).isEqualTo("walker");
        assertThat(p.isPowerDependentEquipment()).isTrue();
        assertThat(p.getEquipmentNote()).isEqualTo("oxygen concentrator");
        assertThat(p.isCriticalMedication()).isTrue();
        assertThat(p.getCommunicationNeeds()).containsExactly("TEXT_NOT_VOICE");
        assertThat(p.getUpdatedByEmail()).isEqualTo(ADMIN);
    }

    /**
     * An undefined communication value is DROPPED, not stored. This is the one
     * field where a silent unknown could hide a real communication requirement.
     */
    @Test
    void dropsCommunicationValuesTheEnumDoesNotDefine() {
        SupportProfileRequest req = new SupportProfileRequest(
                false, false, null,
                List.of("TEXT_NOT_VOICE", "TELEPATHY", "", "plain_language"),
                null, false, null, false, false, false, false, null, null);

        service.upsertProfile(HH, "manual", "x", req, ADMIN);

        ArgumentCaptor<EmergencySupportProfile> saved =
                ArgumentCaptor.forClass(EmergencySupportProfile.class);
        verify(profileRepo).save(saved.capture());
        assertThat(saved.getValue().getCommunicationNeeds())
                .containsExactly("TEXT_NOT_VOICE", "PLAIN_LANGUAGE");
    }

    @Test
    void clampsTheSupportNoteRatherThanRejectingIt() {
        String tooLong = "x".repeat(400);
        SupportProfileRequest req = new SupportProfileRequest(
                false, false, null, List.of(), null,
                false, null, false, false, false, false, null, tooLong);

        service.upsertProfile(HH, "manual", "x", req, ADMIN);

        ArgumentCaptor<EmergencySupportProfile> saved =
                ArgumentCaptor.forClass(EmergencySupportProfile.class);
        verify(profileRepo).save(saved.capture());
        assertThat(saved.getValue().getSupportNote()).hasSize(240);
    }

    @Test
    void rejectsAnUnknownSubjectType() {
        assertThatThrownBy(() -> service.upsertProfile(HH, "robot", "x", request(), ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("subjectType");
    }

    // ── Assignments — plan state, never acknowledgement ──────────────────

    @Test
    void assignsAHouseholdMemberAsPrimarySupport() {
        SupportAssignmentDto dto = service.upsertAssignment(
                HH, "manual", "grandma-1", "PRIMARY",
                new SupportAssignmentRequest("MEMBER", "marcus@probe.app", null, "has the ramp"),
                ADMIN);

        assertThat(dto.role()).isEqualTo("PRIMARY");
        assertThat(dto.helperType()).isEqualTo("MEMBER");
        assertThat(dto.helperUserEmail()).isEqualTo("marcus@probe.app");
    }

    /**
     * The neighbour, the paid aide, the caseworker: a contact, never a fake
     * user. A pseudo-user would look like a member on every roster and map.
     */
    @Test
    void assignsAnEmergencyContactAsBackupSupport() {
        EmergencyContact neighbour = new EmergencyContact();
        neighbour.setName("Denise Alvarez");
        when(contactRepo.findById(42L)).thenReturn(Optional.of(neighbour));

        SupportAssignmentDto dto = service.upsertAssignment(
                HH, "manual", "grandma-1", "BACKUP",
                new SupportAssignmentRequest("CONTACT", null, 42L, null), ADMIN);

        assertThat(dto.helperType()).isEqualTo("CONTACT");
        assertThat(dto.helperContactId()).isEqualTo(42L);
        // Resolved at write time so a printed or cached plan still names them.
        assertThat(dto.helperName()).isEqualTo("Denise Alvarez");
    }

    /**
     * THE RC-2 INVARIANT. An assignment carries no acceptance, no availability
     * and no acknowledgement — there is nowhere on the wire for a client to read
     * one, so no surface can imply "Marcus is handling this".
     */
    @Test
    void anAssignmentCarriesNoAcknowledgementOfAnyKind() {
        SupportAssignmentDto dto = service.upsertAssignment(
                HH, "manual", "grandma-1", "PRIMARY",
                new SupportAssignmentRequest("MEMBER", "marcus@probe.app", null, null), ADMIN);

        List<String> fields = java.util.Arrays.stream(dto.getClass().getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertThat(fields).doesNotContain(
                "acceptedAt", "acknowledgedAt", "available", "availability", "confirmed");
    }

    @Test
    void rejectsAMemberHelperWithNoEmail() {
        assertThatThrownBy(() -> service.upsertAssignment(
                HH, "manual", "g", "PRIMARY",
                new SupportAssignmentRequest("MEMBER", null, null, null), ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("helperUserEmail");
    }

    @Test
    void rejectsAContactHelperThatDoesNotExist() {
        when(contactRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertAssignment(
                HH, "manual", "g", "PRIMARY",
                new SupportAssignmentRequest("CONTACT", null, 99L, null), ADMIN))
                .isInstanceOf(ResponseStatusException.class);
    }

    /**
     * "No longer needs support" and "these people are prepared to help them"
     * cannot be true separately.
     */
    @Test
    void deletingTheProfileTakesItsAssignmentsWithIt() {
        service.deleteProfile(HH, "manual", "grandma-1", ADMIN);

        verify(profileRepo).deleteByHouseholdIdAndSubjectTypeAndSubjectId(HH, "manual", "grandma-1");
        verify(assignmentRepo).deleteByHouseholdIdAndSubjectTypeAndSubjectId(HH, "manual", "grandma-1");
    }

    // ── The roster's question ────────────────────────────────────────────

    @Test
    void reportsWhichSubjectsHaveAProfileWithoutLoadingSensitiveFields() {
        EmergencySupportProfile p = new EmergencySupportProfile();
        p.setSubjectType("manual");
        p.setSubjectId("grandma-1");
        when(profileRepo.findByHouseholdId(HH)).thenReturn(List.of(p));

        assertThat(service.subjectsNeedingSupport(HH)).containsExactly("manual:grandma-1");
    }

    @Test
    void aUserSubjectKeyIsCaseInsensitive() {
        assertThat(EmergencySupportService.subjectKey("user", "Grandma@Probe.app"))
                .isEqualTo("user:grandma@probe.app");
    }
}
