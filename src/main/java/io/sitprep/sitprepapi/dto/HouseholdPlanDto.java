package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;
import io.sitprep.sitprepapi.dto.GoBagDtos.GoBagSummaryDto;

import java.time.Instant;
import java.util.List;

/**
 * Full household plan document for the combined HouseholdPlanView
 * (docs/WIP_HOUSEHOLD_PLANS.md, Phase 3). Unlike {@link MePlansDto} — which
 * carries lossy summaries (counts) for the dashboard — this returns the full
 * entities the shared, printable plan view needs: contacts with phone +
 * medical, the meal menu, meeting-place addresses + notes, shelter details,
 * and the household's identity + demographics.
 *
 * <p>Served by {@code GET /api/households/{id}/plans}, gated to household
 * members. Any member can read; admins edit through the per-pillar editors.</p>
 */
public record HouseholdPlanDto(
        String householdId,
        String name,
        String address,
        String latitude,
        String longitude,
        String zipCode,
        Demographic demographic,
        List<MeetingPlace> meetingPlaces,
        List<EvacuationPlan> evacuationPlans,
        List<OriginLocation> originLocations,
        MealPlanData mealPlan,
        List<EmergencyContactGroup> contactGroups,
        /**
         * Lossy go-bag summaries (name / kind / storage / packed / expiring)
         * so the plan-tab surface renders the household's bags without a
         * second fetch. Full item lists come from {@code /api/households/{id}/go-bags}.
         */
        List<GoBagSummaryDto> goBags,
        /**
         * §3 of docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md — timestamp the
         * household plan was most recently confirmed as current (distinct
         * from any individual plan-component edit time). Null on legacy
         * households + on households whose admin has never tapped
         * "Mark confirmed"; FE treats null as "not yet confirmed" and
         * shows the standard sub-copy. Mirrored from {@code Group.planLastConfirmedAt}.
         */
        Instant planLastConfirmedAt,
        /**
         * RC-3 · Emergency Need Profiles for this household's people.
         *
         * <p>They ride the PLAN DOCUMENT rather than a member DTO for one
         * reason: this is the payload the frontend mirrors into {@code meCache}
         * and renders in the printable plan. A support profile that lives only
         * on a live member view would vanish exactly when it matters — offline,
         * on a dead phone, on a printed page in a blackout — which is the one
         * job preparedness data has.</p>
         */
        List<EmergencySupportDtos.SupportProfileDto> supportProfiles,
        /** Prepared helpers. Plan state — never current acknowledgement. */
        List<EmergencySupportDtos.SupportAssignmentDto> supportAssignments,
        /**
         * ACTIVE household standing conditions — what the household says is
         * affecting it right now.
         *
         * <p>Rides the plan document deliberately: this is the payload
         * `meCache` mirrors, so conditions are readable on a cold offline
         * launch and printable, without a second cache. Private household data
         * — never projected onto any public/bearer-link DTO.
         */
        List<StandingConditionDtos.StandingConditionDto> standingConditions
) {}
