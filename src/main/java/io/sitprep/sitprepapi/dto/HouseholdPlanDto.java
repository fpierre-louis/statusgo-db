package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EvacuationPlan;
import io.sitprep.sitprepapi.domain.MealPlanData;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.OriginLocation;

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
        List<EmergencyContactGroup> contactGroups
) {}
