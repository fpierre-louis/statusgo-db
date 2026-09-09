package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.domain.GroupInviteRedemption;
import io.sitprep.sitprepapi.repo.GroupInviteRedemptionRepo;
import io.sitprep.sitprepapi.repo.GroupInviteRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupInviteServiceTest {

    private GroupInviteRepo inviteRepo;
    private GroupInviteRedemptionRepo redemptionRepo;
    private GroupService groupService;
    private GroupInviteService service;

    @BeforeEach
    void setUp() {
        inviteRepo = mock(GroupInviteRepo.class);
        redemptionRepo = mock(GroupInviteRedemptionRepo.class);
        groupService = mock(GroupService.class);
        service = new GroupInviteService(inviteRepo, redemptionRepo, groupService);
    }

    @Test
    void redeemJoinsGroupAndConsumesInviteOnce() {
        GroupInvite invite = invite("invite-1", "grp-1", 1, 0);
        Group group = group("grp-1");

        when(redemptionRepo.findByInviteIdAndUserEmail("invite-1", "new@x.com"))
                .thenReturn(Optional.empty());
        when(inviteRepo.findByIdForUpdate("invite-1")).thenReturn(Optional.of(invite));
        when(groupService.selfJoin("grp-1", "new@x.com")).thenReturn(group);
        when(inviteRepo.save(invite)).thenReturn(invite);

        var result = service.redeem("invite-1", "New@X.com");

        assertTrue(result.isOk());
        assertFalse(result.alreadyRedeemed());
        assertEquals(group, result.group());
        assertEquals(1, invite.getUsedCount());
        verify(redemptionRepo).save(argThat(redemption ->
                "invite-1".equals(redemption.getInviteId())
                        && "new@x.com".equals(redemption.getUserEmail())
                        && "grp-1".equals(redemption.getGroupId())));
    }

    @Test
    void repeatedRedeemBySameUserIsIdempotentAndDoesNotBurnAnotherUse() {
        GroupInviteRedemption redemption = new GroupInviteRedemption();
        redemption.setInviteId("invite-1");
        redemption.setUserEmail("new@x.com");
        redemption.setGroupId("grp-1");
        redemption.setRedeemedAt(Instant.now());
        Group group = group("grp-1");

        when(redemptionRepo.findByInviteIdAndUserEmail("invite-1", "new@x.com"))
                .thenReturn(Optional.of(redemption));
        when(groupService.selfJoin("grp-1", "new@x.com")).thenReturn(group);

        var result = service.redeem("invite-1", "NEW@X.COM");

        assertTrue(result.isOk());
        assertTrue(result.alreadyRedeemed());
        assertEquals(group, result.group());
        verify(inviteRepo, never()).save(any());
        verify(redemptionRepo, never()).save(any());
    }

    @Test
    void exhaustedInviteDoesNotJoinGroup() {
        GroupInvite invite = invite("invite-1", "grp-1", 1, 1);

        when(redemptionRepo.findByInviteIdAndUserEmail("invite-1", "new@x.com"))
                .thenReturn(Optional.empty());
        when(inviteRepo.findByIdForUpdate("invite-1")).thenReturn(Optional.of(invite));

        var result = service.redeem("invite-1", "new@x.com");

        assertEquals(GroupInviteService.InviteState.EXHAUSTED, result.state());
        assertFalse(result.isOk());
        verify(groupService, never()).selfJoin(anyString(), anyString());
        verify(redemptionRepo, never()).save(any());
    }

    @Test
    void redeemHouseholdJoinsHouseholdDirectlyAndConsumesInviteOnce() {
        GroupInvite invite = invite("hh-invite-1", "hh-1", 1, 0);
        Group household = group("hh-1");
        household.setGroupType("Household");

        when(redemptionRepo.findByInviteIdAndUserEmail("hh-invite-1", "new@x.com"))
                .thenReturn(Optional.empty());
        when(inviteRepo.findByIdForUpdate("hh-invite-1")).thenReturn(Optional.of(invite));
        when(groupService.getGroupByPublicId("hh-1")).thenReturn(household);
        when(groupService.joinHouseholdByInvite("hh-1", "new@x.com")).thenReturn(household);
        when(inviteRepo.save(invite)).thenReturn(invite);

        var result = service.redeemHousehold("hh-invite-1", "New@X.com");

        assertTrue(result.isOk());
        assertFalse(result.alreadyRedeemed());
        assertEquals(household, result.group());
        assertEquals(1, invite.getUsedCount());
        verify(groupService).joinHouseholdByInvite("hh-1", "new@x.com");
        verify(redemptionRepo).save(argThat(redemption ->
                "hh-invite-1".equals(redemption.getInviteId())
                        && "new@x.com".equals(redemption.getUserEmail())
                        && "hh-1".equals(redemption.getGroupId())));
    }

    @Test
    void redeemHouseholdRejectsNonHouseholdInvite() {
        GroupInvite invite = invite("invite-1", "grp-1", 1, 0);
        Group group = group("grp-1");
        group.setGroupType("Neighborhood");

        when(redemptionRepo.findByInviteIdAndUserEmail("invite-1", "new@x.com"))
                .thenReturn(Optional.empty());
        when(inviteRepo.findByIdForUpdate("invite-1")).thenReturn(Optional.of(invite));
        when(groupService.getGroupByPublicId("grp-1")).thenReturn(group);

        var result = service.redeemHousehold("invite-1", "new@x.com");

        assertEquals(GroupInviteService.InviteState.NOT_FOUND, result.state());
        verify(groupService, never()).joinHouseholdByInvite(anyString(), anyString());
        verify(redemptionRepo, never()).save(any());
        verify(inviteRepo, never()).save(any());
    }

    private static GroupInvite invite(String id, String groupId, Integer maxUses, int usedCount) {
        GroupInvite invite = new GroupInvite();
        invite.setId(id);
        invite.setGroupId(groupId);
        invite.setIssuedByEmail("owner@x.com");
        invite.setIssuedAt(Instant.now());
        invite.setExpiresAt(Instant.now().plusSeconds(3600));
        invite.setMaxUses(maxUses);
        invite.setUsedCount(usedCount);
        return invite;
    }

    private static Group group(String groupId) {
        Group group = new Group();
        group.setGroupId(groupId);
        group.setGroupName("Neighborhood Ready");
        return group;
    }
}
