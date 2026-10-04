package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which bundled sound an audible iOS push plays (haptics & sound epic, D3).
 * The file names must match ios/App/App/sounds/ in the FE repo; a mismatch
 * plays the system default, so this pins the names.
 */
class NotificationSoundTest {

    @Test
    @DisplayName("a check-in request plays the check-in tone")
    void checkInRequest() {
        assertThat(NotificationService.iosSoundFor("check_in_request")).isEqualTo("sitprep-checkin.caf");
    }

    @Test
    @DisplayName("every time-sensitive safety type plays the alert tone")
    void safetyTypes() {
        for (String t : new String[]{"alert", "group_status", "hazard_alert", "PLAN_ACTIVATION", "plan_activation"}) {
            assertThat(NotificationService.iosSoundFor(t)).as(t).isEqualTo("sitprep-alert.caf");
        }
    }

    @Test
    @DisplayName("everything else keeps the system default")
    void routine() {
        assertThat(NotificationService.iosSoundFor("new_member")).isEqualTo("default");
        assertThat(NotificationService.iosSoundFor("post_comment")).isEqualTo("default");
        assertThat(NotificationService.iosSoundFor(null)).isEqualTo("default");
    }
}
