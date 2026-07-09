package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentEvaluateRequest;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentQuestionDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentSummaryDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.PlanSignalsDto;
import io.sitprep.sitprepapi.service.AssessmentScoringService;
import io.sitprep.sitprepapi.service.UserInfoService;
import io.sitprep.sitprepapi.util.AuthUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Readiness Assessment quiz, server-side (weighted scoring migrated from
 * the deleted FE {@code assessmentModel.js}).
 *
 * <p><b>Auth-optional by design:</b> the quiz is a guest-capable lead surface.
 * {@code /questions} is an open read (static catalog). {@code /evaluate} is a
 * pure compute for anonymous callers (no data read, no persist); for
 * authenticated callers it enriches with saved-plan signals and persists the
 * summary + completion stamp (the same store {@code POST /api/userinfo/me/assessment}
 * writes, so the guest→account migration path keeps working).</p>
 */
@RestController
@RequestMapping("/api/readiness/assessment")
@CrossOrigin(origins = "http://localhost:3000")
public class ReadinessAssessmentResource {

    private static final Logger log = LoggerFactory.getLogger(ReadinessAssessmentResource.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AssessmentScoringService scoring;
    private final UserInfoService userInfoService;
    private final ObjectMapper objectMapper;

    public ReadinessAssessmentResource(AssessmentScoringService scoring,
                                       UserInfoService userInfoService,
                                       ObjectMapper objectMapper) {
        this.scoring = scoring;
        this.userInfoService = userInfoService;
        this.objectMapper = objectMapper;
    }

    /** The question catalog — single source of truth for the quiz UI. */
    @GetMapping("/questions")
    public ResponseEntity<List<AssessmentQuestionDto>> questions() {
        return ResponseEntity.ok(AssessmentScoringService.QUESTIONS);
    }

    @PostMapping("/evaluate")
    public ResponseEntity<AssessmentSummaryDto> evaluate(@RequestBody AssessmentEvaluateRequest req) {
        String email = AuthUtils.getCurrentUserEmail();
        boolean authed = email != null && !email.isBlank();

        PlanSignalsDto signals = authed
                ? scoring.planSignalsFor(email)
                : AssessmentScoringService.guestSignals();
        Set<String> activeHazards = req == null || req.activeHazards() == null
                ? Set.of() : new HashSet<>(req.activeHazards());

        AssessmentSummaryDto summary = scoring.buildSummary(
                req == null ? Map.of() : req.responses(), signals, authed, activeHazards);

        if (authed) {
            // Persist alongside the completion stamp; a persist failure must
            // not sink the result the user is looking at.
            try {
                userInfoService.markAssessmentCompleteByEmail(
                        email, objectMapper.convertValue(summary, MAP_TYPE));
            } catch (Exception e) {
                log.warn("assessment persist failed email={} cause={}", email, e.getMessage());
            }
        }
        return ResponseEntity.ok(summary);
    }
}
