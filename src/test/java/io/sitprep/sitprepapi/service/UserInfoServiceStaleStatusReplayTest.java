package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MemberStatusFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * instant_roster D10 / B1. A queued self-status replay must not revert a newer status.
 *
 * <p>The FE outbox replays {@code PATCH /api/userinfo/me/status} with the
 * {@code updatedAt} stamped at tap time, possibly hours later. A status written
 * after that, from another device, the Watch or an admin, has to survive.</p>
 */
class UserInfoServiceStaleStatusReplayTest {

    private static final String MAYA = "maya@x.com";
    private static final String ADMIN = "dione@x.com";
    /** The stored status: SAFE, set 10 minutes ago from another device. */
    private static final Instant STORED_AT = Instant.now().minus(Duration.ofMinutes(10));

    private UserInfoRepo userInfoRepo;
    private HouseholdEventService events;
    private WebSocketMessageSender ws;
    private GroupRepo groupRepo;
    private UserInfoService service;
    private UserInfo maya;

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
        maya.setStatusColor("#1BBC9B");
        maya.setUserStatusLastUpdated(STORED_AT);
        when(userInfoRepo.findByUserEmailIgnoreCase(MAYA)).thenReturn(Optional.of(maya));
        when(userInfoRepo.save(any(UserInfo.class))).thenAnswer(inv -> inv.getArgument(0));

        Group hh = new Group();
        hh.setGroupId("hh-1");
        hh.setGroupType("Household");
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(hh));
    }

    @Test
    void anOlderReplayIsIgnoredAndTheCurrentStatusIsReturned() {
        // HELP tapped offline an hour ago, drained now, after the newer SAFE.
        Instant queuedAt = STORED_AT.minus(Duration.ofMinutes(50));

        MemberStatusFrame returned = service.updateSelfStatusByEmail(MAYA, "HELP", "#FFC107", queuedAt);

        assertEquals("SAFE", returned.status(), "the stored, newer status comes back");
        assertEquals("#1BBC9B", returned.color());
        assertEquals(STORED_AT, returned.updatedAt());
        assertEquals(MAYA, returned.email());
        assertEquals("SAFE", maya.getUserStatus(), "the row is untouched");
        assertEquals(STORED_AT, maya.getUserStatusLastUpdated());
        verify(userInfoRepo, never()).save(any());
        verifyNoInteractions(ws);
        verifyNoInteractions(events);
        verify(groupRepo, never()).findByMemberEmail(anyString());
    }

    @Test
    void anIgnoredReplayReturnsTheProxyNameOfWhoSetTheStoredStatus() {
        maya.setStatusSetByEmail(ADMIN);
        UserInfo admin = new UserInfo();
        admin.setUserEmail(ADMIN);
        admin.setUserFirstName("Dione");
        when(userInfoRepo.findByUserEmailIgnoreCase(ADMIN)).thenReturn(Optional.of(admin));

        MemberStatusFrame returned = service.updateSelfStatusByEmail(
                MAYA, "HELP", null, STORED_AT.minusSeconds(1));

        assertEquals("SAFE", returned.status());
        assertEquals("Dione", returned.setByName());
        assertEquals(ADMIN, maya.getStatusSetByEmail(), "the attribution is not cleared by an ignored write");
    }

    @Test
    void aNewerWriteIsApplied() {
        Instant tappedAt = STORED_AT.plus(Duration.ofMinutes(5));

        MemberStatusFrame returned = service.updateSelfStatusByEmail(MAYA, "HELP", "#FFC107", tappedAt);

        assertEquals("HELP", returned.status());
        assertEquals(tappedAt, returned.updatedAt());
        assertEquals("HELP", maya.getUserStatus());
        assertEquals(tappedAt, maya.getUserStatusLastUpdated());
        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), any());
        verify(events).recordSelfStatusWrite(MAYA, "HELP", true, STORED_AT);
    }

    @Test
    void anEqualStampIsApplied() {
        // An outbox retry of a write that committed but lost its response
        // arrives with the stored stamp. Re-applying it is idempotent and
        // matches the FE's newerSelfStatus, which lets an equal stamp through.
        MemberStatusFrame returned = service.updateSelfStatusByEmail(MAYA, "SAFE", "#1BBC9B", STORED_AT);

        assertEquals("SAFE", returned.status());
        assertEquals(STORED_AT, returned.updatedAt());
        verify(userInfoRepo).save(maya);
        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), any());
        verify(events).recordSelfStatusWrite(MAYA, "SAFE", false, STORED_AT);
    }

    @Test
    void aWriteWithNoStampIsAppliedAsToday() {
        Instant before = Instant.now();

        MemberStatusFrame returned = service.updateSelfStatusByEmail(MAYA, "HELP", null, null);

        assertEquals("HELP", returned.status());
        assertEquals("HELP", maya.getUserStatus());
        assertFalse(maya.getUserStatusLastUpdated().isBefore(before), "stamped with server now");
        verify(ws).sendHouseholdMemberStatus(eq("hh-1"), any());
    }

    @Test
    void aFutureStampIsClampedToServerNow() {
        Instant fastClock = Instant.now().plus(Duration.ofHours(2));

        MemberStatusFrame returned = service.updateSelfStatusByEmail(MAYA, "HELP", null, fastClock);

        assertEquals("HELP", returned.status());
        assertTrue(maya.getUserStatusLastUpdated().isBefore(fastClock),
                "a fast clock must not store a stamp that blocks every later write");
        assertFalse(maya.getUserStatusLastUpdated().isAfter(Instant.now()));
    }

    @Test
    void aStoredStampAheadOfServerNowDoesNotBlockAnHonestWrite() {
        // A row written by a fast clock before the clamp existed.
        maya.setUserStatusLastUpdated(Instant.now().plus(Duration.ofHours(1)));
        Instant tappedAt = Instant.now();

        MemberStatusFrame returned = service.updateSelfStatusByEmail(MAYA, "HELP", null, tappedAt);

        assertEquals("HELP", returned.status());
        assertEquals("HELP", maya.getUserStatus());
    }
}
