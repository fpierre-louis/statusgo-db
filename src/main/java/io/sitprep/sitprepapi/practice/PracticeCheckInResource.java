package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.AnswerDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.AnswerRequest;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.ConfidenceRequest;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.DueDto;
import io.sitprep.sitprepapi.practice.PracticeCheckInDtos.SkillStatesDto;
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
 * Check-ins and server skill state (Practice redesign FR-7). Token-gated by the
 * {@code /api/**} chain; household access is checked in the services.
 *
 * <ul>
 *   <li>{@code GET  /api/practice/checkins/due?householdId=} — up to 3 due cards, one hazard</li>
 *   <li>{@code POST /api/practice/checkins/{key}/answer} {@code {householdId, choiceKey}}</li>
 *   <li>{@code POST /api/practice/checkins/answers/{answerId}/confidence} {@code {confidence}} — 204</li>
 *   <li>{@code GET  /api/practice/skills/state?householdId=} — NEW | PRACTICED | REFRESH per skill</li>
 * </ul>
 */
@RestController
public class PracticeCheckInResource {

    private final PracticeCheckInService service;

    public PracticeCheckInResource(PracticeCheckInService service) {
        this.service = service;
    }

    @GetMapping("/api/practice/checkins/due")
    public ResponseEntity<DueDto> due(@RequestParam(value = "householdId", required = false) String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.due(caller, householdId));
    }

    @PostMapping("/api/practice/checkins/{checkinKey}/answer")
    @Idempotent
    public ResponseEntity<AnswerDto> answer(@PathVariable String checkinKey,
                                            @RequestBody(required = false) AnswerRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        if (checkinKey == null || checkinKey.length() > PracticeContentValidator.KEY_MAX
                || !PracticeContentValidator.KEY_FORMAT.matcher(checkinKey).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Check-in not found");
        }
        return ResponseEntity.ok(service.answer(caller, checkinKey,
                body == null ? null : body.householdId(), body == null ? null : body.choiceKey()));
    }

    @PostMapping("/api/practice/checkins/answers/{answerId}/confidence")
    @Idempotent
    public ResponseEntity<Void> confidence(@PathVariable long answerId,
                                           @RequestBody(required = false) ConfidenceRequest body) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        service.confidence(caller, answerId, body == null ? null : body.confidence());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/practice/skills/state")
    public ResponseEntity<SkillStatesDto> skillState(@RequestParam(value = "householdId", required = false) String householdId) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        return ResponseEntity.ok(service.skillStates(caller, householdId));
    }
}
