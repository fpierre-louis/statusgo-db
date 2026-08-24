package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.dto.MealPlanDto;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.MealPlanDataService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mealPlans")
@CrossOrigin(origins = "http://localhost:3000")
public class MealPlanDataResource {

    private final MealPlanDataService service;
    private final HouseholdAccessService access;

    public MealPlanDataResource(MealPlanDataService service,
                                HouseholdAccessService access) {
        this.service = service;
        this.access = access;
    }

    /**
     * Frontend: GET /api/mealPlans/{ownerEmail}. Household plan-sharing
     * has members reading the head's plan, so the path can target a
     * different user — but only if the caller shares a household with
     * them. Otherwise 403.
     */
    @GetMapping("/{ownerEmail}")
    public ResponseEntity<MealPlanDto> getByOwner(@PathVariable String ownerEmail) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        access.requireCanReadPlanDataFor(caller, ownerEmail);
        return service.findByOwnerEmailCI(ownerEmail)
                .map(MealPlanDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // GET /api/mealPlans (dump-all, labelled "admin/dev helper") was DELETED
    // 2026-08-24. Auth-only, no FE caller. Its real value to an attacker was not
    // the menus: it was a complete ownerEmail → householdId map, which turns
    // every id-taking household route into a lookup instead of a guess.
    //
    // Do not re-add. A cross-tenant read belongs behind
    // PlatformPermission.VIEW_PII with an audit record (UserInfoResource:80-81).

    /** Idempotent create/update — owner is the verified caller. */
    @PostMapping
    public ResponseEntity<MealPlanDto> save(@RequestBody MealPlanData mealPlanData) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        mealPlanData.setOwnerEmail(caller);
        try {
            return ResponseEntity.ok(MealPlanDto.from(service.upsert(mealPlanData)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Update by route email; must match the verified caller. 404 if none exists. */
    @PutMapping("/{ownerEmail}")
    public ResponseEntity<MealPlanDto> update(@PathVariable String ownerEmail,
                                              @RequestBody MealPlanData mealPlanData) {
        ensurePathOwnerIsCaller(ownerEmail);
        mealPlanData.setOwnerEmail(ownerEmail);
        try {
            return ResponseEntity.ok(MealPlanDto.from(service.updateByOwnerEmail(ownerEmail, mealPlanData)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /** Delete by route email; must match the verified caller. */
    @DeleteMapping("/{ownerEmail}")
    public ResponseEntity<Void> delete(@PathVariable String ownerEmail) {
        ensurePathOwnerIsCaller(ownerEmail);
        service.deleteByOwnerEmail(ownerEmail);
        return ResponseEntity.noContent().build();
    }

    private void ensurePathOwnerIsCaller(String pathOwnerEmail) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (pathOwnerEmail == null || !pathOwnerEmail.equalsIgnoreCase(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Meal plan belongs to a different user");
        }
    }
}
