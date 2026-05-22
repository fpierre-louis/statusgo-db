package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EvacuationPlanRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.MealPlanDataRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.OriginLocationRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One-time, idempotent migration to the household-owned plan model
 * (docs/WIP_HOUSEHOLD_PLANS.md, Phase 1). Invoked on every boot by
 * {@code HouseholdBackfillRunner}, but cheap after the first successful
 * pass: each step queries only rows still missing the new column, so once
 * everything is backfilled the queries return empty and {@link #run()} is
 * a no-op.
 *
 * <ol>
 *   <li>Every user without a {@code baseHouseholdId} gets one — their
 *   first existing {@code groupType="Household"} group, or a freshly
 *   auto-created personal household (so "every plan has a household"
 *   always holds).</li>
 *   <li>Every plan row without a {@code householdId} inherits its author's
 *   base household.</li>
 * </ol>
 *
 * <p>DELETE this class + the runner once the migration is universally
 * applied and the create/save paths set {@code householdId} directly
 * (Phase 2).</p>
 */
@Service
public class HouseholdBackfillService {

    private static final Logger log = LoggerFactory.getLogger(HouseholdBackfillService.class);

    private final UserInfoRepo userInfoRepo;
    private final GroupRepo groupRepo;
    private final MeetingPlaceRepo meetingPlaceRepo;
    private final EvacuationPlanRepo evacuationPlanRepo;
    private final OriginLocationRepo originLocationRepo;
    private final MealPlanDataRepo mealPlanDataRepo;
    private final EmergencyContactGroupRepo emergencyContactGroupRepo;
    private final DemographicRepo demographicRepo;

    public HouseholdBackfillService(
            UserInfoRepo userInfoRepo,
            GroupRepo groupRepo,
            MeetingPlaceRepo meetingPlaceRepo,
            EvacuationPlanRepo evacuationPlanRepo,
            OriginLocationRepo originLocationRepo,
            MealPlanDataRepo mealPlanDataRepo,
            EmergencyContactGroupRepo emergencyContactGroupRepo,
            DemographicRepo demographicRepo
    ) {
        this.userInfoRepo = userInfoRepo;
        this.groupRepo = groupRepo;
        this.meetingPlaceRepo = meetingPlaceRepo;
        this.evacuationPlanRepo = evacuationPlanRepo;
        this.originLocationRepo = originLocationRepo;
        this.mealPlanDataRepo = mealPlanDataRepo;
        this.emergencyContactGroupRepo = emergencyContactGroupRepo;
        this.demographicRepo = demographicRepo;
    }

    @Transactional
    public void run() {
        int basesAssigned = assignBaseHouseholds();
        int plansBackfilled = backfillPlanHouseholds();
        if (basesAssigned > 0 || plansBackfilled > 0) {
            log.info("Household backfill: {} base household(s) assigned, {} plan row(s) backfilled.",
                    basesAssigned, plansBackfilled);
        }
    }

    // ── Step 1: base household per user ────────────────────────────
    private int assignBaseHouseholds() {
        List<UserInfo> needing = userInfoRepo.findByBaseHouseholdIdIsNull();
        int n = 0;
        for (UserInfo u : needing) {
            String email = u.getUserEmail();
            if (email == null || email.isBlank()) continue;
            Group base = firstHousehold(email);
            if (base == null) base = createPersonalHousehold(u, email);
            if (base == null) continue;
            u.setBaseHouseholdId(base.getGroupId());
            // Keep the denormalized managed-id cache coherent.
            if (u.getManagedGroupIDs() == null) u.setManagedGroupIDs(new HashSet<>());
            u.getManagedGroupIDs().add(base.getGroupId());
            userInfoRepo.save(u);
            n++;
        }
        return n;
    }

    private Group firstHousehold(String email) {
        return groupRepo.findByMemberEmail(email).stream()
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .findFirst()
                .orElse(null);
    }

    /** Mirrors the fields CreateHouseholdGroup.js sets for a new household. */
    private Group createPersonalHousehold(UserInfo u, String email) {
        Group g = new Group();
        g.setGroupId(UUID.randomUUID().toString());
        String first = u.getUserFirstName();
        String last = u.getUserLastName();
        g.setGroupName((first != null && !first.isBlank() ? first.trim() + "'s" : "My") + " Household");
        g.setGroupCode("HH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        g.setGroupType("Household");
        g.setPrivacy("Private");
        g.setAlert("Not Active");
        g.setOwnerEmail(email);
        g.setOwnerName(((first == null ? "" : first) + " " + (last == null ? "" : last)).trim());
        g.setAdminEmails(new ArrayList<>(List.of(email)));
        g.setMemberEmails(new ArrayList<>(List.of(email)));
        g.setMemberCount(1);
        Instant now = Instant.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        return groupRepo.save(g);
    }

    // ── Step 2: householdId per plan row ───────────────────────────
    private int backfillPlanHouseholds() {
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
