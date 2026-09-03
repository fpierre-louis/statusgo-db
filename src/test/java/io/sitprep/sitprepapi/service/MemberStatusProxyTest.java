package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MemberStatusFrame;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An admin answering FOR somebody (2026-09-03).
 *
 * <p>Three surfaces have offered this control for a long time and all three
 * wrote it through {@code PATCH /userinfo/{id}}, which is self-only — so every
 * one of them 403s. The write now exists; these pin the two things that make it
 * honest rather than merely working.</p>
 *
 * <p><b>A proxy report is not a reply.</b> Without {@code statusSetByEmail} the
 * roster renders "Checked in 4m ago" about a person nobody has heard from, and
 * the timeline prints a sentence somebody did not say.</p>
 */
class MemberStatusProxyTest {

    private static final String ADMIN = "admin@x.com";
    private static final String MAYA = "maya@x.com";

    private UserInfoRepo userInfoRepo;
    private HouseholdEventService events;
    private UserInfoService service;
    private UserInfo maya;

    @BeforeEach
    void setUp() {
        userInfoRepo = mock(UserInfoRepo.class);
        events = mock(HouseholdEventService.class);
        GroupRepo groupRepo = mock(GroupRepo.class);
        service = new UserInfoService(userInfoRepo, events, groupRepo,
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(WebSocketMessageSender.class), mock(NominatimGeocodeService.class),
                mock(HouseholdProvisioningService.class));

        maya = new UserInfo();
        maya.setId("u-maya");
        maya.setUserEmail(MAYA);
        maya.setUserStatus("NO RESPONSE");
        when(userInfoRepo.findByUserEmailIgnoreCase(MAYA)).thenReturn(Optional.of(maya));
        when(userInfoRepo.save(any(UserInfo.class))).thenAnswer(inv -> inv.getArgument(0));

        Group hh = new Group();
        hh.setGroupId("hh-1");
        hh.setGroupType("Household");
        hh.setMemberEmails(List.of(ADMIN, MAYA));
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of(hh));
    }

    @Test
    void aProxyWriteRecordsWhoAnswered() {
        MemberStatusFrame frame = service.setStatusForMember(MAYA, "SAFE", ADMIN);

        assertEquals("SAFE", maya.getUserStatus());
        assertEquals(ADMIN, maya.getStatusSetByEmail(),
                "without this the roster cannot tell a reply from somebody answering for them");
        assertEquals("SAFE", frame.status());
    }

    @Test
    void aSelfWriteCLEARSAnEarlierProxyAttribution() {
        // THE RULING, and the reason the column is written on every path rather
        // than only the proxy one: a fresh reply must not sit under "marked
        // safe by Dad" from an hour ago.
        service.setStatusForMember(MAYA, "SAFE", ADMIN);
        assertEquals(ADMIN, maya.getStatusSetByEmail());

        service.updateSelfStatusByEmail(MAYA, "HELP", null, null);

        assertNull(maya.getStatusSetByEmail(), "she answered — nobody answered for her");
        assertEquals("HELP", maya.getUserStatus());
    }

    @Test
    void anAdminSettingTheirOWNStatusIsASelfReport() {
        // Not a special case for tidiness: routing it through the proxy path
        // would stamp the admin as their own proxy and print "Dione marked
        // Dione safe" in the household's timeline.
        UserInfo admin = new UserInfo();
        admin.setId("u-admin");
        admin.setUserEmail(ADMIN);
        when(userInfoRepo.findByUserEmailIgnoreCase(ADMIN)).thenReturn(Optional.of(admin));

        service.setStatusForMember(ADMIN, "SAFE", ADMIN);

        assertNull(admin.getStatusSetByEmail());
        verify(events).recordStatusChangedForActor(ADMIN, "SAFE");
        verify(events, never()).recordStatusSetForMember(anyString(), anyString(), anyString());
    }

    @Test
    void theTimelineGetsADIFFERENTSentenceForAProxy() {
        // "Maya replied — safe" is false when Dione set it, and recording the
        // admin under the existing kind would print "Dione replied — safe"
        // about Maya's status. A proxy report is a different sentence, so it is
        // a different row.
        service.setStatusForMember(MAYA, "SAFE", ADMIN);

        verify(events).recordStatusSetForMember(ADMIN, MAYA, "SAFE");
        verify(events, never()).recordStatusChangedForActor(anyString(), anyString());
    }

    @Test
    void anUnknownStatusIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setStatusForMember(MAYA, "SLIGHTLY WORRIED", ADMIN));
        assertEquals("NO RESPONSE", maya.getUserStatus(), "a refused write changes nothing");
    }

    @Test
    void aProxyWriteWithoutAnActorIsRefused() {
        // The actor is the whole point. A null one would write an
        // indistinguishable-from-self report through the admin path.
        assertThrows(IllegalArgumentException.class,
                () -> service.setStatusForMember(MAYA, "SAFE", null));
    }
}
