package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which scenarios rehearse a hazard the household's area is known for
 * (EXEC-I3), from the same regional risk profile Ready for More reads.
 *
 * <p>The scenario → hazard map is presentation, held here rather than in the
 * reviewed content: it only orders the catalog and adds a quiet note, and never
 * changes what a scenario says, so it carries no content hash. A scenario with
 * no hazard (Comms) keeps its catalog place.</p>
 *
 * <p>Decoration only: an unknown household, an unresolved location or any
 * failure reading the profile yields no ranks, and the catalog is unchanged.</p>
 */
@Service
public class ScenarioRelevance {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRelevance.class);

    /** The quiet line on a matching row. Honest for a state-level heuristic: it says "a risk", not "likely". */
    static final String LOCAL_NOTE = "A risk where you live";

    static final Map<String, String> SCENARIO_HAZARD = Map.of(
            "flash-flood-turn-around-route", "flood",
            "earthquake-first-five-minutes", "earthquake");

    private final GroupRepo groupRepo;
    private final RiskProfileService riskProfileService;

    public ScenarioRelevance(GroupRepo groupRepo, RiskProfileService riskProfileService) {
        this.groupRepo = groupRepo;
        this.riskProfileService = riskProfileService;
    }

    /**
     * Scenario key → rank (0 = the area's highest tier) for the scenarios whose
     * hazard is in the household's profile. Low-tier hazards are left out.
     */
    public Map<String, Integer> ranks(String householdId) {
        if (householdId == null || householdId.isBlank()) return Map.of();
        try {
            Group household = groupRepo.findByGroupId(householdId)
                    .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                    .orElse(null);
            if (household == null) return Map.of();
            RiskProfileDto profile = riskProfileService.resolveFor(household);
            return ranksFor(profile == null ? null : profile.risks());
        } catch (RuntimeException e) {
            log.warn("Scenario relevance: household {} risk profile unreadable; catalog order unchanged", householdId, e);
            return Map.of();
        }
    }

    static Map<String, Integer> ranksFor(List<RiskDto> risks) {
        if (risks == null || risks.isEmpty()) return Map.of();
        Map<String, Integer> byHazard = new HashMap<>();
        for (RiskDto r : risks) {
            int rank = tierRank(r.tier());
            if (r.hazard() == null || rank < 0) continue;
            byHazard.merge(r.hazard(), rank, Math::min);
        }
        Map<String, Integer> out = new HashMap<>();
        SCENARIO_HAZARD.forEach((key, hazard) -> {
            Integer rank = byHazard.get(hazard);
            if (rank != null) out.put(key, rank);
        });
        return out;
    }

    private static int tierRank(String tier) {
        if (tier == null) return -1;
        return switch (tier) {
            case "very_high" -> 0;
            case "high" -> 1;
            case "moderate" -> 2;
            default -> -1;
        };
    }
}
