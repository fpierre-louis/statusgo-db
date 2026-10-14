package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AgencyStaff;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.ProfileSummaryDto;
import io.sitprep.sitprepapi.repo.AgencyStaffRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
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
 * {@code POST /userinfo/profiles/batch} answers only for people the caller has
 * a relationship with (B2 / BE-04, owner decision D7).
 *
 * <p>It used to resolve a name and avatar for any 500 addresses to any
 * signed-in account, so "which of these addresses are SitPrep users, and who"
 * was one request away. The follow / followers / blocked lists, which the
 * server chooses, keep their identity rows ({@code getProfileSummariesByEmails}).</p>
 */
class ProfilesBatchScopeTest {

    private static final Instant AT = Instant.parse("2026-10-13T12:00:00Z");
    private static final List<String> ASKED = List.of(
            "me@x.com", "mate@x.com", "requester@x.com", "staffer@x.com", "stranger@x.com");

    private UserInfoRepo userInfoRepo;
    private GroupRepo groupRepo;
    private AgencyStaffRepo staffRepo;
    private UserInfoService service;

    @BeforeEach
    void setUp() {
        userInfoRepo = mock(UserInfoRepo.class);
        groupRepo = mock(GroupRepo.class);
        staffRepo = mock(AgencyStaffRepo.class);
        service = new UserInfoService(userInfoRepo, mock(HouseholdEventService.class), groupRepo,
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), mock(WebSocketMessageSender.class), mock(LocationPresenceService.class),
                mock(HouseholdProvisioningService.class));
        service.setAgencyStaffRepo(staffRepo);

        // boss@x.com owns g1: me + mate are members, requester@x.com asked to join.
        Group g1 = new Group();
        g1.setGroupId("g1");
        g1.setOwnerEmail("boss@x.com");
        g1.setAdminEmails(List.of("boss@x.com"));
        g1.setMemberEmails(List.of("boss@x.com", "me@x.com", "mate@x.com"));
        g1.setPendingMemberEmails(List.of("Requester@x.com"));
        when(groupRepo.findByMemberEmail(anyString())).thenAnswer(i -> {
            String e = i.getArgument(0);
            return g1.getMemberEmails().contains(e) ? List.of(g1) : List.of();
        });
        when(groupRepo.findByAdminEmail(anyString())).thenAnswer(i ->
                "boss@x.com".equals(i.getArgument(0)) ? List.of(g1) : List.of());
        when(groupRepo.findByOwnerEmailIgnoreCase(anyString())).thenAnswer(i ->
                "boss@x.com".equals(i.getArgument(0)) ? List.of(g1) : List.of());
        AgencyStaff s = new AgencyStaff();
        s.setGroupId("g1");
        s.setUserEmail("staffer@x.com");
        when(staffRepo.findByGroupIdOrderByAddedAtAsc("g1")).thenReturn(List.of(s));

        // The repo only ever answers the addresses it is asked for.
        when(userInfoRepo.findByUserEmailIn(anyList())).thenAnswer(i -> {
            List<String> asked = i.getArgument(0);
            return asked.stream().map(ProfilesBatchScopeTest::user).toList();
        });
    }

    private static UserInfo user(String email) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(email.substring(0, 1).toUpperCase());
        u.setUserStatus("SAFE");
        u.setUserStatusLastUpdated(AT);
        u.setLastActiveAt(AT);
        return u;
    }

    private Map<String, ProfileSummaryDto> batch(String viewer) {
        return service.getRelatedProfileSummaries(ASKED, viewer).stream()
                .collect(Collectors.toMap(ProfileSummaryDto::email, Function.identity()));
    }

    @Test
    void aStrangersAddressIsAbsentNotJustStatusless() {
        Map<String, ProfileSummaryDto> out = batch("me@x.com");
        assertTrue(out.containsKey("mate@x.com"));
        assertFalse(out.containsKey("stranger@x.com"));
        // A plain member does not see who asked to join, or the agency staff.
        assertFalse(out.containsKey("requester@x.com"));
        assertFalse(out.containsKey("staffer@x.com"));
    }

    @Test
    void theOwnerSeesRequestersAndStaffByName() {
        Map<String, ProfileSummaryDto> out = batch("boss@x.com");
        assertEquals("R", out.get("requester@x.com").firstName());
        assertEquals("S", out.get("staffer@x.com").firstName());
        assertFalse(out.containsKey("stranger@x.com"));
        // Named, not watched: a requester is not a co-member.
        assertNull(out.get("requester@x.com").userStatus());
    }

    @Test
    void lastActiveAtNeverShips() {
        assertTrue(batch("boss@x.com").values().stream().allMatch(p -> p.lastActiveAt() == null));
        assertTrue(service.getProfileSummariesByEmails(ASKED, "me@x.com").stream()
                .allMatch(p -> p.lastActiveAt() == null));
    }

    @Test
    void coMemberStatusStaysForTheLiveRosterReaders() {
        // SubGroups, the map's subgroup rows and the household crisis timeline
        // still read status here (open for F4 / map / household).
        assertEquals("SAFE", batch("me@x.com").get("mate@x.com").userStatus());
    }

    @Test
    void anAccountInNoGroupGetsOnlyThemselves() {
        Map<String, ProfileSummaryDto> out = batch("stranger@x.com");
        assertEquals(List.of("stranger@x.com"), List.copyOf(out.keySet()));
        verify(userInfoRepo).findByUserEmailIn(argThat(c -> c.size() == 1));
    }

    @Test
    void theServerChosenListsKeepIdentity() {
        // Follows / followers / blocked: the server picked the addresses.
        assertEquals(ASKED.size(), service.getProfileSummariesByEmails(ASKED, "me@x.com").size());
    }
}
