package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.service.MealPlanDataService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/mealPlans")
@CrossOrigin(origins = "http://localhost:3000")
public class MealPlanDataResource {

    private static final Logger logger = LoggerFactory.getLogger(MealPlanDataResource.class);

    private final MealPlanDataService service;

    public MealPlanDataResource(MealPlanDataService service) {
        this.service = service;
    }

    /** Frontend: GET /api/mealPlans/{ownerEmail} */
    @GetMapping("/{ownerEmail}")
    public ResponseEntity<MealPlanData> getByOwner(@PathVariable String ownerEmail) {
        return service.findByOwnerEmailCI(ownerEmail)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** Admin/dev helper (optional) */
    @GetMapping
    public List<MealPlanData> getAll() {
        logger.info("Fetching all meal plans");
        return service.getAllMealPlans();
    }

    /** Idempotent create/update — owner is the verified caller. */
    @PostMapping
    public ResponseEntity<MealPlanData> save(@RequestBody MealPlanData mealPlanData) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        mealPlanData.setOwnerEmail(caller);
        try {
            return ResponseEntity.ok(service.upsert(mealPlanData));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Update by route email; must match the verified caller. 404 if none exists. */
    @PutMapping("/{ownerEmail}")
    public ResponseEntity<MealPlanData> update(@PathVariable String ownerEmail,
                                               @RequestBody MealPlanData mealPlanData) {
        ensurePathOwnerIsCaller(ownerEmail);
        mealPlanData.setOwnerEmail(ownerEmail);
        try {
            return ResponseEntity.ok(service.updateByOwnerEmail(ownerEmail, mealPlanData));
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
