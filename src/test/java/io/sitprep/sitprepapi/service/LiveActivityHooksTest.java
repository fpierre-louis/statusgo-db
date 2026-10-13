package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * live_activities P1 §3.3: where the situation card hears about a change.
 * Each hook fires after commit and hands off; these pin WHICH writes reach it.
 */
class LiveActivityHooksTest {

    private static final String MAYA = "maya@x.com";
    private static final String ADMIN = "dione@x.com";

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static Group group(String id, String alert) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id);
        g.setGroupType("Household");
        g.setAlert(alert);
        g.setOwnerEmail(ADMIN);
        g.setAdminEmails(new ArrayList<>(List.of(ADMIN)));
        g.setMemberEmails(new ArrayList<>(List.of(ADMIN, MAYA)));
        return g;
    }

    private static void commit() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
    }

    // ── UserInfoService: applied writes only ───────────────────────────

    private UserInfoService userInfoService(UserInfoRepo users, GroupRepo groups, LiveActivityPushService la) {
        UserInfoService s = new UserInfoService(users, mock(HouseholdEventService.class), groups,
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), mock(WebSocketMessageSender.class), mock(LocationPresenceService.class),
                mock(HouseholdProvisioningService.class));
        s.setLiveActivities(la);
        return s;
    }

    @Test
    void anAppliedStatusWriteUpdatesEachGroupWithARunningCheckIn_andOnlyThose() {
        UserInfoRepo users = mock(UserInfoRepo.class);
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        UserInfo maya = new UserInfo();
        maya.setUserEmail(MAYA);
        when(users.findByUserEmailIgnoreCase(MAYA)).thenReturn(Optional.of(maya));
        when(users.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));
        when(groups.findByMemberEmail(MAYA)).thenReturn(List.of(group("hh-1", "Active"), group("g-2", "Inactive")));

        TransactionSynchronizationManager.initSynchronization();
        userInfoService(users, groups, la).updateSelfStatusByEmail(MAYA, "SAFE", null, null);
        verifyNoInteractions(la);   // nothing before the commit
        commit();

        verify(la).situationChanged("hh-1");
        verify(la, never()).situationChanged("g-2");
    }

    @Test
    void aStaleReplayNeverReachesTheCard() {
        UserInfoRepo users = mock(UserInfoRepo.class);
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        Instant stored = Instant.now().minus(Duration.ofMinutes(5));
        UserInfo maya = new UserInfo();
        maya.setUserEmail(MAYA);
        maya.setUserStatus("SAFE");
        maya.setUserStatusLastUpdated(stored);
        when(users.findByUserEmailIgnoreCase(MAYA)).thenReturn(Optional.of(maya));
        when(groups.findByMemberEmail(MAYA)).thenReturn(List.of(group("hh-1", "Active")));

        userInfoService(users, groups, la).updateSelfStatusByEmail(MAYA, "HELP", null, stored.minusSeconds(3600));

        verifyNoInteractions(la);
    }

    // ── GroupService: start, end, continue ─────────────────────────────

    private GroupService groupService(GroupRepo groups, UserInfoRepo users, LiveActivityPushService la) {
        GroupService s = new GroupService(groups, users, mock(WebSocketMessageSender.class),
                mock(HouseholdEventService.class), mock(NotificationService.class), mock(CheckInRequestService.class));
        s.setLiveActivities(la);
        when(groups.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        return s;
    }

    @Test
    void endingACheckInSendsTheEndWithTheStartItHad_afterCommit() {
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        Group g = group("hh-1", "Active");
        Instant started = Instant.now().minus(Duration.ofMinutes(17));
        g.setAlertActivatedAt(started);
        when(groups.findByGroupId("hh-1")).thenReturn(Optional.of(g));

        TransactionSynchronizationManager.initSynchronization();
        groupService(groups, mock(UserInfoRepo.class), la).setAlert("hh-1", false, ADMIN);
        assertThat(g.getAlertActivatedAt()).isNull();   // cleared on the row…
        verifyNoInteractions(la);
        commit();

        verify(la).situationEnded("hh-1", started, LiveActivityStateBuilder.REASON_MANUAL, ADMIN);   // …but not for the card
    }

    @Test
    void startingACheckInRetiresOldTokens() {
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        when(groups.findByGroupId("hh-1")).thenReturn(Optional.of(group("hh-1", "Inactive")));

        groupService(groups, mock(UserInfoRepo.class), la).setAlert("hh-1", true, ADMIN);

        verify(la).situationStarted("hh-1");
        verify(la, never()).situationEnded(any(), any(), any(), any());
    }

    @Test
    void reassertingTheSameStateTouchesNothing() {
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        Group g = group("hh-1", "Active");
        g.setAlertActivatedAt(Instant.now());
        when(groups.findByGroupId("hh-1")).thenReturn(Optional.of(g));

        groupService(groups, mock(UserInfoRepo.class), la).setAlert("hh-1", true, ADMIN);

        verifyNoInteractions(la);
    }

    @Test
    void continuingACheckInUpdatesTheCardsEndTime() {
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        Group g = group("hh-1", "Active");
        g.setAlertActivatedAt(Instant.now().minus(Duration.ofHours(40)));
        when(groups.findByGroupId("hh-1")).thenReturn(Optional.of(g));

        groupService(groups, mock(UserInfoRepo.class), la).continueCheckIn("hh-1", ADMIN);

        verify(la).situationChanged("hh-1");
    }

    // ── GroupAlertDecayService ─────────────────────────────────────────

    @Test
    void theDecaySweepEndsEachCardWithItsOwnStart() {
        GroupRepo groups = mock(GroupRepo.class);
        LiveActivityPushService la = mock(LiveActivityPushService.class);
        Group a = group("hh-1", "Active");
        Instant startA = Instant.now().minus(Duration.ofHours(49));
        a.setAlertActivatedAt(startA);
        Group b = group("g-2", "Active");
        Instant startB = Instant.now().minus(Duration.ofHours(50));
        b.setAlertActivatedAt(startB);
        when(groups.findStaleActiveAlerts(any(), any(), any())).thenReturn(List.of(a, b));
        GroupAlertDecayService decay = new GroupAlertDecayService(groups, mock(HouseholdEventService.class),
                mock(UserInfoRepo.class), mock(NotificationService.class), mock(WebSocketMessageSender.class));
        decay.setLiveActivities(la);
        org.springframework.test.util.ReflectionTestUtils.setField(decay, "sweepBatchSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(decay, "decayHours", 48);

        TransactionSynchronizationManager.initSynchronization();
        assertThat(decay.decayOnce()).isEqualTo(2);
        verifyNoInteractions(la);
        commit();

        verify(la).situationEnded("hh-1", startA, LiveActivityStateBuilder.REASON_DECAY, null);
        verify(la).situationEnded("g-2", startB, LiveActivityStateBuilder.REASON_DECAY, null);
    }
}
