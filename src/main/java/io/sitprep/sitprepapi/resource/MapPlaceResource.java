package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.MapPlaceDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.MapPlaceService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Unified household map "places" feed — home, meeting places, shelters, and the
 * caller's saved locations in one typed payload (gap B of
 * docs/MAP_REBUILD_PLAN.md). Replaces the frontend's localStorage assembly.
 *
 * <p>Auth: verified Firebase token required; the caller must be a member,
 * admin, or owner of the household. Membership is checked server-side so a
 * signed-in user can't read another household's places by guessing its id.</p>
 */
@RestController
@RequestMapping("/api/households")
public class MapPlaceResource {

    private final MapPlaceService mapPlaceService;
    private final GroupRepo groupRepo;

    public MapPlaceResource(MapPlaceService mapPlaceService, GroupRepo groupRepo) {
        this.mapPlaceService = mapPlaceService;
        this.groupRepo = groupRepo;
    }

    @GetMapping("/{householdId}/map-places")
    public List<MapPlaceDto> mapPlaces(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        Group household = groupRepo.findByGroupId(householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));
        if (!isMember(household, caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this household");
        }
        return mapPlaceService.forHousehold(household, caller);
    }

    private static boolean isMember(Group g, String email) {
        if (email == null) return false;
        String needle = email.trim();
        return eq(g.getOwnerEmail(), needle)
                || containsCi(g.getAdminEmails(), needle)
                || containsCi(g.getMemberEmails(), needle);
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static boolean containsCi(List<String> list, String needle) {
        if (list == null) return false;
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }
}
