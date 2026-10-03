package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.service.GoBagService;
import io.sitprep.sitprepapi.service.HomeStockpileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Which household tokens a household's records satisfy right now.
 *
 * <p>Every criterion reads the record that owns the fact, not the readiness
 * percent: that percent counts the VIEWER's personal pillar tasks
 * ({@code MeService.computePillarRollup}), so two members of one household get
 * different numbers. A household credential can't depend on whose phone asked.</p>
 *
 * <p>Each criterion is caught on its own, so one failing read drops only its
 * own token. {@code HOUSEHOLD_READY} is not decided here — it follows from the
 * ledger ({@link TokenEvaluationService}).</p>
 */
@Component
public class HouseholdTokenCriteria {

    private static final Logger log = LoggerFactory.getLogger(HouseholdTokenCriteria.class);

    static final int CONTACT_CIRCLE_MIN_CONTACTS = 2;
    static final int PRACTICE_CADENCE_MIN_DRILLS = 3;
    static final int STEWARD_GO_BAG_MIN_PCT = 80;
    static final int STEWARD_STOCKPILE_MIN_PCT = 50;

    private final MeetingPlaceRepo meetingPlaces;
    private final EmergencyContactGroupRepo contactGroups;
    private final EvacuationPlanRepo evacuationPlans;
    private final GroupRepo groups;
    private final GoBagService goBags;
    private final HomeStockpileService stockpile;

    public HouseholdTokenCriteria(MeetingPlaceRepo meetingPlaces,
                                  EmergencyContactGroupRepo contactGroups,
                                  EvacuationPlanRepo evacuationPlans,
                                  GroupRepo groups,
                                  GoBagService goBags,
                                  HomeStockpileService stockpile) {
        this.meetingPlaces = meetingPlaces;
        this.contactGroups = contactGroups;
        this.evacuationPlans = evacuationPlans;
        this.groups = groups;
        this.goBags = goBags;
        this.stockpile = stockpile;
    }

    /** Household tokens whose criteria hold now, excluding the composite {@code HOUSEHOLD_READY}. */
    @Transactional(readOnly = true)
    public Set<TokenKey> met(String householdId) {
        Set<TokenKey> met = EnumSet.noneOf(TokenKey.class);
        if (householdId == null || householdId.isBlank()) return met;

        boolean meetingPoint = check(householdId, TokenKey.MEETING_POINT,
                () -> !meetingPlaces.findByHouseholdId(householdId).isEmpty());
        boolean contactCircle = check(householdId, TokenKey.CONTACT_CIRCLE,
                () -> contactCount(householdId) >= CONTACT_CIRCLE_MIN_CONTACTS);
        boolean primaryRoute = check(householdId, TokenKey.PLAN_ARCHITECT,
                () -> evacuationPlans.findByHouseholdId(householdId).stream()
                        .anyMatch(p -> p.getPrimaryRouteNotes() != null && !p.getPrimaryRouteNotes().isBlank()));
        int drills = distinctDrills(householdId);

        if (meetingPoint) met.add(TokenKey.MEETING_POINT);
        if (contactCircle) met.add(TokenKey.CONTACT_CIRCLE);
        if (primaryRoute && meetingPoint && contactCircle) met.add(TokenKey.PLAN_ARCHITECT);
        if (drills >= 1) met.add(TokenKey.DRILL_CREW);
        if (drills >= PRACTICE_CADENCE_MIN_DRILLS) met.add(TokenKey.PRACTICE_CADENCE);
        if (check(householdId, TokenKey.STOCKPILE_STEWARD, () -> stockpileSteward(householdId))) {
            met.add(TokenKey.STOCKPILE_STEWARD);
        }
        return met;
    }

    private int contactCount(String householdId) {
        int n = 0;
        for (EmergencyContactGroup g : contactGroups.findByHouseholdId(householdId)) {
            if (g.getContacts() != null) n += g.getContacts().size();
        }
        return n;
    }

    /** Distinct drills logged; {@code go-bag#papers} is a step of {@code go-bag}, not another drill. */
    int distinctDrills(String householdId) {
        try {
            Group g = groups.findById(householdId).orElse(null);
            Map<String, ?> log = g == null ? null : g.getDrillLog();
            if (log == null || log.isEmpty()) return 0;
            Set<String> base = new HashSet<>();
            for (String key : log.keySet()) {
                if (key == null || key.isBlank()) continue;
                int hash = key.indexOf('#');
                base.add(hash >= 0 ? key.substring(0, hash) : key);
            }
            return base.size();
        } catch (Exception e) {
            HouseholdTokenCriteria.log.warn("drill criteria unreadable for {}: {}", householdId, e.toString());
            return 0;
        }
    }

    private boolean stockpileSteward(String householdId) {
        boolean bagReady = goBags.summariesForHousehold(householdId).stream()
                .mapToInt(GoBagSummaryDto::completionPct)
                .anyMatch(pct -> pct >= STEWARD_GO_BAG_MIN_PCT);
        return bagReady && stockpile.getForHousehold(householdId).percentComplete() >= STEWARD_STOCKPILE_MIN_PCT;
    }

    private static boolean check(String householdId, TokenKey key, BooleanSupplier criterion) {
        try {
            return criterion.getAsBoolean();
        } catch (Exception e) {
            log.warn("token criteria {} unreadable for {}: {}", key, householdId, e.toString());
            return false;
        }
    }
}
