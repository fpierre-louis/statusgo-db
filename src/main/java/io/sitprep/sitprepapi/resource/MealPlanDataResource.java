package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.service.MealPlanDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

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

    /** Idempotent create/update by payload.ownerEmail */
    @PostMapping
    public ResponseEntity<MealPlanData> save(@RequestBody MealPlanData mealPlanData) {
        try {
            return ResponseEntity.ok(service.upsert(mealPlanData));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Update by route email; 404 if none exists */
    @PutMapping("/{ownerEmail}")
    public ResponseEntity<MealPlanData> update(@PathVariable String ownerEmail,
                                               @RequestBody MealPlanData mealPlanData) {
        try {
            return ResponseEntity.ok(service.updateByOwnerEmail(ownerEmail, mealPlanData));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /** Delete by route email (no-op if missing) */
    @DeleteMapping("/{ownerEmail}")
    public ResponseEntity<Void> delete(@PathVariable String ownerEmail) {
        service.deleteByOwnerEmail(ownerEmail);
        return ResponseEntity.noContent().build();
    }
}
