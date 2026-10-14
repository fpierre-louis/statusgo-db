package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.domain.GroupInviteRedemption;
import io.sitprep.sitprepapi.dto.CommunityDiscoverDto;
import io.sitprep.sitprepapi.dto.GroupPreviewDto;
import io.sitprep.sitprepapi.dto.JoinOutcomeDto;
import io.sitprep.sitprepapi.repo.GroupInviteRedemptionRepo;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A household never reaches a public path (B2: JN-03, JN-04, JN-05, JN-17;
 * owner decisions D4, D5).
 *
 * <p>Measured on prod before this: an anonymous {@code GET /groups/601/preview}
 * returned a household's street address and 7-decimal coordinate, Discover
 * listed two households with their addresses, and a Public-flagged household
 * was instantly joinable by any signed-in account. Each path is pinned here
 * through the REAL code (the earlier preview test mocked the builder with a
 * null address, so it could not see the leak it was guarding).</p>
 */
class HouseholdPublicPathsTest {

    private GroupRepo groupRepo;
    private UserInfoRepo userInfoRepo;
    private GroupService groups;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        groups = new GroupService(groupRepo, userInfoRepo, mock(WebSocketMessageSender.class),
                mock(HouseholdEventService.class), mock(NotificationService.class),
                mock(CheckInRequestService.class));
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Group put(String id, String type, String privacy) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id + " name");
        g.setGroupType(type);
        g.setPrivacy(privacy);
        g.setOwnerEmail("owner@x.com");
        g.setOwnerName("Olive Owner");
        g.setAdminEmails(new ArrayList<>(List.of("owner@x.com")));
        g.setMemberEmails(new ArrayList<>(List.of("owner@x.com", "kid@x.com")));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setAddress("1450 N Traverse Ridge Rd, Draper, UT 84020, USA");
        g.setLatitude(40.4876543);
        g.setLongitude(-111.8512345);
        g.setAlert("Active");
        when(groupRepo.findByGroupId(id)).thenReturn(Optional.of(g));
        return g;
    }

    // ---- /preview ----

    @Test
    void previewOfAHouseholdIs404EvenWhenFlaggedPublic() {
        put("hh", "Household", "Public");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> groups.getGroupPreview("hh", null));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void publicOrgPreviewShowsCityAndZipOnly() {
        put("org", "HOA/Neighborhood", "Public");
        GroupPreviewDto p = groups.getGroupPreview("org", null);
        assertEquals("Draper", p.city());
        assertEquals("84020", p.zipCode());
        String json = p.toString();
        assertFalse(json.contains("Traverse"), "street leaked: " + json);
        assertFalse(json.contains("40.48"), "coordinate leaked: " + json);
        // A stranger doesn't learn that a check-in is running.
        assertFalse(p.alertActive());
    }

    @Test
    void privateOrgPreviewShowsNoLocation() {
        put("org", "Church", "Private");
        GroupPreviewDto p = groups.getGroupPreview("org", "someone@x.com");
        assertNull(p.city());
        assertNull(p.zipCode());
    }

    @Test
    void aMemberStillSeesThatACheckInIsRunning() {
        put("org", "HOA/Neighborhood", "Public");
        assertTrue(groups.getGroupPreview("org", "kid@x.com").alertActive());
    }

    @Test
    void cityParsingNeverReturnsAStreet() {
        assertEquals("Lehi", GroupService.cityOf("123 Main St, Lehi, UT 84043, USA"));
        assertNull(GroupService.cityOf("123 Main St"));
        assertNull(GroupService.cityOf("123 Main St, UT, USA"));
        assertNull(GroupService.cityOf(null));
        assertEquals("84043", GroupService.zipOf("123 Main St, Lehi, UT 84043, USA"));
        assertNull(GroupService.zipOf("84043 Main St"));
    }

    // ---- self-join ----

    @Test
    void selfJoinRefusesAHouseholdWith404AndWritesNothing() {
        put("hh", "Household", "Public");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> groups.selfJoin("hh", "stranger@x.com"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(groupRepo, never()).save(any());
    }

    @Test
    void nullPrivacyIsARequestNotAnInstantJoin() {
        Group g = put("org", "Church", null);
        Group after = groups.selfJoin("org", "new@x.com");
        assertTrue(after.getPendingMemberEmails().contains("new@x.com"));
        assertFalse(g.getMemberEmails().contains("new@x.com"));
        JoinOutcomeDto o = JoinOutcomeDto.of(after, "new@x.com");
        assertEquals("PENDING", o.status());
        assertEquals("PENDING", o.viewerRole());
        assertEquals(2, o.memberCount());
    }

    @Test
    void publicOrgJoinOutcomeSaysMemberAndCarriesNoRoster() {
        put("org", "HOA/Neighborhood", "Public");
        JoinOutcomeDto o = JoinOutcomeDto.of(groups.selfJoin("org", "new@x.com"), "new@x.com");
        assertEquals("MEMBER", o.status());
        assertEquals("MEMBER", o.viewerRole());
        assertEquals(3, o.memberCount());
        assertFalse(o.toString().contains("owner@x.com"));
    }

    // ---- invite resolve + redeem (JN-17) ----

    @Test
    void aHouseholdTokenIsNotFoundOnTheGroupInvitePaths() {
        GroupInviteRepo invites = mock(GroupInviteRepo.class);
        GroupInviteRedemptionRepo redemptions = mock(GroupInviteRedemptionRepo.class);
        GroupService gs = mock(GroupService.class);
        GroupInviteService svc = new GroupInviteService(invites, redemptions, gs);
        Group hh = new Group();
        hh.setGroupId("hh");
        hh.setGroupType("Household");
        when(gs.getGroupByPublicId("hh")).thenReturn(hh);
        GroupInvite inv = new GroupInvite();
        inv.setId("tok");
        inv.setGroupId("hh");
        inv.setExpiresAt(Instant.now().plusSeconds(3600));
        inv.setUsedCount(0);
        when(invites.findById("tok")).thenReturn(Optional.of(inv));
        when(invites.findByIdForUpdate("tok")).thenReturn(Optional.of(inv));
        when(redemptions.findByInviteIdAndUserEmail(anyString(), anyString())).thenReturn(Optional.empty());

        assertEquals(GroupInviteService.InviteState.NOT_FOUND, svc.validateGroupInvite("tok").state());
        assertNull(svc.validateGroupInvite("tok").invite(), "the household id must not ship");
        assertEquals(GroupInviteService.InviteState.NOT_FOUND, svc.redeem("tok", "x@x.com").state());
        verify(gs, never()).selfJoin(anyString(), anyString());
    }

    @Test
    void aRevokedTokenDoesNotReAdmitAPriorRedeemer() {
        GroupInviteRepo invites = mock(GroupInviteRepo.class);
        GroupInviteRedemptionRepo redemptions = mock(GroupInviteRedemptionRepo.class);
        GroupService gs = mock(GroupService.class);
        GroupInviteService svc = new GroupInviteService(invites, redemptions, gs);
        GroupInviteRedemption r = new GroupInviteRedemption();
        r.setInviteId("tok");
        r.setUserEmail("removed@x.com");
        r.setGroupId("org");
        when(redemptions.findByInviteIdAndUserEmail("tok", "removed@x.com")).thenReturn(Optional.of(r));
        GroupInvite inv = new GroupInvite();
        inv.setId("tok");
        inv.setGroupId("org");
        inv.setRevokedAt(Instant.now());
        when(invites.findById("tok")).thenReturn(Optional.of(inv));

        assertEquals(GroupInviteService.InviteState.REVOKED, svc.redeem("tok", "removed@x.com").state());
        verify(gs, never()).selfJoin(anyString(), anyString());
        assertEquals(GroupInviteService.InviteState.REVOKED, svc.redeemHousehold("tok", "removed@x.com").state());
        verify(gs, never()).joinHouseholdByInvite(anyString(), anyString());
    }

    // ---- Discover (JN-05, BE-16) ----

    @Test
    void discoverReadsTheBoundingBoxAndNeverListsAHousehold() {
        NominatimGeocodeService geocode = mock(NominatimGeocodeService.class);
        CommunityDiscoverService discover = new CommunityDiscoverService(
                groupRepo, userInfoRepo, mock(GroupPostRepo.class), geocode);
        Group org = put("org", "HOA/Neighborhood", "Public");
        org.setLatitude(40.39);
        org.setLongitude(-111.85);
        // As if the SQL predicate ever let one through: the second line holds.
        Group hh = put("hh", "Household", "Public");
        hh.setLatitude(40.391);
        hh.setLongitude(-111.851);
        when(groupRepo.findPublicInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(org, hh));

        CommunityDiscoverDto out = discover.discover(40.39, -111.85, 200, "viewer@x.com", false);

        assertEquals(List.of("org"), out.nearbyGroups().stream().map(CommunityDiscoverDto.NearbyGroup::groupId).toList());
        verify(groupRepo, never()).findAll();
        verify(groupRepo).findPublicInBounds(
                doubleThat(v -> v < 40.39), doubleThat(v -> v > 40.39),
                doubleThat(v -> v < -111.85), doubleThat(v -> v > -111.85));
    }
}
