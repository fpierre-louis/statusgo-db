package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.PlanActivation;
import io.sitprep.sitprepapi.repo.PlanActivationRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Saved-plan edits reach the whole household and its live plans (plan data consistency, 2026-10-21). */
class HouseholdPlanBroadcastTest {

    @Test
    @DisplayName("an admin's edit reaches a live plan someone else started, and the household plan topic")
    void broadcastsByHousehold() {
        PlanActivationRepo repo = mock(PlanActivationRepo.class);
        WebSocketMessageSender ws = mock(WebSocketMessageSender.class);
        HouseholdResolver resolver = mock(HouseholdResolver.class);
        ActivationPlanUpdateBroadcastService svc = new ActivationPlanUpdateBroadcastService(repo, ws);
        svc.setHouseholdResolver(resolver);

        PlanActivation started = new PlanActivation();
        started.setId("act-by-dana");
        when(resolver.writableTargetHousehold("admin@x.com")).thenReturn(null);
        when(resolver.baseHouseholdIdFor("admin@x.com")).thenReturn("hh-1");
        when(repo.findActiveByOwnerEmail(eq("admin@x.com"), any())).thenReturn(List.of());
        when(repo.findLiveByHouseholdId(eq("hh-1"), any())).thenReturn(List.of(started));

        svc.broadcastOwnerPlanChangedAfterCommit("admin@x.com", "meetingPlaces");

        verify(ws).sendActivationPlanUpdate(eq("act-by-dana"), any());
        verify(ws).sendHouseholdPlan(eq("hh-1"), any(Map.class));
    }
}
