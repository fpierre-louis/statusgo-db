package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.exception.QuotaExceededException;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Metered monetization (Phase 2): a group's monthly work-order allowance is
 * enforced by {@link WorkOrderQuotaService}, which throws
 * {@link QuotaExceededException} (→ HTTP 402) once usage reaches the tier cap.
 * Pure Mockito — no Spring context, no DB.
 *
 * <p>D26 (2026-10-13; audit TK-07, PR-01): life-safety work, a group in
 * alert and an area in alert are exempt; the cap reads the effective tier;
 * and it stays a soft warning until billing is live.</p>
 */
class WorkOrderQuotaServiceTest {

    private static final String GROUP_ID = "grp-1";

    private PostRepo taskRepo;
    private BillingService billing;
    private WorkOrderQuotaService quota;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        billing = mock(BillingService.class);
        // The hard-cap cases below describe the world once billing is live.
        when(billing.isConfigured()).thenReturn(true);
        quota = new WorkOrderQuotaService(taskRepo, billing);
    }

    private Group group(String tier) {
        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setPlanTier(tier);
        return g;
    }

    private void stubUsage(long used) {
        when(taskRepo.countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                eq(GROUP_ID), eq("task"), any(Instant.class))).thenReturn(used);
    }

    @Test
    void freeTier_atCap_throws402() {
        stubUsage(15); // FREE cap is 15 → 15 used means the 16th is blocked
        QuotaExceededException ex = assertThrows(QuotaExceededException.class,
                () -> quota.assertQuota(group("FREE"), false, null));
        assertEquals(15, ex.getCap());
        assertEquals(15L, ex.getUsed());
    }

    @Test
    void freeTier_underCap_passes() {
        stubUsage(14);
        assertDoesNotThrow(() -> quota.assertQuota(group("FREE"), false, null));
    }

    @Test
    void agencyTier_isUnlimited_neverThrows_andSkipsTheCountQuery() {
        assertDoesNotThrow(() -> quota.assertQuota(group("AGENCY"), false, null));
        // Unlimited tiers short-circuit before ever counting usage.
        verify(taskRepo, never()).countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                anyString(), anyString(), any());
    }

    @Test
    void nullGroup_isNeverMetered() {
        assertDoesNotThrow(() -> quota.assertQuota(null, false, null));
        verifyNoInteractions(taskRepo);
    }

    @Test
    void legacyNullTier_readsAsFree_andEnforces() {
        stubUsage(15);
        assertThrows(QuotaExceededException.class,
                () -> quota.assertQuota(group(null), false, null));
    }

    // ---- D26 -------------------------------------------------------------

    @Test
    void lifeSafetyRow_passesAtCap_andIsNeverCounted() {
        stubUsage(15);
        assertDoesNotThrow(() -> quota.assertQuota(group("FREE"), true, null));
        verify(taskRepo, never()).countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                anyString(), anyString(), any());
    }

    @Test
    void groupInAlert_passesAtCap() {
        stubUsage(15);
        Group g = group("FREE");
        g.setAlert("Active");
        assertDoesNotThrow(() -> quota.assertQuota(g, false, null));
    }

    @Test
    void areaInAlert_passesAtCap_andIsAskedOnlyAtCap() {
        stubUsage(15);
        assertDoesNotThrow(() -> quota.assertQuota(group("FREE"), false, () -> true));

        stubUsage(3);
        java.util.concurrent.atomic.AtomicBoolean asked = new java.util.concurrent.atomic.AtomicBoolean();
        quota.assertQuota(group("FREE"), false, () -> { asked.set(true); return true; });
        assertFalse(asked.get(), "under the cap the area lookup (which may geocode) is skipped");
    }

    @Test
    void overCap_isSoftWhileBillingIsNotLive() {
        when(billing.isConfigured()).thenReturn(false);
        stubUsage(40);
        assertDoesNotThrow(() -> quota.assertQuota(group("FREE"), false, () -> false));
    }

    @Test
    void unexpiredOverride_isTheEffectiveTier() {
        Group g = group("FREE");
        g.setSubscriptionOverrideTier("AGENCY");
        g.setSubscriptionOverrideExpiresAt(Instant.now().plusSeconds(3600));
        assertDoesNotThrow(() -> quota.assertQuota(g, false, null));
        verify(taskRepo, never()).countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                anyString(), anyString(), any());
    }

    @Test
    void expiredOverride_fallsBackToTheStoredTier() {
        stubUsage(15);
        Group g = group("FREE");
        g.setSubscriptionOverrideTier("AGENCY");
        g.setSubscriptionOverrideExpiresAt(Instant.now().minusSeconds(60));
        assertThrows(QuotaExceededException.class, () -> quota.assertQuota(g, false, () -> false));
    }
}
