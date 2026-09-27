package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.constant.LocationSharing;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto;
import io.sitprep.sitprepapi.dto.MemberLocationFrame;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-2: {@code PATCH /api/userinfo/me/location} with the optional
 * {@code source}/{@code accuracyM} — the path a watch will call. The roster
 * frame carries the derived fields only to groups whose gate is open.
 */
class LocationPingFrameTest {

    private static final String ME = "ann@x.com";

    private UserInfoRepo userRepo;
    private GroupRepo groupRepo;
    private UserSavedLocationRepo placeRepo;
    private WebSocketMessageSender ws;
    private UserInfoService service;
    private UserInfo ann;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserInfoRepo.class);
        groupRepo = mock(GroupRepo.class);
        placeRepo = mock(UserSavedLocationRepo.class);
        ws = mock(WebSocketMessageSender.class);
        LocationPresenceService presence =
                new LocationPresenceService(placeRepo, mock(NominatimGeocodeService.class), null);
        service = new UserInfoService(userRepo, mock(HouseholdEventService.class), groupRepo,
                mock(PostService.class), mock(FollowService.class), mock(BlockService.class),
                new ObjectMapper(), ws, presence, mock(HouseholdProvisioningService.class));

        ann = new UserInfo();
        ann.setUserEmail(ME);
        ann.setGroupLocationSharing(new HashMap<>(Map.of("open", LocationSharing.ALWAYS,
                "closed", LocationSharing.NEVER)));
        when(userRepo.findByUserEmailIgnoreCase(ME)).thenReturn(Optional.of(ann));
        when(userRepo.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));

        UserSavedLocation school = new UserSavedLocation();
        school.setId(4L);
        school.setOwnerEmail(ME);
        school.setName("Lincoln Elementary");
        school.setKind("school");
        school.setLatitude(40.4);
        school.setLongitude(-111.8);
        school.setSharePresence(true);
        when(placeRepo.findByOwnerEmailIgnoreCaseAndSharePresenceTrue(anyString())).thenReturn(List.of(school));

        when(groupRepo.findByMemberEmail(ME)).thenReturn(List.of(group("open"), group("closed")));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static Group group(String id) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupType("Neighborhood");
        return g;
    }

    private void commit() {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
    }

    @Test
    void aWatchFixIsRecordedAndFramedOnlyWhereTheGateIsOpen() {
        service.updateLastKnownLocationByEmail(ME, 40.4001, -111.8, "watch", 7.0);
        commit();

        assertThat(ann.getLocationSource()).isEqualTo("watch");
        assertThat(ann.getLocationAccuracyM()).isEqualTo(7);
        assertThat(ann.getCurrentPlaceId()).isEqualTo(4L);

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(ws).sendGroupMemberLocation(eq("open"), frame.capture());
        verify(ws, never()).sendGroupMemberLocation(eq("closed"), any());
        MemberLocationFrame f = (MemberLocationFrame) frame.getValue();
        assertThat(f.atPlace()).isEqualTo(new GroupMemberViewDto.AtPlace(
                "Lincoln Elementary", "school", ann.getCurrentPlaceSince()));
        assertThat(f.locationSource()).isEqualTo("watch");
        assertThat(f.locationAccuracyM()).isEqualTo(7);
    }

    @Test
    void anOlderClientSendingOnlyLatLngStillWorks() {
        service.updateLastKnownLocationByEmail(ME, 40.4001, -111.8);
        commit();
        assertThat(ann.getLastKnownLat()).isEqualTo(40.4001);
        assertThat(ann.getLocationSource()).isNull();
        assertThat(ann.getLocationAccuracyM()).isNull();
    }
}
