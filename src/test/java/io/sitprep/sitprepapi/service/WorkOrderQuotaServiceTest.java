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
 */
class WorkOrderQuotaServiceTest {

    private static final String GROUP_ID = "grp-1";

    private PostRepo taskRepo;
    private WorkOrderQuotaService quota;

    @BeforeEach
    void setUp() {
        taskRepo = mock(PostRepo.class);
        quota = new WorkOrderQuotaService(taskRepo);
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
                () -> quota.assertQuota(group("FREE")));
        assertEquals(15, ex.getCap());
        assertEquals(15L, ex.getUsed());
    }

    @Test
    void freeTier_underCap_passes() {
        stubUsage(14);
        assertDoesNotThrow(() -> quota.assertQuota(group("FREE")));
    }

    @Test
    void agencyTier_isUnlimited_neverThrows_andSkipsTheCountQuery() {
        assertDoesNotThrow(() -> quota.assertQuota(group("AGENCY")));
        // Unlimited tiers short-circuit before ever counting usage.
        verify(taskRepo, never()).countByGroupIdAndKindAndCreatedAtGreaterThanEqual(
                anyString(), anyString(), any());
    }

    @Test
    void nullGroup_isNeverMetered() {
        assertDoesNotThrow(() -> quota.assertQuota(null));
        verifyNoInteractions(taskRepo);
    }

    @Test
    void legacyNullTier_readsAsFree_andEnforces() {
        stubUsage(15);
        assertThrows(QuotaExceededException.class,
                () -> quota.assertQuota(group(null)));
    }
}
