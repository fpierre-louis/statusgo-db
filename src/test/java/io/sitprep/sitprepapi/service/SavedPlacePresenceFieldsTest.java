package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.dto.UserSavedLocationDto;
import io.sitprep.sitprepapi.dto.UserSavedLocationWriteDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-2 (V83): saved places carry {@code kind}, {@code sharePresence} (opt-in,
 * default off) and {@code radiusM}; the new {@code UserInfo} fields derived
 * from a fix can be neither read raw nor written by a client.
 */
class SavedPlacePresenceFieldsTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private UserSavedLocationService service(UserSavedLocationRepo repo) {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        return new UserSavedLocationService(repo, null);
    }

    @Test
    void createAcceptsThePresenceFields() {
        var repo = mock(UserSavedLocationRepo.class);
        UserSavedLocation saved = service(repo).create("a@x.com", new UserSavedLocationWriteDto(
                "Lincoln Elementary", null, 40.4, -111.8, null, "School", true, 5000));
        assertThat(saved.getKind()).isEqualTo("school");
        assertThat(saved.isSharePresence()).isTrue();
        assertThat(saved.getRadiusM()).as("clamped to 2000").isEqualTo(2000);
    }

    @Test
    void presenceIsOffByDefault() {
        var repo = mock(UserSavedLocationRepo.class);
        UserSavedLocation saved = service(repo).create("a@x.com",
                new UserSavedLocationWriteDto("Work", null, 40.4, -111.8, null));
        assertThat(saved.getKind()).isNull();
        assertThat(saved.isSharePresence()).isFalse();
        assertThat(saved.getRadiusM()).isEqualTo(150);
    }

    @Test
    void anUnknownKindIsA400() {
        var repo = mock(UserSavedLocationRepo.class);
        assertThatThrownBy(() -> service(repo).create("a@x.com", new UserSavedLocationWriteDto(
                "Gym", null, 40.4, -111.8, null, "gym", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateIsPartialForThePresenceFields() {
        var repo = mock(UserSavedLocationRepo.class);
        UserSavedLocation row = new UserSavedLocation();
        row.setId(7L);
        row.setOwnerEmail("a@x.com");
        row.setName("School");
        row.setLatitude(40.4);
        row.setLongitude(-111.8);
        row.setKind("school");
        row.setSharePresence(true);
        row.setRadiusM(300);
        when(repo.findById(7L)).thenReturn(Optional.of(row));
        var svc = service(repo);

        svc.update(7L, new UserSavedLocationWriteDto("Renamed", null, null, null, null));
        assertThat(row.getKind()).isEqualTo("school");
        assertThat(row.isSharePresence()).isTrue();
        assertThat(row.getRadiusM()).isEqualTo(300);

        svc.update(7L, new UserSavedLocationWriteDto(null, null, null, null, null, null, false, 20));
        assertThat(row.isSharePresence()).isFalse();
        assertThat(row.getRadiusM()).as("clamped to 50").isEqualTo(50);
    }

    @Test
    void wireShapeOnBothDtos() throws Exception {
        UserSavedLocationWriteDto in = json.readValue(
                "{\"name\":\"School\",\"latitude\":40.4,\"longitude\":-111.8,"
                        + "\"kind\":\"school\",\"sharePresence\":true,\"radiusM\":300}",
                UserSavedLocationWriteDto.class);
        assertThat(in.kind()).isEqualTo("school");
        assertThat(in.sharePresence()).isTrue();
        assertThat(in.radiusM()).isEqualTo(300);

        UserSavedLocation row = in.toNewEntity("a@x.com");
        row.setId(3L);
        JsonNode out = json.valueToTree(UserSavedLocationDto.from(row));
        assertThat(out.get("kind").asText()).isEqualTo("school");
        assertThat(out.get("sharePresence").asBoolean()).isTrue();
        assertThat(out.get("radiusM").asInt()).isEqualTo(300);
        assertThat(out.has("ownerEmail")).isFalse();
    }

    @Test
    void theDerivedUserInfoFieldsNeverSerializeRaw() {
        UserInfo u = new UserInfo();
        u.setUserEmail("a@x.com");
        u.setLocationSource("watch");
        u.setLocationAccuracyM(10);
        u.setCurrentPlaceId(5L);
        u.setCurrentPlaceSince(Instant.now());
        u.setLastSeenNearLabel("Dry Creek");
        u.setLastSeenNearLat(40.4);
        u.setLastSeenNearLng(-111.8);
        JsonNode node = json.valueToTree(u);
        for (String f : UserInfoService.SERVER_DERIVED_FIELDS) {
            assertThat(node.has(f)).as(f + " must not appear on the raw entity").isFalse();
        }
    }

    @Test
    void theReflectivePatchCannotSetThem() {
        UserInfoRepo repo = mock(UserInfoRepo.class);
        UserInfo u = new UserInfo();
        u.setId("u-1");
        u.setUserEmail("a@x.com");
        when(repo.findById("u-1")).thenReturn(Optional.of(u));
        when(repo.save(any(UserInfo.class))).thenAnswer(i -> i.getArgument(0));
        UserInfoService svc = new UserInfoService(repo, mock(HouseholdEventService.class),
                mock(GroupRepo.class), mock(PostService.class), mock(FollowService.class),
                mock(BlockService.class), json, mock(WebSocketMessageSender.class),
                mock(LocationPresenceService.class), mock(HouseholdProvisioningService.class));

        svc.patchUserById("u-1", Map.of(
                "currentPlaceId", 99L,
                "lastSeenNearLabel", "Somewhere I am not",
                "locationSource", "watch",
                "locationAccuracyM", 3));

        assertThat(u.getCurrentPlaceId()).isNull();
        assertThat(u.getLastSeenNearLabel()).isNull();
        assertThat(u.getLocationSource()).isNull();
        assertThat(u.getLocationAccuracyM()).isNull();
    }
}
