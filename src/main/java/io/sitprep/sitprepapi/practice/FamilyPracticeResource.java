package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ActivityDetailDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CompleteRequest;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CompletionDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ProgressDto;
import io.sitprep.sitprepapi.util.AuthUtils;
import io.sitprep.sitprepapi.web.Idempotent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Household-only Family Practice API. */
@RestController
public class FamilyPracticeResource {

    private final FamilyPracticeService service;

    public FamilyPracticeResource(FamilyPracticeService service) {
        this.service = service;
    }

    @GetMapping("/api/practice/family")
    public ResponseEntity<CatalogDto> catalog(@RequestParam String householdId) {
        return ResponseEntity.ok(service.catalog(AuthUtils.requireAuthenticatedEmail(), householdId));
    }

    @GetMapping("/api/practice/family/{activityKey}")
    public ResponseEntity<ActivityDetailDto> detail(@PathVariable String activityKey,
                                                    @RequestParam String householdId) {
        return ResponseEntity.ok(service.detail(validKey(activityKey), AuthUtils.requireAuthenticatedEmail(), householdId));
    }

    @PostMapping("/api/households/{householdId}/practice/family/{activityKey}/complete")
    @Idempotent
    public ResponseEntity<CompletionDto> complete(@PathVariable String householdId,
                                                  @PathVariable String activityKey,
                                                  @RequestBody(required = false) CompleteRequest body) {
        if (body == null || body.contentVersion() == null || body.contentVersion() < 1
                || body.contentHash() == null || body.contentHash().isBlank() || body.contentHash().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentVersion and contentHash are required");
        }
        return ResponseEntity.ok(service.complete(validKey(activityKey), AuthUtils.requireAuthenticatedEmail(),
                householdId, body.contentVersion(), body.contentHash()));
    }

    @GetMapping("/api/households/{householdId}/practice/family/progress")
    public ResponseEntity<ProgressDto> progress(@PathVariable String householdId) {
        return ResponseEntity.ok(service.progress(householdId, AuthUtils.requireAuthenticatedEmail()));
    }

    private static String validKey(String key) {
        if (key == null || key.length() > PracticeContentValidator.KEY_MAX
                || !PracticeContentValidator.KEY_FORMAT.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Family activity not found");
        }
        return key;
    }
}
