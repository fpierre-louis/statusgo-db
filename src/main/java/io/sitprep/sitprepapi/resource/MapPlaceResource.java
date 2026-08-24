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
 *
 * <p><b>The group must actually be a household (2026-08-24).</b> The membership
 * check above was correct and still let the wrong people through, because it
 * never checked the group's <em>type</em>. Any group id passed here — a
 * business, a neighborhood circle — found a real row, and any member of that
 * group passed {@code isMember}. Both household-scoped queries in the service
 * then came back empty, because no MeetingPlace or EvacuationPlan row can carry
 * a non-household group id, which fired the owner-email fallback and returned
 * the <em>group owner's personal</em> meeting places and shelter destinations —
 * name, address, phone number and coordinates — to every member of a circle
 * they merely administer.</p>
 *
 * <p>{@link io.sitprep.sitprepapi.service.HouseholdAccessService} has always
 * filtered on {@code groupType}; this hand-rolled check did not. That gap is the
 * entire bug.</p>
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
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                // 404, not 403: a non-household id is not a household that you
                // lack access to, it is not a household. Saying so also avoids
                // confirming which group ids exist.
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
