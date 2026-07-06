package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One-time, idempotent migration to the household-owned plan model
 * (docs/WIP_HOUSEHOLD_PLANS.md, Phase 1). Invoked on every boot by
 * {@code HouseholdBackfillRunner}, but cheap after the first successful pass.
 *
 * <ol>
 *   <li>Every user without a {@code baseHouseholdId} gets one — delegated to
 *   {@link HouseholdProvisioningService#ensureBaseHousehold}.</li>
 *   <li>Every plan row without a {@code householdId} inherits its author's
 *   base household.</li>
 * </ol>
 *
 * <p><b>Per-user isolation (2026-07-02):</b> base assignment runs each user in
 * its OWN {@code REQUIRES_NEW} transaction. Previously the whole pass was a
 * single transaction, so ONE user whose household data throws on load (e.g. a
 * corrupt group) rolled the ENTIRE pass back every boot — leaving every other
 * user without a base too. Now a failing user is logged + skipped (retried next
 * boot) while everyone else still gets their base.</p>
 *
 * <p>DELETE this class + the runner once the migration is universally applied
 * and the create/save paths set {@code householdId} directly (Phase 2). Note
 * the create paths ALREADY call {@code ensureBaseHousehold} on provisioning.</p>
 */
@Service
public class HouseholdBackfillService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdBackfillService.class);

    private final UserInfoRepo userInfoRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final OriginLocationRepo originLocationRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final DemographicRepo demographicRepo;
    private final HouseholdProvisioningService householdProvisioning;
    private final PlatformTransactionManager txManager;

    public HouseholdBackfillService(
            UserInfoRepo userInfoRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            OriginLocationRepo originLocationRepo,
            MealPlanDataRepo mealPlanDataRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo,
            DemographicRepo demographicRepo,
            HouseholdProvisioningService householdProvisioning,
            PlatformTransactionManager txManager
    ) {
        this.userInfoRepo = userInfoRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.originLocationRepo = originLocationRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.demographicRepo = demographicRepo;
        this.householdProvisioning = householdProvisioning;
        this.txManager = txManager;
    }

    // NOT @Transactional: each sub-step manages its own transaction boundary so
    // one failure can't poison the others (see per-user isolation above).
    public void run() {
        int basesAssigned = assignBaseHouseholds();
        int plansBackfilled = 0;
        try {
            plansBackfilled = backfillPlanHouseholds();
        } catch (Exception e) {
            log.warn("Household backfill: plan-householdId backfill failed (retries next boot): {}", e.getMessage());
        }
        if (basesAssigned > 0 || plansBackfilled > 0) {
            log.info("Household backfill: {} base household(s) assigned, {} plan row(s) backfilled.",
                    basesAssigned, plansBackfilled);
        }
    }

    // ── Step 1: base household per user (per-user REQUIRES_NEW, resilient) ──
    private int assignBaseHouseholds() {
        List<String> userIds = userInfoRepo.findByBaseHouseholdIdIsNull().stream()
                .map(UserInfo::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (userIds.isEmpty()) return 0;

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        int n = 0;
        for (String id : userIds) {
            try {
                String assigned = tx.execute(status -> {
                    UserInfo u = userInfoRepo.findById(id).orElse(null);
                    return u == null ? null : householdProvisioning.ensureBaseHousehold(u);
                });
                if (assigned != null) n++;
            } catch (Exception e) {
                // One user's un-loadable household must NOT abort everyone
                // else's base assignment. Skip + log; retried next boot.
                log.warn("Household backfill: skipped user {} — base assignment failed: {}", id, e.getMessage());
            }
        }
        return n;
    }

    // ── Step 2: householdId per plan row (own REQUIRES_NEW tx) ─────────────
    private int backfillPlanHouseholds() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer result = tx.execute(status -> doBackfillPlanHouseholds());
        return result == null ? 0 : result;
    }

    private int doBackfillPlanHouseholds() {
        List<MeetingPlace> mp = meetingPlaceRepo.findByHouseholdIdIsNull();
        List<EvacuationPlan> ev = evacuationPlanRepo.findByHouseholdIdIsNull();
        List<OriginLocation> ol = originLocationRepo.findByHouseholdIdIsNull();
        List<MealPlanData> meal = mealPlanDataRepo.findByHouseholdIdIsNull();
        List<EmergencyContactGroup> ec = emergencyContactGroupRepo.findByHouseholdIdIsNull();
        List<Demographic> dm = demographicRepo.findByHouseholdIdIsNull();

        if (mp.isEmpty() && ev.isEmpty() && ol.isEmpty()
                && meal.isEmpty() && ec.isEmpty() && dm.isEmpty()) {
            return 0;
        }

        Map<String, String> baseByEmail = baseHouseholdByEmail();
        int n = 0;
        n += apply(mp, MeetingPlace::getOwnerEmail, MeetingPlace::setHouseholdId, baseByEmail, meetingPlaceRepo);
        n += apply(ev, EvacuationPlan::getOwnerEmail, EvacuationPlan::setHouseholdId, baseByEmail, evacuationPlanRepo);
        n += apply(ol, OriginLocation::getOwnerEmail, OriginLocation::setHouseholdId, baseByEmail, originLocationRepo);
        n += apply(meal, MealPlanData::getOwnerEmail, MealPlanData::setHouseholdId, baseByEmail, mealPlanDataRepo);
        n += apply(ec, EmergencyContactGroup::getOwnerEmail, EmergencyContactGroup::setHouseholdId, baseByEmail, emergencyContactGroupRepo);
        n += apply(dm, Demographic::getOwnerEmail, Demographic::setHouseholdId, baseByEmail, demographicRepo);
        return n;
    }

    /** lower(email) -> baseHouseholdId for every user that has a base. */
    private Map<String, String> baseHouseholdByEmail() {
        Map<String, String> m = new HashMap<>();
        for (UserInfo u : userInfoRepo.findAll()) {
            if (u.getUserEmail() != null && u.getBaseHouseholdId() != null) {
                m.put(u.getUserEmail().toLowerCase(Locale.ROOT), u.getBaseHouseholdId());
            }
        }
        return m;
    }

    private <T> int apply(List<T> rows,
                          Function<T, String> ownerGetter,
                          BiConsumer<T, String> householdSetter,
                          Map<String, String> baseByEmail,
                          JpaRepository<T, ?> repo) {
        List<T> toSave = new ArrayList<>();
        for (T row : rows) {
            String owner = ownerGetter.apply(row);
            if (owner == null || owner.isBlank()) continue;
            String base = baseByEmail.get(owner.toLowerCase(Locale.ROOT));
            if (base == null) continue; // owner has no base (e.g. orphan row) — leave for next pass
            householdSetter.accept(row, base);
            toSave.add(row);
        }
        if (!toSave.isEmpty()) repo.saveAll(toSave);
        return toSave.size();
    }
}
