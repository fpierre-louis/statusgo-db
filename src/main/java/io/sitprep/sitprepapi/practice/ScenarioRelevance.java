package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
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
 * <p>Also (2026-10-21): a household that has recorded pets — a named pet, or a
 * dog/cat/other count on its demographics — sees Evacuating with Pets near the
 * top with a note saying why. That is data the household entered, read only.</p>
 *
 * <p>Decoration only: an unknown household, an unresolved location or any
 * failure reading the profile yields no ranks, and the catalog is unchanged.
 * The hazard and pet reads fail independently.</p>
 */
@Service
public class ScenarioRelevance {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRelevance.class);

    /** The quiet line on a matching row. Honest for a state-level heuristic: it says "a risk", not "likely". */
    static final String LOCAL_NOTE = "A risk where you live";

    /** The quiet line on the pets scenario for a household with pets. Says what we know, nothing more. */
    static final String PETS_NOTE = "Your household has pets";
    static final String PETS_SCENARIO = "evacuating-with-pets";
    /** Below a very-high local hazard (0), level with a high one (1). */
    static final int PETS_RANK = 1;

    /** Where a matching scenario sorts (0 first) and the line that says why. */
    public record Match(int rank, String note) {}

    static final Map<String, String> SCENARIO_HAZARD = Map.of(
            "flash-flood-turn-around-route", "flood",
            "earthquake-first-five-minutes", "earthquake",
            "tornado-warning-shelter-decision", "tornado");

    private final GroupRepo groupRepo;
    private final RiskProfileService riskProfileService;
    private final HouseholdPetRepo petRepo;
    private final DemographicRepo demographicRepo;

    public ScenarioRelevance(GroupRepo groupRepo, RiskProfileService riskProfileService,
                             HouseholdPetRepo petRepo, DemographicRepo demographicRepo) {
        this.groupRepo = groupRepo;
        this.riskProfileService = riskProfileService;
        this.petRepo = petRepo;
        this.demographicRepo = demographicRepo;
    }

    /**
     * Scenario key → where it sorts and why, for this household: scenarios whose
     * hazard is in the area's profile (0 = highest tier; low tiers left out), and
     * the pets scenario when the household has recorded pets.
     */
    public Map<String, Match> matches(String householdId) {
        if (householdId == null || householdId.isBlank()) return Map.of();
        Group household;
        try {
            household = groupRepo.findByGroupId(householdId)
                    .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Scenario relevance: household {} unreadable; catalog order unchanged", householdId, e);
            return Map.of();
        }
        if (household == null) return Map.of();
        Map<String, Match> out = new HashMap<>();
        try {
            RiskProfileDto profile = riskProfileService.resolveFor(household);
            ranksFor(profile == null ? null : profile.risks())
                    .forEach((key, rank) -> out.put(key, new Match(rank, LOCAL_NOTE)));
        } catch (RuntimeException e) {
            log.warn("Scenario relevance: household {} risk profile unreadable; no local-risk order", householdId, e);
        }
        try {
            if (hasPets(householdId)) out.put(PETS_SCENARIO, new Match(PETS_RANK, PETS_NOTE));
        } catch (RuntimeException e) {
            log.warn("Scenario relevance: household {} pets unreadable; no pets order", householdId, e);
        }
        return out;
    }

    private boolean hasPets(String householdId) {
        if (!petRepo.findByHouseholdIdOrderByCreatedAtAsc(householdId).isEmpty()) return true;
        return demographicRepo.findFirstByHouseholdIdOrderByIdDesc(householdId)
                .map(d -> d.getDogs() + d.getCats() + d.getPets() > 0)
                .orElse(false);
    }

    /**
     * The household's local hazards, highest risk first (very high → high →
     * moderate; low and unknown tiers are left out): the Practice home's
     * "A risk where you live" (redesign FR-5). Empty when nothing is known.
     */
    public List<ScenarioDtos.LocalHazardDto> localHazards(String householdId) {
        if (householdId == null || householdId.isBlank()) return List.of();
        try {
            Group household = groupRepo.findByGroupId(householdId)
                    .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                    .orElse(null);
            if (household == null) return List.of();
            RiskProfileDto profile = riskProfileService.resolveFor(household);
            return localHazardsFrom(profile == null ? null : profile.risks());
        } catch (RuntimeException e) {
            log.warn("Practice local hazards: household {} risk profile unreadable", householdId, e);
            return List.of();
        }
    }

    static List<ScenarioDtos.LocalHazardDto> localHazardsFrom(List<RiskDto> risks) {
        if (risks == null || risks.isEmpty()) return List.of();
        Map<String, RiskDto> best = new java.util.LinkedHashMap<>();
        for (RiskDto r : risks) {
            if (r.hazard() == null || tierRank(r.tier()) < 0) continue;
            RiskDto seen = best.get(r.hazard());
            if (seen == null || tierRank(r.tier()) < tierRank(seen.tier())) best.put(r.hazard(), r);
        }
        return best.values().stream()
                .sorted(java.util.Comparator.comparingInt(r -> tierRank(r.tier())))
                .map(r -> new ScenarioDtos.LocalHazardDto(r.hazard(), r.label(), r.tier()))
                .toList();
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
