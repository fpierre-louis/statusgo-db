package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The skill registry (Practice redesign BE-04), read-only. Token-gated by the
 * {@code /api/**} chain like the scenario catalog.
 *
 * <ul><li>{@code GET /api/practice/skills?hazard=} — startable skills (PUBLISHED; drafts only under preview)</li></ul>
 */
@RestController
public class PracticeSkillResource {

    private final PracticeSkillService service;

    public PracticeSkillResource(PracticeSkillService service) {
        this.service = service;
    }

    @GetMapping("/api/practice/skills")
    public ResponseEntity<PracticeSkillService.SkillsDto> list(@RequestParam(value = "hazard", required = false) String hazard) {
        AuthUtils.requireAuthenticatedEmail();
        if (hazard != null && !PracticeContentValidator.KEY_FORMAT.matcher(hazard).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hazard must be a lower-kebab key");
        }
        return ResponseEntity.ok(service.list(hazard));
    }
}
