package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.ScenarioDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.CommitRequest;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ProgressDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.RunDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.ScenarioDetailDto;
import io.sitprep.sitprepapi.practice.ScenarioDtos.StartRequest;
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

/**
 * Practice scenarios (EXEC-B). Token-gated by the {@code /api/**} chain;
 * household membership and run ownership are checked in {@link ScenarioService}.
 *
 * <ul>
 *   <li>{@code GET  /api/practice/scenarios?householdId=} — startable catalog + "continue where you left off"</li>
 *   <li>{@code GET  /api/practice/scenarios/{key}?householdId=} — intro</li>
 *   <li>{@code POST /api/practice/scenarios/{key}/runs} {@code {householdId?}} — start or resume</li>
 *   <li>{@code GET  /api/practice/scenarios/runs/{runId}} — run state (refresh / resume)</li>
 *   <li>{@code POST /api/practice/scenarios/runs/{runId}/choices} {@code {nodeKey, choiceKey}} — commit</li>
 *   <li>{@code POST /api/practice/scenarios/runs/{runId}/complete} — record the debrief</li>
 *   <li>{@code GET  /api/households/{id}/practice/scenarios/progress} — derived from runs</li>
 * </ul>
 */
@RestController
public class PracticeScenarioResource {

    private final ScenarioService service;

    public PracticeScenarioResource(ScenarioService service) {
        this.service = service;
    }

    @GetMapping("/api/practice/scenarios")
    public ResponseEntity<CatalogDto> catalog(@RequestParam(value = "householdId", required = false) String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.catalog(caller, householdId));
    }

    @GetMapping("/api/practice/scenarios/{scenarioKey}")
    public ResponseEntity<ScenarioDetailDto> detail(@PathVariable String scenarioKey,
                                                    @RequestParam(value = "householdId", required = false) String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.detail(validKey(scenarioKey), caller, householdId));
    }

    @PostMapping("/api/practice/scenarios/{scenarioKey}/runs")
    @Idempotent
    public ResponseEntity<RunDto> start(@PathVariable String scenarioKey,
                                        @RequestBody(required = false) StartRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.start(validKey(scenarioKey), caller, body == null ? null : body.householdId()));
    }

    @GetMapping("/api/practice/scenarios/runs/{runId}")
    public ResponseEntity<RunDto> run(@PathVariable long runId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.get(runId, caller));
    }

    @PostMapping("/api/practice/scenarios/runs/{runId}/choices")
    @Idempotent
    public ResponseEntity<RunDto> commit(@PathVariable long runId, @RequestBody(required = false) CommitRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (body == null || isBlank(body.nodeKey()) || isBlank(body.choiceKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nodeKey and choiceKey are required");
        }
        return ResponseEntity.ok(service.commit(runId, caller, body.nodeKey(), body.choiceKey()));
    }

    @PostMapping("/api/practice/scenarios/runs/{runId}/complete")
    @Idempotent
    public ResponseEntity<RunDto> complete(@PathVariable long runId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.complete(runId, caller));
    }

    @GetMapping("/api/households/{householdId}/practice/scenarios/progress")
    public ResponseEntity<ProgressDto> progress(@PathVariable String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.progress(householdId, caller));
    }

    private static String validKey(String key) {
        if (key == null || key.length() > PracticeContentValidator.KEY_MAX
                || !PracticeContentValidator.KEY_FORMAT.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario not found");
        }
        return key;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
