package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.repo.HouseholdEventRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * D9: a daily sweep must drain the backlog inside one tick. alert_history takes
 * ~1,510 NWS rows a day; one 1,000-row batch a day loses ground every day.
 */
@ExtendWith(MockitoExtension.class)
class RetentionSweepServiceTest {

    @Mock NotificationLogRepo notificationLogRepo;
    @Mock HouseholdEventRepo householdEventRepo;
    @Mock AlertHistoryService alertHistoryService;

    RetentionSweepService service;

    @BeforeEach
    void setUp() {
        service = new RetentionSweepService(notificationLogRepo, householdEventRepo, alertHistoryService);
        ReflectionTestUtils.setField(service, "notificationLogRetentionDays", 30);
        ReflectionTestUtils.setField(service, "notificationLogArchivedRetentionDays", 7);
        ReflectionTestUtils.setField(service, "householdEventRetentionDays", 90);
        ReflectionTestUtils.setField(service, "sweepBatchSize", 1000);
        ReflectionTestUtils.setField(service, "maxBatchesPerTick", 5);
    }

    @Test
    void alertHistorySweep_drainsUntilAShortBatch() {
        when(alertHistoryService.retentionDays()).thenReturn(30);
        when(alertHistoryService.sweepOnce(1000)).thenReturn(1000, 1000, 37);

        service.scheduledAlertHistorySweep();

        verify(alertHistoryService, times(3)).sweepOnce(1000);
    }

    @Test
    void alertHistorySweep_emptyFirstBatch_isOneCall() {
        when(alertHistoryService.retentionDays()).thenReturn(30);
        when(alertHistoryService.sweepOnce(1000)).thenReturn(0);

        service.scheduledAlertHistorySweep();

        verify(alertHistoryService, times(1)).sweepOnce(1000);
    }

    @Test
    void anExactlyFullLastBatchCostsOneEmptyProbe() {
        when(alertHistoryService.retentionDays()).thenReturn(30);
        when(alertHistoryService.sweepOnce(1000)).thenReturn(1000, 0);

        service.scheduledAlertHistorySweep();

        verify(alertHistoryService, times(2)).sweepOnce(1000);
    }

    @Test
    void alertHistorySweep_stopsAtThePerTickCap() {
        when(alertHistoryService.retentionDays()).thenReturn(30);
        when(alertHistoryService.sweepOnce(1000)).thenReturn(1000);   // a backlog that never ends

        service.scheduledAlertHistorySweep();

        verify(alertHistoryService, times(5)).sweepOnce(1000);
    }

    @Test
    void aFailingBatchEndsTheTick_withoutThrowing() {
        when(alertHistoryService.retentionDays()).thenReturn(30);
        when(alertHistoryService.sweepOnce(1000))
                .thenReturn(1000)
                .thenThrow(new IllegalStateException("db away"));

        service.scheduledAlertHistorySweep();

        verify(alertHistoryService, times(2)).sweepOnce(1000);
    }

    @Test
    void drainReportsTheTotalAcrossBatches() {
        int[] returns = {1000, 1000, 37};
        int[] call = {0};

        int total = service.drain("Fake", 30, () -> returns[call[0]++]);

        assertThat(call[0]).isEqualTo(3);
        assertThat(total).isEqualTo(2037);
    }

    @Test
    void notificationLogSweep_drainsThroughTheRepo() {
        when(notificationLogRepo.findIdsOlderThan(any(), any(Pageable.class)))
                .thenReturn(ids(1000), ids(1000), ids(37));
        when(notificationLogRepo.deleteByIdIn(anyCollection()))
                .thenAnswer(inv -> ((java.util.Collection<?>) inv.getArgument(0)).size());

        service.scheduledNotificationLogSweep();

        verify(notificationLogRepo, times(3)).findIdsOlderThan(any(), any(Pageable.class));
        verify(notificationLogRepo, times(3)).deleteByIdIn(anyCollection());
    }

    @Test
    void householdEventSweep_nothingEligible_deletesNothing() {
        when(householdEventRepo.findIdsOlderThan(any(), any(Pageable.class))).thenReturn(List.of());

        service.scheduledHouseholdEventSweep();

        verify(householdEventRepo, times(1)).findIdsOlderThan(any(), any(Pageable.class));
        verify(householdEventRepo, never()).deleteByIdIn(anyCollection());
    }

    private static List<Long> ids(int n) {
        return LongStream.range(0, n).boxed().toList();
    }
}
