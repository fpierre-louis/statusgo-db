package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MePlansDto;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 *
 * <p>EXEC-A1 task 3 audited every consumer and pinned the final behavior per
 * role. The one correction: the household is used only while the user still
 * belongs to it — an admin removing a member does not clear the member's
 * baseHouseholdId, so the raw id must not unlock a household's plan data.</p>
 */
class MeServicePlansContactsHouseholdFirstTest {

    static final String UID = "uid-member";
    static final String EMAIL = "member@example.com";
    static final String HH = "hh-1";
    static final String HH2 = "hh-2";

    private final UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
    private final GroupRepo groupRepo = mock(GroupRepo.class);
    private final EmergencyContactGroupRepo contactRepo = mock(EmergencyContactGroupRepo.class);
    private final MeetingPlaceRepo meetingPlaceRepo = mock(MeetingPlaceRepo.class);
    private final EvacuationPlanRepo evacRepo = mock(EvacuationPlanRepo.class);
    private final MeSubfetchService subfetch = mock(MeSubfetchService.class);

    private final MeService service = new MeService(
            userInfoRepo,
            groupRepo,
            mock(DemographicRepo.class),
            mock(MealPlanDataRepo.class),
            evacRepo,
            meetingPlaceRepo,
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

    private UserInfo user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        user = new UserInfo();
        user.setFirebaseUid(UID);
        user.setUserEmail(EMAIL);
        user.setBaseHouseholdId(HH);
        when(userInfoRepo.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(subfetch.runReadOnly(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        household(HH, "owner@example.com", List.of(), List.of("owner@example.com", EMAIL));
        when(contactRepo.findByHouseholdId(anyString())).thenReturn(List.of());
        when(contactRepo.findByOwnerEmailIgnoreCase(anyString())).thenReturn(List.of());
    }

    private void household(String id, String owner, List<String> admins, List<String> members) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType("Household");
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(admins));
        g.setMemberEmails(new ArrayList<>(members));
        when(groupRepo.findByGroupId(id)).thenReturn(Optional.of(g));
    }

    private List<String> contactGroupNames() {
        MePlansDto plans = service.buildMePlans(UID).orElseThrow().plans();
        return plans.emergencyContactGroups().stream().map(MePlansDto.EmergencyContactGroupSummary::name).toList();
    }

    @Test
    void householdContactsWinForANonAuthoringMember() {
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(group(1L, "Family", "owner@example.com", HH)));

        assertThat(contactGroupNames()).containsExactly("Family");
        verify(contactRepo, never()).findByOwnerEmailIgnoreCase(EMAIL);
    }

    @Test
    void anAdminSeesTheSameHouseholdList() {
        household(HH, "owner@example.com", List.of(EMAIL), List.of("owner@example.com", EMAIL));
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(group(1L, "Family", "owner@example.com", HH)));
        assertThat(contactGroupNames()).containsExactly("Family");
    }

    @Test
    void ownRowsAreTheFallbackWhenTheHouseholdHasNone() {
        // Includes legacy groups with no householdId at all.
        when(contactRepo.findByOwnerEmailIgnoreCase(EMAIL)).thenReturn(List.of(group(2L, "Mine", EMAIL, null)));
        assertThat(contactGroupNames()).containsExactly("Mine");
    }

    @Test
    void withHouseholdGroupsPersonalGroupsAreNotMixedIn() {
        // Recorded behavior: /me/plans shows the household's list. The member's own
        // groups stamped elsewhere (or nowhere) still live in the contacts editor,
        // which is owner-scoped; prod had 0 such users at audit time.
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(group(1L, "Family", "owner@example.com", HH)));
        when(contactRepo.findByOwnerEmailIgnoreCase(EMAIL)).thenReturn(List.of(group(2L, "Work", EMAIL, null)));
        assertThat(contactGroupNames()).containsExactly("Family");
    }

    @Test
    void aMemberWhoSwitchedBaseHouseholdReadsTheNewOne() {
        household(HH2, "other@example.com", List.of(), List.of("other@example.com", EMAIL));
        user.setBaseHouseholdId(HH2);
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(group(1L, "Old family", "owner@example.com", HH)));
        when(contactRepo.findByHouseholdId(HH2)).thenReturn(List.of(group(3L, "New family", "other@example.com", HH2)));
        assertThat(contactGroupNames()).containsExactly("New family");
    }

    @Test
    void aRemovedMembersStaleBaseUnlocksNothing() {
        // Admin removal leaves baseHouseholdId pointing at the household.
        household(HH, "owner@example.com", List.of(), List.of("owner@example.com"));
        when(contactRepo.findByHouseholdId(HH)).thenReturn(List.of(group(1L, "Family", "owner@example.com", HH)));
        when(contactRepo.findByOwnerEmailIgnoreCase(EMAIL)).thenReturn(List.of(group(2L, "Mine", EMAIL, null)));

        assertThat(contactGroupNames()).containsExactly("Mine");
        // ...and none of the other household-first plan reads touch that household.
        verify(contactRepo, never()).findByHouseholdId(HH);
        verify(meetingPlaceRepo, never()).findByHouseholdId(HH);
        verify(evacRepo, never()).findByHouseholdId(HH);
    }

    @Test
    void noHouseholdMeansOwnRowsOnly() {
        user.setBaseHouseholdId(null);
        when(contactRepo.findByOwnerEmailIgnoreCase(EMAIL)).thenReturn(List.of(group(2L, "Mine", EMAIL, null)));
        assertThat(contactGroupNames()).containsExactly("Mine");
        verify(contactRepo, never()).findByHouseholdId(anyString());
    }

    @Test
    void aBaseThatIsNotAHouseholdIsIgnored() {
        Group circle = new Group();
        circle.setGroupId(HH);
        circle.setGroupType("Neighborhood");
        circle.setMemberEmails(new ArrayList<>(List.of(EMAIL)));
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(circle));
        assertThat(contactGroupNames()).isEmpty();
        verify(contactRepo, never()).findByHouseholdId(anyString());
    }

    private static EmergencyContactGroup group(Long id, String name, String owner, String householdId) {
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setId(id);
        g.setName(name);
        g.setOwnerEmail(owner);
        g.setHouseholdId(householdId);
        return g;
    }
}
