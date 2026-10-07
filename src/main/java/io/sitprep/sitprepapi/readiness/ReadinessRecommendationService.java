package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskDto;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialStep;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService.EssentialsResult;
import io.sitprep.sitprepapi.readiness.ReadinessCatalog.CatalogItem;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ActionDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.BandDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.NextStepDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ProvenanceDto;
import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.ReasonDto;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picks the ONE next step (CONTRACT.md §4). Deterministic: the same inputs
 * always produce the same pick, and the internal score never leaves this
 * class.
 *
 * <p>Gates first — an active response suppresses the next step entirely;
 * incomplete essentials make the next essential the step. Otherwise every
 * candidate (applicable, relevant, not snoozed, not done or due for review)
 * is scored and the highest wins; ties go to the lower catalog index, then
 * the key.</p>
 */
@Service
public class ReadinessRecommendationService {

    public static final String RECOMMENDATION_VERSION = "readiness-rec-2026.10.07.2";

    static final int LOCAL_RISK_HIGH = 30;
    static final int LOCAL_RISK_MODERATE = 15;
    static final int HOUSEHOLD_RELEVANCE = 25;
    static final int UNFINISHED_AREA = 12;
    static final int REVIEW_DUE = 10;

    static final String ESSENTIALS_FIRST_TEXT = "Essentials come first. This is the next one.";
    static final String REVIEW_DUE_TEXT = "It's been a while. A quick review keeps it current.";

    /** The pick plus the factor that explains it. */
    record Pick(DerivedItem item, String reasonCode, String reasonText) {}

    private record Scored(DerivedItem d, int score, String reasonCode, String reasonText) {}

    /**
     * @param risks the household's risk list (hazard, label, tier) — may be empty
     * @return the next step, or null (active response, or nothing left)
     */
    public NextStepDto recommend(JourneyMode mode, EssentialsResult essentials,
                                 List<DerivedItem> items, List<RiskDto> risks) {
        if (mode == JourneyMode.ACTIVE_RESPONSE) return null;
        if (mode == JourneyMode.ESSENTIALS_FIRST) return essentialStep(essentials);
        Pick pick = pick(items, risks);
        return pick == null ? null : toDto(pick);
    }

    /** The highest-scoring candidate, or null when nothing is left. */
    Pick pick(List<DerivedItem> items, List<RiskDto> risks) {
        if (items == null || items.isEmpty()) return null;
        Set<ReadinessArea> areasWithDone = items.stream()
                .filter(DerivedItem::countsDone)
                .map(d -> d.item().area())
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ReadinessArea.class)));
        List<RiskDto> riskList = risks == null ? List.of() : risks;

        return items.stream()
                .filter(DerivedItem::candidate)
                .map(d -> score(d, areasWithDone, riskList))
                .min(Comparator.comparingInt((Scored s) -> -s.score())
                        .thenComparingInt(s -> s.d().item().catalogIndex())
                        .thenComparing(s -> s.d().item().key()))
                .map(s -> new Pick(s.d(), s.reasonCode(), s.reasonText()))
                .orElse(null);
    }

    private Scored score(DerivedItem d, Set<ReadinessArea> areasWithDone, List<RiskDto> risks) {
        CatalogItem item = d.item();
        int score = item.priority();

        // Local risk: the best tier among the item's tagged hazards.
        int localRisk = 0;
        String riskLabel = null;
        for (RiskDto r : risks) {
            if (r == null || r.hazard() == null || !item.tags().contains(r.hazard())) continue;
            int pts = switch (r.tier() == null ? "" : r.tier()) {
                case "very_high", "high" -> LOCAL_RISK_HIGH;
                case "moderate" -> LOCAL_RISK_MODERATE;
                default -> 0;
            };
            if (pts > localRisk) {
                localRisk = pts;
                riskLabel = r.label() != null ? r.label() : r.hazard().replace('_', ' ');
            }
        }
        int relevance = item.applicability() != ReadinessCatalog.Applicability.ALWAYS ? HOUSEHOLD_RELEVANCE : 0;
        int unfinished = areasWithDone.contains(item.area()) ? 0 : UNFINISHED_AREA;
        int reviewDue = d.freshness() == Freshness.REVIEW_DUE ? REVIEW_DUE : 0;
        int ease = switch (item.time()) {
            case MIN_2, MIN_5 -> 8;
            case MIN_10 -> 4;
            default -> 0;
        } + (item.cost() == CostBand.FREE || item.cost() == CostBand.USE_WHAT_YOU_HAVE ? 5 : 0)
          + (item.effort() == EffortBand.ON_YOUR_OWN ? 2 : 0);
        score += localRisk + relevance + unfinished + reviewDue + ease;

        // Reason = largest contributing factor; ties resolve in the listed
        // order local risk → relevance → review due → unfinished area.
        // The two non-specific codes carry the step's own "why" (EXEC-A1
        // task 5): the code stays deterministic, only the sentence is the
        // item's. The generic line is a fallback for an item with none.
        String why = item.whyItMatters() != null && !item.whyItMatters().isBlank()
                ? item.whyItMatters() : ReadinessCatalog.GOOD_NEXT_STEP_TEXT;
        String code = "GOOD_NEXT_STEP";
        String text = why;
        int best = 0;
        if (localRisk > best) {
            best = localRisk;
            code = "LOCAL_RISK";
            text = "Suggested because your area has " + article(riskLabel) + " risk.";
        }
        if (relevance > best) {
            best = relevance;
            code = "HOUSEHOLD_RELEVANCE";
            text = item.relevanceReason();
        }
        if (reviewDue > best) {
            best = reviewDue;
            code = "REVIEW_DUE";
            text = REVIEW_DUE_TEXT;
        }
        if (unfinished > best) {
            code = "UNFINISHED_AREA";
            text = why;
        }
        return new Scored(d, score, code, text);
    }

    /** "a winter storm", "an earthquake". */
    static String article(String label) {
        String l = label == null ? "local" : label.toLowerCase(Locale.ROOT);
        boolean vowel = !l.isEmpty() && "aeiou".indexOf(l.charAt(0)) >= 0;
        return (vowel ? "an " : "a ") + l;
    }

    private static NextStepDto toDto(Pick p) {
        CatalogItem item = p.item().item();
        return new NextStepDto(
                item.key(), item.area(), item.title(), item.description(),
                new ReasonDto(p.reasonCode(), p.reasonText()),
                BandDto.of(item.time()), BandDto.of(item.cost()), BandDto.of(item.effort()),
                item.provenance().stream().map(ProvenanceDto::of).toList(),
                ActionDto.of(item.action(), item.actionParams()));
    }

    private static NextStepDto essentialStep(EssentialsResult essentials) {
        String key = essentials == null ? EssentialsReadinessService.ESSENTIAL_KEYS.get(0) : essentials.nextKey();
        EssentialStep step = key == null ? null : EssentialsReadinessService.step(key);
        if (step == null) return null;
        return new NextStepDto(
                "essentials." + step.key(), null, step.title(), step.description(),
                new ReasonDto("ESSENTIALS_FIRST", ESSENTIALS_FIRST_TEXT),
                BandDto.of(step.time()), BandDto.of(CostBand.FREE), BandDto.of(EffortBand.ON_YOUR_OWN),
                List.of(ProvenanceDto.of(new ReadinessCatalog.Provenance(ProvenanceKind.HOUSEHOLD_PLAN, null))),
                ActionDto.of(ReadinessAction.OPEN_ESSENTIALS, Map.of("essentialKey", step.key())));
    }
}
