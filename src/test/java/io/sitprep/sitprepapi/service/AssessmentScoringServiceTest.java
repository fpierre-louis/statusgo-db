package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentItemDto;
import io.sitprep.sitprepapi.dto.ReadinessDtos.AssessmentSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity guard for the weighted scoring port. The expected values are
 * HAND-COMPUTED from the retired FE {@code assessmentModel.buildAssessmentSummary}
 * math, so the server engine cannot drift from what users saw:
 *
 * <pre>
 * responses: selfGrade=50, foodSupply=yes(1.2), waterSupply=kinda(1.2),
 *            communicationPlan=kinda(1.1), emergencyContacts=yes(1.2),
 *            evacuationPlan=no(1.2), naturalDisasters=[flood], floodPrep=no(0.9)
 * totalWeight = 6.8 ; earned = 1.2 + .6 + .55 + 1.2 + 0 + 0 = 3.55
 * base = 3.55/6.8×100 = 52.2058…; score = round(base×0.9 + 50×0.1) = 52
 * band: 52 &lt; 60 → start_with_essentials
 * gaps (severity→priority): waterSupply(11) → communicationPlan(18) →
 *                           evacuationPlan(25) → floodPrep(high,32)
 * </pre>
 */
@SpringBootTest
@ActiveProfiles("test")
class AssessmentScoringServiceTest {

    @Autowired AssessmentScoringService scoring;

    private static final Map<String, Object> RESPONSES = Map.of(
            "selfGrade", 50,
            "foodSupply", "yes",
            "waterSupply", "kinda",
            "communicationPlan", "kinda",
            "emergencyContacts", "yes",
            "evacuationPlan", "no",
            "naturalDisasters", List.of("flood"),
            "floodPrep", "no");

    @Test
    void guestVector_matchesHandComputedFrontendMath() {
        AssessmentSummaryDto s = scoring.buildSummary(
                RESPONSES, AssessmentScoringService.guestSignals(), false, Set.of());

        assertThat(s.score()).isEqualTo(52);
        assertThat(s.band()).isEqualTo("start_with_essentials");
        assertThat(s.bandTitle()).isEqualTo("Start with essentials");
        assertThat(s.mode()).isEqualTo("guest");
        assertThat(s.selfGrade()).isEqualTo(50);
        assertThat(s.version()).isEqualTo(2);

        assertThat(s.gaps()).extracting(AssessmentItemDto::key)
                .containsExactly("waterSupply", "communicationPlan", "evacuationPlan", "floodPrep");
        assertThat(s.strengths()).extracting(AssessmentItemDto::key)
                .containsExactlyInAnyOrder("foodSupply", "emergencyContacts");

        // PillarContextBanner reads pillarKey straight off the gap now.
        assertThat(s.gaps().get(0).pillarKey()).isEqualTo("mealPlan");
        assertThat(s.gaps().get(0).severity()).isEqualTo("critical");
        assertThat(s.gaps().get(0).reason()).contains("partly ready");

        // Critical gap present → next action is the top gap, auth-gated route.
        assertThat(s.nextAction().type()).isEqualTo("app_step");
        assertThat(s.nextAction().key()).isEqualTo("waterSupply");
        assertThat(s.nextAction().route()).isEqualTo("/create-foodsupply");
        assertThat(s.nextAction().requiresAuth()).isTrue();

        // Flood selected + weak prep → its guide action, guest reason.
        assertThat(s.guideActions()).hasSize(1);
        assertThat(s.guideActions().get(0).hazardKey()).isEqualTo("flood");
        assertThat(s.guideActions().get(0).route()).isEqualTo("/flood-prep");
        assertThat(s.guideActions().get(0).reason()).contains("You selected flood");
    }

    @Test
    void activeHazard_bumpsSeverityAndReason() {
        AssessmentSummaryDto s = scoring.buildSummary(
                RESPONSES, AssessmentScoringService.guestSignals(), false, Set.of("flood"));

        AssessmentItemDto floodGap = s.gaps().stream()
                .filter(g -> "floodPrep".equals(g.key())).findFirst().orElseThrow();
        assertThat(floodGap.severity()).isEqualTo("critical"); // bumped from high
        assertThat(floodGap.priority()).isEqualTo(20);         // 32 − 12
        assertThat(s.guideActions().get(0).reason()).contains("may be active nearby");
    }

    @Test
    void unansweredAndNa_areExcludedFromWeighting() {
        // Only selfGrade answered → no weights → score = confidence verbatim.
        AssessmentSummaryDto s = scoring.buildSummary(
                Map.of("selfGrade", 75, "petPreparedness", "na"),
                AssessmentScoringService.guestSignals(), false, Set.of());
        assertThat(s.score()).isEqualTo(75);
        assertThat(s.gaps()).isEmpty();
        assertThat(s.strengths()).isEmpty();
        // No gaps and no guides → review-and-share next action (guest CTA).
        assertThat(s.nextAction().type()).isEqualTo("review");
        assertThat(s.nextAction().route()).isEqualTo("/create-account");
    }

    @Test
    void questionCatalog_declarativeConditionsPreserved() {
        assertThat(AssessmentScoringService.QUESTIONS).hasSize(18);
        var flood = AssessmentScoringService.QUESTIONS.stream()
                .filter(q -> "floodPrep".equals(q.field())).findFirst().orElseThrow();
        assertThat(flood.conditionHazard()).isEqualTo("flood");
        assertThat(flood.deep()).isTrue();
        var hazards = AssessmentScoringService.QUESTIONS.stream()
                .filter(q -> "naturalDisasters".equals(q.field())).findFirst().orElseThrow();
        assertThat(hazards.type()).isEqualTo("multi-select");
        assertThat(hazards.conditionHazard()).isNull();
    }
}
