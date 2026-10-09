package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdEvent;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MemberStatusFrame;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Household drawer gameplan §5.4 — a reply is a reply even when nothing
 * changed.
 *
 * <p>The frame and the event used to fire only when the VALUE changed, so a
 * SAFE member answering an ask with SAFE again — the most common reply there
 * is — updated a timestamp and told nobody. The asker's row sat on "Asked"
 * until a refetch.</p>
 */
class UserInfoServiceStatusBroadcastTest {

    private static final String ADMIN = "dione@x.com";
    private static final String MAYA = "maya@x.com";
    private static final Instant EARLIER = Instant.now().minus(Duration.ofHours(3));

    private UserInfoRepo userInfoRepo;
    private HouseholdEventService events;
    private WebSocketMessageSender ws;
    private UserInfoService service;
    private UserInfo maya;
    private GroupRepo groupRepo;

    @BeforeEach
    void setUp() {
        userInfoRepo = mock(UserInfoRepo.class);
        events = mock(HouseholdEventService.class);
        ws = mock(WebSocketMessageSender.class);
        groupRepo = mock(GroupRepo.class);
        service = new UserInfoService(userInfoRepo, events, groupRepo,
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), ws, mock(LocationPresenceService.class),
                mock(HouseholdProvisioningService.class));

        maya = new UserInfo();
        maya.setId("u-maya");
        maya.setUserEmail(MAYA);
        maya.setBaseHouseholdId("hh-1");
        maya.setUserStatus("SAFE");
        maya.setUserStatusLastUpdated(EARLIER);
        UserInfo admin = new UserInfo();
        admin.setUserEmail(ADMIN);
        admin.setUserFirstName("Dione");
        when(userInfoRepo.findByUserEmailIgnoreCase(MAYA)).thenReturn(Optional.of(maya));
        when(userInfoRepo.findByUserEmailIgnoreCase(ADMIN)).thenReturn(Optional.of(admin));
        when(userInfoRepo.save(any(UserInfo.class))).thenAnswer(inv -> inv.getArgument(0));

        Group hh = new Group();
        hh.setGroupId("hh-1");
        hh.setGroupType("Household");
        hh.setMemberEmails(List.of(ADMIN, MAYA));
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(hh));
    }

    @Test
    void safeWhileAlreadySafeStillSendsTheFrame() {
        service.updateSelfStatusByEmail(MAYA, "SAFE", null, null);

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), frame.capture());
        verify(ws).sendGroupMemberStatus(eq("hh-1"), any());
        MemberStatusFrame f = (MemberStatusFrame) frame.getValue();
        assertEquals("SAFE", f.status());
        assertNull(f.setByName(), "a self-report carries no proxy name");
        // The recorder gets the facts it needs to tell a reply from a repeat.
        verify(events).recordSelfStatusWrite(MAYA, "SAFE", false, EARLIER);
    }

    @Test
    void aProxySafeWhileAlreadySafeSendsTheFrameWithWhoSetIt() {
        MemberStatusFrame returned = service.setStatusForMember(MAYA, "SAFE", ADMIN);

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), frame.capture());
        MemberStatusFrame f = (MemberStatusFrame) frame.getValue();
        assertEquals("SAFE", f.status());
        assertEquals("Dione", f.setByName(), "a live proxy write keeps its attribution");
        assertEquals("Dione", returned.setByName());
        verify(events).recordStatusSetForMember(ADMIN, MAYA, "SAFE", false, EARLIER);
    }

    // ── the recorder's rule: a reply, or a change ────────────────────────────

    private HouseholdEventRepo eventRepo;
    private CheckInRequestService asks;
    private HouseholdEventService recorder;
    private Group calmHousehold;

    private void realRecorder() {
        eventRepo = mock(HouseholdEventRepo.class);
        asks = mock(CheckInRequestService.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        calmHousehold = new Group();
        calmHousehold.setGroupId("hh-1");
        calmHousehold.setGroupType("Household");
        calmHousehold.setAlert("Not Active");
        when(groupRepo.findByMemberEmail(MAYA)).thenReturn(List.of(calmHousehold));
        when(eventRepo.save(any(HouseholdEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        recorder = new HouseholdEventService(eventRepo, mock(UserInfoRepo.class), groupRepo,
                mock(HouseholdRitualRepo.class), mock(WebSocketMessageSender.class),
                new ObjectMapper(), asks);
    }

    private String savedKind() {
        ArgumentCaptor<HouseholdEvent> e = ArgumentCaptor.forClass(HouseholdEvent.class);
        verify(eventRepo).save(e.capture());
        return e.getValue().getKind();
    }

    @Test
    void anUnchangedAnswerToAnOpenAskIsRecordedAsAReply() {
        realRecorder();
        when(asks.askedAt(calmHousehold, MAYA)).thenReturn(EARLIER.plus(Duration.ofHours(1)));

        recorder.recordSelfStatusWrite(MAYA, "SAFE", false, EARLIER);

        assertEquals(HouseholdEventService.KIND_CHECKIN_REPLIED, savedKind());
    }

    @Test
    void anUnchangedStatusWithNoAskRecordsNothing() {
        realRecorder();
        when(asks.askedAt(calmHousehold, MAYA)).thenReturn(null);

        recorder.recordSelfStatusWrite(MAYA, "SAFE", false, EARLIER);

        verify(eventRepo, never()).save(any());
    }

    @Test
    void anAskAlreadyAnsweredIsNotOpen() {
        realRecorder();
        // Asked BEFORE her last write: she already replied to it.
        when(asks.askedAt(calmHousehold, MAYA)).thenReturn(EARLIER.minus(Duration.ofHours(1)));

        recorder.recordSelfStatusWrite(MAYA, "SAFE", false, EARLIER);

        verify(eventRepo, never()).save(any());
    }

    @Test
    void aChangedValueWithNoAskIsStillStatusChanged() {
        realRecorder();
        when(asks.askedAt(calmHousehold, MAYA)).thenReturn(null);

        recorder.recordSelfStatusWrite(MAYA, "HELP", true, EARLIER);

        assertEquals(HouseholdEventService.KIND_STATUS_CHANGED, savedKind());
    }

    @Test
    void aChangedValueAfterAnAnsweredAskIsStatusChangedNotAReply() {
        realRecorder();
        // Asked, answered SAFE an hour later (EARLIER), now HELP: no ask is open.
        when(asks.askedAt(calmHousehold, MAYA)).thenReturn(EARLIER.minus(Duration.ofHours(1)));

        recorder.recordSelfStatusWrite(MAYA, "HELP", true, EARLIER);

        assertEquals(HouseholdEventService.KIND_STATUS_CHANGED, savedKind());
    }

    @Test
    void theLegacyPatchPathPassesThePriorWriteTime() {
        when(userInfoRepo.findById("u-maya")).thenReturn(Optional.of(maya));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.patchUserById("u-maya", java.util.Map.of("userStatus", "HELP"));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        // Not null: an unknown prior write time would make any ask look open.
        verify(events).recordSelfStatusWrite(MAYA, "HELP", true, EARLIER);
    }

    // ── 2026-10-09: every status write reaches every roster ──────────────────

    private void commit(Runnable write) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            write.run();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theLegacyPatchIsTheSelfWriteFrameStampAndAttributionIncluded() {
        when(userInfoRepo.findById("u-maya")).thenReturn(Optional.of(maya));
        maya.setStatusSetByEmail(ADMIN); // Dione had answered for her earlier

        commit(() -> service.patchUserById("u-maya", java.util.Map.of("userStatus", "help", "statusColor", "#FFC107")));

        assertEquals("HELP", maya.getUserStatus(), "validated and normalised, not written raw");
        assertNull(maya.getStatusSetByEmail(), "her own answer is not 'set by Dione'");
        assertTrue(maya.getUserStatusLastUpdated().isAfter(EARLIER), "stamped");
        // The open rosters hear it — this path used to send no frame at all.
        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), any());
        verify(ws).sendGroupMemberStatus(eq("hh-1"), any());
    }

    @Test
    void aLegacyPatchOfAnUnknownStatusIsRefusedNotStored() {
        when(userInfoRepo.findById("u-maya")).thenReturn(Optional.of(maya));
        assertThrows(IllegalArgumentException.class,
                () -> service.patchUserById("u-maya", java.util.Map.of("userStatus", "NO RESPONSE")));
        verify(ws, never()).sendHouseholdMemberStatus(any(), any());
    }

    @Test
    void thePutEchoCannotUndoAStatus() {
        when(userInfoRepo.findById("u-maya")).thenReturn(Optional.of(maya));
        UserInfo staleEcho = new UserInfo();
        staleEcho.setUserFirstName("Maya");
        staleEcho.setUserStatus("NO RESPONSE");
        staleEcho.setStatusColor("Gray");

        service.updateUserById("u-maya", staleEcho);

        assertEquals("SAFE", maya.getUserStatus());
        assertEquals(EARLIER, maya.getUserStatusLastUpdated());
    }

    @Test
    void everyHouseholdTheyAreInHearsTheFrameNotOnlyTheBaseOne() {
        Group base = new Group();
        base.setGroupId("hh-1");
        base.setGroupType("Household");
        Group second = new Group();
        second.setGroupId("hh-2");
        second.setGroupType("Household");
        Group school = new Group();
        school.setGroupId("org-1");
        school.setGroupType("School");
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(base, second, school));

        commit(() -> service.updateSelfStatusByEmail(MAYA, "SAFE", null, null));

        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), any());
        verify(ws).sendHouseholdMemberStatus(eq("hh-2"), any());
        verify(ws, never()).sendHouseholdMemberStatus(eq("org-1"), any());
        verify(ws).sendGroupMemberStatus(eq("org-1"), any());
        verify(ws).sendGroupMemberStatus(eq("hh-2"), any());
    }
}
