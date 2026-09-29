package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.AgencyCapability;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.dto.MapPoiDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-5: the map DTO fields the redesign reads and nothing used to fill.
 */
class MapDtoEnrichmentTest {

    private static final Instant T = Instant.parse("2026-09-26T18:30:00Z");

    private GroupRepo groupRepo;
    private PostRepo postRepo;
    private UserInfoRepo userRepo;
    private MapDiscoveryService discovery;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        postRepo = mock(PostRepo.class);
        userRepo = mock(UserInfoRepo.class);
        ExternalPoiCacheService external = mock(ExternalPoiCacheService.class);
        when(external.getPois(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        discovery = new MapDiscoveryService(groupRepo, postRepo, userRepo, external, null);

        when(groupRepo.findPublicInBounds(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(
                        group("city", "City of Lehi", true, EnumSet.of(AgencyCapability.SEND_AREA_ALERTS)),
                        group("fire", "Lehi Fire", true, EnumSet.of(AgencyCapability.MANAGE_WORK)),
                        group("hoa", "Dry Creek HOA", false, EnumSet.noneOf(AgencyCapability.class))));
        when(groupRepo.findAllById(anyList())).thenReturn(List.of(
                group("church", "Lehi 5th Ward", false, EnumSet.noneOf(AgencyCapability.class))));

        when(postRepo.findAidInBounds(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(
                        post(1L, "ask", Post.PostPriority.URGENT, "frank@x.com", null),
                        post(2L, "offer", Post.PostPriority.MEDIUM, "admin@x.com", "church"),
                        post(3L, "offer", Post.PostPriority.HIGH, "noname@x.com", null)));
        UserInfo frank = new UserInfo();
        frank.setUserEmail("frank@x.com");
        frank.setUserFirstName("Frank");
        frank.setUserLastName("Diaz");
        UserInfo noName = new UserInfo();
        noName.setUserEmail("noname@x.com");
        when(userRepo.findByUserEmailIn(anyList())).thenReturn(List.of(frank, noName));
    }

    private static Group group(String id, String name, boolean agency, Set<AgencyCapability> caps) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(name);
        g.setGroupType(agency ? "Agency" : "HOA/Neighborhood");
        g.setAgencyAuthorized(agency);
        g.setAgencyCapabilities(caps);
        g.setLatitude(40.39);
        g.setLongitude(-111.85);
        g.setCreatedAt(T);
        g.setMemberEmails(new ArrayList<>());
        return g;
    }

    private static Post post(long id, String kind, Post.PostPriority priority, String author, String asGroup) {
        Post p = new Post();
        p.setId(id);
        p.setKind(kind);
        p.setPriority(priority);
        p.setRequesterEmail(author);
        p.setAuthoredAsGroupId(asGroup);
        p.setTitle("Post " + id);
        p.setLatitude(40.391);
        p.setLongitude(-111.851);
        p.setCreatedAt(T.plusSeconds(id));
        return p;
    }

    private Map<String, MapPoiDto> pois(String viewer) {
        Map<String, MapPoiDto> out = new HashMap<>();
        for (MapPoiDto p : discovery.discover(40.3, -111.95, 40.5, -111.75, 14, viewer).pois()) {
            out.put(p.id(), p);
        }
        return out;
    }

    @Test
    void canSendAreaAlertsIsTheV80CapabilityForAgenciesOnly() {
        Map<String, MapPoiDto> r = pois("me@x.com");
        assertThat(r.get("group:city").canSendAreaAlerts()).isTrue();
        assertThat(r.get("group:fire").canSendAreaAlerts()).isFalse();
        assertThat(r.get("group:hoa").canSendAreaAlerts()).isNull();
        assertThat(r.get("post:1").canSendAreaAlerts()).isNull();
        assertThat(r.get("group:city").createdAt()).isEqualTo(T);
    }

    @Test
    void priorityReasonIsOnlyThePostersOwnUrgent() {
        Map<String, MapPoiDto> r = pois("me@x.com");
        assertThat(r.get("post:1").priorityReason()).isEqualTo("poster-urgent");
        assertThat(r.get("post:3").priorityReason()).as("HIGH is not URGENT").isNull();
        assertThat(r.get("post:2").priorityReason()).isNull();
        assertThat(r.get("post:1").createdAt()).isEqualTo(T.plusSeconds(1));
    }

    @Test
    void authorDisplayNameIsANameOrNullNeverAnEmail() {
        Map<String, MapPoiDto> r = pois("me@x.com");
        assertThat(r.get("post:1").authorDisplayName()).isEqualTo("Frank Diaz");
        assertThat(r.get("post:2").authorDisplayName()).as("speaks as a group").isEqualTo("Lehi 5th Ward");
        assertThat(r.get("post:3").authorDisplayName()).as("no name on file").isNull();
        r.values().forEach(p -> {
            if (p.authorDisplayName() != null) assertThat(p.authorDisplayName()).doesNotContain("@");
        });
    }

    @Test
    void asksAndNamesAreForSignedInViewersOnly() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> kinds = ArgumentCaptor.forClass(Set.class);

        pois("me@x.com");
        verify(postRepo).findAidInBounds(any(), kinds.capture(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(kinds.getValue()).contains("ask");

        postRepo = mock(PostRepo.class);
        when(postRepo.findAidInBounds(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(post(2L, "offer", Post.PostPriority.MEDIUM, "frank@x.com", null)));
        ExternalPoiCacheService external = mock(ExternalPoiCacheService.class);
        when(external.getPois(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of());
        discovery = new MapDiscoveryService(groupRepo, postRepo, userRepo, external, null);

        Map<String, MapPoiDto> guest = pois(null);
        verify(postRepo).findAidInBounds(any(), kinds.capture(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(kinds.getValue()).doesNotContain("ask");
        assertThat(guest.get("post:2").authorDisplayName()).isNull();
    }

    @Test
    void cachedPoiPayloadsRoundTripThroughTheCanonicalConstructor() throws Exception {
        // ExternalPoiCacheService stores MapPoiDto[] as JSON and reads it back.
        // The record now has a second (pre-BE-5) constructor; Jackson must still
        // bind the canonical one, and a payload cached before BE-5 must load.
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        MapPoiDto full = pois("me@x.com").get("post:1");
        assertThat(json.readValue(json.writeValueAsString(full), MapPoiDto.class)).isEqualTo(full);

        MapPoiDto old = json.readValue("{\"id\":\"overpass:node/1\",\"family\":\"park\","
                + "\"source\":\"overpass\",\"name\":\"Park\",\"lat\":40.1,\"lng\":-111.9}", MapPoiDto.class);
        assertThat(old.name()).isEqualTo("Park");
        assertThat(old.priorityReason()).isNull();

        var point = json.readValue("{\"lat\":40.1,\"lng\":-111.9,\"accuracyM\":8,\"source\":\"watch\"}",
                io.sitprep.sitprepapi.dto.LiveLocationDtos.LiveLocationPointRequest.class);
        assertThat(point.source()).isEqualTo("watch");
        assertThat(point.accuracyM()).isEqualTo(8.0);
    }

    // ── MapPlaceDto ────────────────────────────────────────────────────────

    @Test
    void mapPlacesCarryTheirOwnTierAndDeployVerbatim() {
        MeetingPlaceRepo meets = mock(MeetingPlaceRepo.class);
        EvacuationPlanRepo evacs = mock(EvacuationPlanRepo.class);
        MeetingPlace m = new MeetingPlace();
        m.setId(5L);
        m.setName("Grandma's");
        m.setMeetingTier(MeetingPlaceTier.OUT_OF_TOWN);
        m.setDeploy(true);
        m.setLat(40.1);
        m.setLng(-111.9);
        EvacuationPlan e = new EvacuationPlan();
        e.setId(6L);
        e.setShelterName("High school gym");
        e.setDeploy(false);
        when(meets.findByHouseholdId("hh")).thenReturn(List.of(m));
        when(evacs.findByHouseholdId("hh")).thenReturn(List.of(e));
        Group hh = group("hh", "The Lees", false, EnumSet.noneOf(AgencyCapability.class));
        hh.setGroupType("Household");

        Map<String, MapPlaceDto> byId = new HashMap<>();
        for (MapPlaceDto p : new MapPlaceService(meets, evacs, mock(io.sitprep.sitprepapi.repo.OriginLocationRepo.class), mock(io.sitprep.sitprepapi.repo.PlanActivationRepo.class))
                .forHousehold(hh, null)) {
            byId.put(p.id(), p);
        }
        assertThat(byId.get("meetup:5").tier()).isEqualTo("OUT_OF_TOWN");
        assertThat(byId.get("meetup:5").deploy()).isTrue();
        assertThat(byId.get("shelter:6").tier()).isNull();
        assertThat(byId.get("shelter:6").deploy()).isFalse();
        assertThat(byId.get("group:hh").tier()).isNull();
        assertThat(byId.get("group:hh").deploy()).isNull();
    }
}
