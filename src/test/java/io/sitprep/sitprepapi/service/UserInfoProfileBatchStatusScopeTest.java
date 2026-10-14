package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.ProfileSummaryDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The profile batch resolves identity for anyone, but status and last-active
 * only for the viewer and people they share a group with (group page audit
 * FEED-MEMBERS-3, 2026-10-13). It used to answer "is this address safe, and
 * when were they last on" for any signed-in caller and any address.
 */
class UserInfoProfileBatchStatusScopeTest {

    private static final Instant AT = Instant.parse("2026-10-13T12:00:00Z");

    private UserInfoRepo userInfoRepo;
    private GroupRepo groupRepo;
    private UserInfoService service;

    @BeforeEach
    void setUp() {
        userInfoRepo = mock(UserInfoRepo.class);
        groupRepo = mock(GroupRepo.class);
        service = new UserInfoService(userInfoRepo, mock(HouseholdEventService.class), groupRepo,
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), mock(WebSocketMessageSender.class), mock(LocationPresenceService.class),
                mock(HouseholdProvisioningService.class));

        // me@x.com shares one group with mate@x.com (member) and boss@x.com (owner).
        Group shared = new Group();
        shared.setGroupId("g1");
        shared.setOwnerEmail("Boss@x.com");
        shared.setMemberEmails(List.of("me@x.com", "MATE@x.com"));
        shared.setAdminEmails(List.of());
        when(groupRepo.findByMemberEmail("me@x.com")).thenReturn(List.of(shared));
        when(groupRepo.findByAdminEmail(anyString())).thenReturn(List.of());
        when(groupRepo.findByOwnerEmailIgnoreCase(anyString())).thenReturn(List.of());

        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(
                user("me@x.com"), user("mate@x.com"), user("boss@x.com"), user("stranger@x.com")));
    }

    private static UserInfo user(String email) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(email.substring(0, 1).toUpperCase());
        u.setUserStatus("SAFE");
        u.setStatusColor("#1BBC9B");
        u.setUserStatusLastUpdated(AT);
        u.setLastActiveAt(AT);
        return u;
    }

    private Map<String, ProfileSummaryDto> batch(String viewer) {
        return service.getProfileSummariesByEmails(
                        List.of("me@x.com", "mate@x.com", "boss@x.com", "stranger@x.com"), viewer)
                .stream().collect(Collectors.toMap(ProfileSummaryDto::email, Function.identity()));
    }

    @Test
    void coMembersAndSelfGetStatusAndPresence() {
        Map<String, ProfileSummaryDto> out = batch("Me@x.com");
        for (String email : List.of("me@x.com", "mate@x.com", "boss@x.com")) {
            ProfileSummaryDto p = out.get(email);
            assertEquals("SAFE", p.userStatus(), email);
            assertEquals(AT, p.userStatusLastUpdated(), email);
            assertNotNull(p.userStatusFreshUntil(), email);
            assertEquals(AT, p.lastActiveAt(), email);
        }
    }

    @Test
    void aStrangerGetsIdentityButNoStatusOrPresence() {
        ProfileSummaryDto s = batch("me@x.com").get("stranger@x.com");
        assertEquals("S", s.firstName());
        assertNull(s.userStatus());
        assertNull(s.statusColor());
        assertNull(s.userStatusLastUpdated());
        assertNull(s.userStatusFreshUntil());
        assertNull(s.lastActiveAt());
    }

    @Test
    void aViewerInNoGroupSeesOnlyThemselves() {
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of());
        Map<String, ProfileSummaryDto> out = batch("stranger@x.com");
        assertEquals("SAFE", out.get("stranger@x.com").userStatus());
        assertNull(out.get("me@x.com").userStatus());
        assertNull(out.get("mate@x.com").lastActiveAt());
    }

    @Test
    void anOwnerSeesTheSubgroupsBelowTheirGroupButAMemberDoesNot() {
        Group sub = new Group();
        sub.setGroupId("g2");
        sub.setOwnerEmail("lead@x.com");
        sub.setMemberEmails(List.of("stranger@x.com"));
        sub.setAdminEmails(List.of());
        Group parent = new Group();
        parent.setGroupId("g1");
        parent.setOwnerEmail("boss@x.com");
        parent.setMemberEmails(List.of("me@x.com", "boss@x.com"));
        parent.setAdminEmails(List.of());
        parent.setSubGroupIDs(List.of("g2"));
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(parent));
        when(groupRepo.findById("g2")).thenReturn(java.util.Optional.of(sub));

        assertEquals("SAFE", batch("boss@x.com").get("stranger@x.com").userStatus());
        // A plain member of the parent does not oversee its subgroups.
        assertNull(batch("me@x.com").get("stranger@x.com").userStatus());
    }

    @Test
    void noViewerSeesNoStatus() {
        assertTrue(batch(null).values().stream().allMatch(p -> p.userStatus() == null && p.lastActiveAt() == null));
    }
}
