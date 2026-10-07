package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.ApiError;
import io.sitprep.sitprepapi.dto.ApiMeta;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdCompositionService;
import io.sitprep.sitprepapi.service.HouseholdCompositionService.CountsBelowNamedException;
import io.sitprep.sitprepapi.service.HouseholdCompositionService.CountsRequest;
import io.sitprep.sitprepapi.util.AuthUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Who a household plans for — one derivation for every surface
 * (household roster EXEC-B). See {@link HouseholdCompositionDto}.
 *
 * <ul>
 *   <li>{@code GET  /api/households/{id}/composition} — members.</li>
 *   <li>{@code PUT  /api/households/{id}/composition/counts} — admins. Body
 *       {@code {adults, teens, kids, infants, dogs, cats, otherPets}}, each
 *       optional (null = unchanged). 200 → the fresh composition. 409
 *       {@code BELOW_NAMED} when a band would count fewer than are named
 *       (body names the band, its {@code minimum} and what was {@code requested}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/households/{householdId}/composition")
public class HouseholdCompositionResource {

    private final HouseholdCompositionService service;
    private final HouseholdAccessService access;

    public HouseholdCompositionResource(HouseholdCompositionService service, HouseholdAccessService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<HouseholdCompositionDto> get(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadHousehold(caller, householdId);
        return ResponseEntity.ok(service.compose(householdId, caller));
    }

    @PutMapping("/counts")
    public ResponseEntity<?> setCounts(@PathVariable String householdId,
                                       @RequestBody CountsRequest body,
                                       HttpServletRequest req) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanAdminHousehold(caller, householdId);
        try {
            return ResponseEntity.ok(service.setCounts(householdId, body, caller));
        } catch (CountsBelowNamedException e) {
            Map<String, Object> out = new HashMap<>();
            out.put("data", null);
            out.put("error", new ApiError("BELOW_NAMED", e.getReason()));
            out.put("meta", ApiMeta.now());
            out.put("message", e.getReason());
            out.put("path", req.getRequestURI());
            out.put("band", e.band());
            out.put("minimum", e.minimum());
            out.put("requested", e.requested());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }
    }
}
