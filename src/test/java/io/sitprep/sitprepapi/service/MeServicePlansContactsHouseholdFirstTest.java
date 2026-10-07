package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MePlansDto;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /api/me/{uid}/plans} contacts are household-first (Ready for More
 * CONTRACT.md §3a). They were read by the caller's own email only, so a member
 * who didn't author the household's contacts saw Home's "Emergency Contacts"
 * essential as not done while {@code readiness.EssentialsReadinessService}
 * (the backend twin of Home's rule) read the household's rows.
 */
class MeServicePlansContactsHouseholdFirstTest {

    static final String UID = "uid-member";
    static final String EMAIL = "member@example.com";
    static final String HH = "hh-1";

    private final UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
    private final EmergencyContactGroupRepo contactRepo = mock(EmergencyContactGroupRepo.class);
    private final MeSubfetchService subfetch = mock(MeSubfetchService.class);

    private final MeService service = new MeService(
            userInfoRepo,
            mock(GroupRepo.class),
            mock(DemographicRepo.class),
            mock(MealPlanDataRepo.class),
            mock(EvacuationPlanRepo.class),
            mock(MeetingPlaceRepo.class),
            mock(OriginLocationRepo.class),
            contactRepo,
            mock(PlanActivationRepo.class),
            mock(PostRepo.class),
            mock(GroupReadStateRepo.class),
            mock(GroupPostRepo.class),
            mock(GroupMutePrefRepo.class),
            mock(HouseholdRitualRepo.class),
            subfetch,
            mock(UserInfoService.class),
            mock(PlatformAccessService.class),
            mock(GoBagService.class),
            mock(EmergencySupportService.class), mock(StandingConditionService.class),
            mock(HouseholdReadinessService.class),
            new ObjectMapper(),
            mock(AgencyStaffService.class)
    );

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        UserInfo user = new UserInfo();
        user.setFirebaseUid(UID);
        user.setUserEmail(EMAIL);
        user.setBaseHouseholdId(HH);
        when(userInfoRepo.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(subfetch.runReadOnly(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
    }

    @Test
    void householdContactsWinForANonAuthoringMember() {
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(group(1L, "Family", "owner@example.com")));

        MePlansDto plans = service.buildMePlans(UID).orElseThrow().plans();

        assertThat(plans.emergencyContactGroups()).extracting(MePlansDto.EmergencyContactGroupSummary::name)
                .containsExactly("Family");
        verify(contactRepo, never()).findByOwnerEmailIgnoreCase(EMAIL);
    }

    @Test
    void ownRowsAreTheFallbackWhenTheHouseholdHasNone() {
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of());
        when(contactRepo.findByOwnerEmailIgnoreCase(EMAIL)).thenReturn(List.of(group(2L, "Mine", EMAIL)));

        MePlansDto plans = service.buildMePlans(UID).orElseThrow().plans();

        assertThat(plans.emergencyContactGroups()).extracting(MePlansDto.EmergencyContactGroupSummary::name)
                .containsExactly("Mine");
    }

    private static EmergencyContactGroup group(Long id, String name, String owner) {
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setId(id);
        g.setName(name);
        g.setOwnerEmail(owner);
        g.setHouseholdId(HH);
        return g;
    }
}
