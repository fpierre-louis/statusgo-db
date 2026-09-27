package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.PlatformRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.MemberSummary;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-3: {@code MemberSummary.phone} exists only between members of the same
 * household — never in a group/community view, and never for a viewer the read
 * gate admits without membership (platform admin, agency staff).
 */
class RosterPhoneScopeTest {

    private static final String MOM = "mom@x.com";
    private static final String KID = "kid@x.com";
    private static final String NO_PHONE = "nophone@x.com";
    private static final String NO_ACCOUNT = "invited@x.com";
    private static final String PLATFORM = "ops@sitprep.app";

    private GroupRepo groupRepo;
    private PlatformAccessService platform;
    private GroupViewService service;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        UserInfoRepo userRepo = mock(UserInfoRepo.class);
        platform = mock(PlatformAccessService.class);
        service = new GroupViewService(groupRepo, userRepo, mock(GroupPostRepo.class),
                mock(HouseholdManualMemberService.class), mock(HouseholdAccompanimentService.class),
                platform, mock(AgencyStaffService.class), mock(CheckInRequestService.class),
                mock(NotificationLogRepo.class), mock(UserSavedLocationRepo.class));
        when(userRepo.findByUserEmailIn(anyList())).thenReturn(List.of(
                user(MOM, "+1 801 555 0100"), user(KID, "801-555-0101"), user(NO_PHONE, "  ")));
    }

    private static UserInfo user(String email, String phone) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setPhone(phone);
        u.setGroupLocationSharing(new HashMap<>());
        return u;
    }

    private void group(String id, String type) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType(type);
        g.setOwnerEmail(MOM);
        g.setMemberEmails(new ArrayList<>(List.of(MOM, KID, NO_PHONE, NO_ACCOUNT)));
        when(groupRepo.findByGroupId(id)).thenReturn(Optional.of(g));
    }

    private Map<String, MemberSummary> roster(String groupId, String viewer) {
        Map<String, MemberSummary> out = new HashMap<>();
        for (MemberSummary m : service.buildMemberView(groupId, viewer).orElseThrow().members()) {
            out.put(m.email(), m);
        }
        return out;
    }

    @Test
    void aHouseholdMemberSeesHouseholdPhones() {
        group("hh", "Household");
        Map<String, MemberSummary> r = roster("hh", KID);
        assertThat(r.get(MOM).phone()).isEqualTo("+1 801 555 0100");
        assertThat(r.get(KID).phone()).isEqualTo("801-555-0101");
        assertThat(r.get(NO_PHONE).phone()).as("blank is absent, not a value").isNull();
        assertThat(r.get(NO_ACCOUNT).phone()).as("no account, nothing to show").isNull();
    }

    @Test
    void noPhonesInAGroupView() {
        group("hoa", "HOA/Neighborhood");
        roster("hoa", KID).values().forEach(m -> assertThat(m.phone()).isNull());
    }

    @Test
    void aPlatformAdminReadingAHouseholdGetsNoPhones() {
        group("hh", "Household");
        when(platform.resolve(PLATFORM)).thenReturn(new PlatformAccessService.PlatformAccess(
                PLATFORM, PlatformRole.SUPER_ADMIN, Set.of(), false));
        roster("hh", PLATFORM).values().forEach(m -> assertThat(m.phone()).isNull());
    }
}
