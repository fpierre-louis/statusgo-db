import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.service.MealPlanDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/mealPlans")
public class MealPlanDataResource {

    @Autowired
    private MealPlanDataService service;

    @Autowired
    private MealPlanDataRepo repository; // ✅ Moved outside & added @Autowired

    @GetMapping("/{ownerEmail}")
    public ResponseEntity<?> getMealPlansByOwner(@PathVariable String ownerEmail) {
        List<MealPlanData> mealPlans = service.getMealPlansByOwner(ownerEmail);

        if (mealPlans.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No meal plans found for this user.");
        }

        // Ensure meal plans are not null to avoid serialization errors
        mealPlans.forEach(mp -> {
            if (mp.getMealPlan() == null) {
                mp.setMealPlan(new ArrayList<>());
            }
        });

        return ResponseEntity.ok(mealPlans.get(0));
    }

    @GetMapping
    public List<MealPlanData> getAllMealPlans() {
        return service.getAllMealPlans();
    }

    @PostMapping
    public MealPlanData saveMealPlan(@RequestBody MealPlanData mealPlanData) {
        if (mealPlanData.getOwnerEmail() == null || mealPlanData.getMealPlan() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required fields");
        }
        return service.saveMealPlanData(mealPlanData);
    }

    @DeleteMapping("/{ownerEmail}") // ✅ Added proper DELETE endpoint
    public ResponseEntity<?> deleteMealPlanData(@PathVariable String ownerEmail) {
        Optional<MealPlanData> existingOpt = repository.findByOwnerEmail(ownerEmail).stream().findFirst();

        if (existingOpt.isPresent()) {
            repository.delete(existingOpt.get());
            return ResponseEntity.ok("Meal plan deleted successfully.");
        }

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Meal plan not found for this user.");
    }

    @PutMapping("/{ownerEmail}")
    public MealPlanData updateMealPlan(@PathVariable String ownerEmail, @RequestBody MealPlanData mealPlanData) {
        return service.updateMealPlanData(ownerEmail, mealPlanData);
    }
}
