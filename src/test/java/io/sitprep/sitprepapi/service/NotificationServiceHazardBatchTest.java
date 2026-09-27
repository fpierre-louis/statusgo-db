package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceHazardBatchTest {

    @Test
    void providerRangesRespectFiveHundredTokenLimit() {
        assertThat(NotificationService.hazardBatchRanges(0)).isEmpty();
        assertThat(NotificationService.hazardBatchRanges(1))
                .containsExactly(new NotificationService.BatchRange(0, 1));
        assertThat(NotificationService.hazardBatchRanges(500))
                .containsExactly(new NotificationService.BatchRange(0, 500));
        assertThat(NotificationService.hazardBatchRanges(501))
                .containsExactly(
                        new NotificationService.BatchRange(0, 500),
                        new NotificationService.BatchRange(500, 501));
        assertThat(NotificationService.hazardBatchRanges(1201))
                .containsExactly(
                        new NotificationService.BatchRange(0, 500),
                        new NotificationService.BatchRange(500, 1000),
                        new NotificationService.BatchRange(1000, 1201));
    }
}
