package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserSavedLocationRepo;
import io.sitprep.sitprepapi.service.MapPlaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A group id is not a household id.
 *
 * <p>GET /api/households/{id}/map-places checked household membership correctly
 * and still leaked, because it never checked the group's <em>type</em>. Pass a
 * business or neighborhood circle id and any member of that circle passed the
 * membership check; both household-scoped queries in the service then returned
 * empty — no MeetingPlace or EvacuationPlan row can carry a non-household group
 * id — which fired the owner-email fallback and handed back the circle owner's
 * personal meeting places and shelter destinations: name, address, phone,
 * coordinates.</p>
 *
 * <p>HouseholdAccessService has always filtered on groupType. This resource's
 * hand-rolled membership check did not. That gap was the whole bug, and it is
 * the argument for the convention: two checks that look equivalent, one of which
 * quietly is not.</p>
 *
 * <p>The last test covers the service directly, because the fallback is the
 * dangerous half — the next caller should not have to know that passing the
 * wrong kind of group turns a read into a disclosure.</p>
 */
class MapPlaceHouseholdTypeGateTest {

    private static final String OWNER = "owner@x.com";
    private static final String CIRCLE_MEMBER = "colleague@x.com";

    private GroupRepo groupRepo;
    private MeetingPlaceRepo meetingPlaceRepo;
    private EvacuationPlanRepo evacuationPlanRepo;
    private MapPlaceService service;
    private MapPlaceResource resource;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        meetingPlaceRepo = mock(MeetingPlaceRepo.class);
        evacuationPlanRepo = mock(EvacuationPlanRepo.class);
        service = new MapPlaceService(meetingPlaceRepo, evacuationPlanRepo, mock(UserSavedLocationRepo.class));
        resource = new MapPlaceResource(service, groupRepo);

        when(groupRepo.findByGroupId("biz-1")).thenReturn(Optional.of(businessCircle()));
        when(groupRepo.findByGroupId("hh-1")).thenReturn(Optional.of(household()));

        // No row can carry a non-household group id, which is what made the
        // fallback fire. Model that faithfully rather than stubbing it away.
        when(meetingPlaceRepo.findByHouseholdId(anyString())).thenReturn(List.of());
        when(evacuationPlanRepo.findByHouseholdId(anyString())).thenReturn(List.of());
        when(meetingPlaceRepo.findByOwnerEmail(OWNER)).thenReturn(List.of(ownersPrivateMeetingPlace()));
        when(evacuationPlanRepo.findByOwnerEmail(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private Group businessCircle() {
        Group g = new Group();
        g.setGroupId("biz-1");
        g.setGroupType("Business");
        g.setGroupName("Reyes Plumbing");
        g.setOwnerEmail(OWNER);
        g.setMemberEmails(List.of(OWNER, CIRCLE_MEMBER));
        return g;
    }

    private Group household() {
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupType("Household");
        g.setGroupName("The Reyes household");
        g.setOwnerEmail(OWNER);
        g.setMemberEmails(List.of(OWNER));
        return g;
    }

    private MeetingPlace ownersPrivateMeetingPlace() {
        MeetingPlace m = new MeetingPlace();
        m.setId(1L);
        m.setName("Grandma's house");
        m.setAddress("12 Willow Ln");
        m.setLat(40.76);
        m.setLng(-111.89);
        return m;
    }

    @Test
    void aBusinessCircleIdIsNotAHousehold() {
        authenticateAs(CIRCLE_MEMBER);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> resource.mapPlaces("biz-1"));
        // 404, not 403 — it is not a household you lack access to, it is not a
        // household, and saying so does not confirm which ids exist.
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void aRealHouseholdMemberStillGetsTheirPlaces() {
        authenticateAs(OWNER);
        List<MapPlaceDto> places = resource.mapPlaces("hh-1");
        assertTrue(places.stream().anyMatch(p -> "Grandma's house".equals(p.name())),
                "the owner-email fallback must still serve a genuine household");
    }

    @Test
    void theServiceItselfRefusesToFallBackForANonHousehold() {
        // Direct service call: the resource is not the only thing standing
        // between a wrong group type and the owner's private addresses.
        List<MapPlaceDto> places = service.forHousehold(businessCircle(), CIRCLE_MEMBER);
        assertTrue(places.stream().noneMatch(p -> "Grandma's house".equals(p.name())),
                "a non-household group must never reach the owner-email fallback");
        verify(meetingPlaceRepo, never()).findByOwnerEmail(OWNER);
    }
}
