package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.constant.LocationSharing;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.MemberSummary;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The roster's location gate must null EVERY field derived from a fix, and a
 * member who opted out must be indistinguishable from one who never had a fix
 * (locked 2026-07-02 — DV-survivor protection). BE-2 added four such fields;
 * this pins all of them through the real assembly path.
 */
class RosterLocationPrivacyGateTest {

    private static final String HH = "hh-1";
    private static final String VIEWER = "viewer@x.com";
    private static final String HIDDEN = "hidden@x.com";   // has a full fix, sharing = never
    private static final String NEVER_FIXED = "new@x.com"; // no fix at all, default sharing
    private static final String SHARING = "cat@x.com";     // full fix, sharing = always

    private final UserSavedLocationRepo placeRepo = mock(UserSavedLocationRepo.class);
    private final MemberAlertAreaService alertAreas = mock(MemberAlertAreaService.class);
    private GroupViewService service;

    @BeforeEach
    void setUp() {
        GroupRepo groupRepo = mock(GroupRepo.class);
        UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
        service = new GroupViewService(groupRepo, userInfoRepo, mock(GroupPostRepo.class),
                mock(HouseholdManualMemberService.class), mock(HouseholdAccompanimentService.class),
                mock(PlatformAccessService.class), mock(AgencyStaffService.class),
                mock(CheckInRequestService.class), mock(NotificationLogRepo.class), placeRepo, alertAreas);
        when(alertAreas.activeAreas()).thenReturn(List.of());
        when(alertAreas.idsFor(anyList(), anyDouble(), anyDouble())).thenReturn(List.of("urn:oid:heat"));

        Group hh = new Group();
        hh.setGroupId(HH);
        hh.setGroupType("Household");
        hh.setOwnerEmail(VIEWER);
        hh.setMemberEmails(new ArrayList<>(List.of(VIEWER, HIDDEN, NEVER_FIXED, SHARING)));
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(hh));

        UserInfo viewer = user(VIEWER, null);
        UserInfo hidden = fullyLocated(HIDDEN, 11L, LocationSharing.NEVER);
        UserInfo fresh = user(NEVER_FIXED, null);
        UserInfo sharing = fullyLocated(SHARING, 22L, LocationSharing.ALWAYS);
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(viewer, hidden, fresh, sharing));

        when(placeRepo.findAllById(anyCollection())).thenReturn(List.of(
                place(11L, HIDDEN, "Hidden's school", true),
                place(22L, SHARING, "Lincoln Elementary", true)));
    }

    private static UserInfo user(String email, String sharingMode) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(email.substring(0, 3));
        u.setUserStatus("SAFE");
        Map<String, String> prefs = new HashMap<>();
        if (sharingMode != null) prefs.put(HH, sharingMode);
        u.setGroupLocationSharing(prefs);
        return u;
    }

    private static UserInfo fullyLocated(String email, long placeId, String sharingMode) {
        UserInfo u = user(email, sharingMode);
        u.setLastKnownLat(40.4);
        u.setLastKnownLng(-111.8);
        u.setLastKnownLocationAt(Instant.parse("2026-09-27T14:00:00Z"));
        u.setCurrentPlaceId(placeId);
        u.setCurrentPlaceSince(Instant.parse("2026-09-27T13:05:00Z"));
        u.setLastSeenNearLabel("Dry Creek");
        u.setLocationSource("watch");
        u.setLocationAccuracyM(12);
        return u;
    }

    private static UserSavedLocation place(long id, String owner, String name, boolean share) {
        UserSavedLocation p = new UserSavedLocation();
        p.setId(id);
        p.setOwnerEmail(owner);
        p.setName(name);
        p.setKind("school");
        p.setLatitude(40.4);
        p.setLongitude(-111.8);
        p.setSharePresence(share);
        return p;
    }

    private Map<String, MemberSummary> roster() {
        GroupMemberViewDto view = service.buildMemberView(HH, VIEWER).orElseThrow();
        Map<String, MemberSummary> out = new HashMap<>();
        for (MemberSummary m : view.members()) out.put(m.email(), m);
        return out;
    }

    @Test
    void theGateNullsEveryLocationDerivedField() {
        MemberSummary m = roster().get(HIDDEN);
        assertThat(m.lastKnownLat()).isNull();
        assertThat(m.lastKnownLng()).isNull();
        assertThat(m.lastKnownLocationAt()).isNull();
        assertThat(m.atPlace()).isNull();
        assertThat(m.lastSeenNear()).isNull();
        assertThat(m.locationSource()).isNull();
        assertThat(m.locationAccuracyM()).isNull();
        assertThat(m.inAlertIds()).isNull();
    }

    @Test
    void optedOutIsIndistinguishableFromNeverFixed() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Map<String, MemberSummary> r = roster();
        ObjectNode hidden = json.valueToTree(r.get(HIDDEN));
        ObjectNode fresh = json.valueToTree(r.get(NEVER_FIXED));
        // Identity differs by construction; everything else must not.
        for (String identity : List.of("email", "firstName")) {
            hidden.remove(identity);
            fresh.remove(identity);
        }
        assertThat(hidden).isEqualTo(fresh);
    }

    @Test
    void anOpenGateCarriesTheDerivedFields() {
        MemberSummary m = roster().get(SHARING);
        assertThat(m.atPlace()).isEqualTo(new GroupMemberViewDto.AtPlace(
                "Lincoln Elementary", "school", Instant.parse("2026-09-27T13:05:00Z")));
        assertThat(m.lastSeenNear()).isEqualTo("Dry Creek");
        assertThat(m.locationSource()).isEqualTo("watch");
        assertThat(m.locationAccuracyM()).isEqualTo(12);
        assertThat(m.inAlertIds()).containsExactly("urn:oid:heat");
    }

    @Test
    void alertAreasAreTestedOnlyForMembersWhoseLocationIsVisible() {
        roster();
        // HIDDEN and SHARING sit at the same coordinate; only SHARING is tested.
        verify(alertAreas, times(1)).idsFor(anyList(), anyDouble(), anyDouble());
    }

    @Test
    void placesBehindTheGateAreNeverEvenRead() {
        roster();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Long>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(placeRepo).findAllById(ids.capture());
        assertThat(ids.getValue()).containsExactly(22L);
    }

    @Test
    void presenceSwitchedOffAfterTheFixShowsNoPlace() {
        when(placeRepo.findAllById(anyCollection()))
                .thenReturn(List.of(place(22L, SHARING, "Lincoln Elementary", false)));
        MemberSummary m = roster().get(SHARING);
        assertThat(m.atPlace()).isNull();
        assertThat(m.lastSeenNear()).as("the rest of the fix is unaffected").isEqualTo("Dry Creek");
    }

    @Test
    void aPlaceIdPointingAtSomeoneElsesPlaceShowsNothing() {
        when(placeRepo.findAllById(anyCollection()))
                .thenReturn(List.of(place(22L, VIEWER, "Viewer's office", true)));
        assertThat(roster().get(SHARING).atPlace()).isNull();
    }
}
