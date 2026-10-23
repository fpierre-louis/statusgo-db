package io.sitprep.sitprepapi.routine;

import com.fasterxml.jackson.databind.JsonNode;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.RoutineOfferDto;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.web.Idempotent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Household routines (EXEC-R1). Authenticated by the {@code /api/**} chain; household access is in
 * the service: members read, owner/admin write. The body is taken as a raw JSON tree so the service
 * can refuse every field it does not know (Spring disables FAIL_ON_UNKNOWN_PROPERTIES globally).
 */
@RestController
@RequestMapping("/api/households/{householdId}/routines")
public class HouseholdRoutineResource {

    private final HouseholdRoutineService service;

    public HouseholdRoutineResource(HouseholdRoutineService service) {
        this.service = service;
    }

    @GetMapping("/{routineKey}/offer")
    public ResponseEntity<RoutineOfferDto> offer(@PathVariable String householdId, @PathVariable String routineKey) {
        return ResponseEntity.ok(service.offer(householdId, routineKey, AuthUtils.requireAuthenticatedEmail()));
    }

    @PutMapping("/{routineKey}")
    @Idempotent
    public ResponseEntity<HouseholdRoutineDto> save(@PathVariable String householdId, @PathVariable String routineKey,
                                                    @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.ok(service.save(householdId, routineKey, AuthUtils.requireAuthenticatedEmail(), body));
    }

    @DeleteMapping("/{routineKey}")
    public ResponseEntity<Void> delete(@PathVariable String householdId, @PathVariable String routineKey) {
        service.delete(householdId, routineKey, AuthUtils.requireAuthenticatedEmail());
        return ResponseEntity.noContent().build();
    }
}
