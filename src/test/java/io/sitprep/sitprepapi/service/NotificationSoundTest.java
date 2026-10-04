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
    @DisplayName("a person who may be hiding gets no sound key, low priority, and a quiet Android notification")
    void silentDelivery() {
        NotificationService.Delivery d = NotificationService.deliveryFor(true, "new_member");
        assertThat(d.iosSound()).isNull();
        assertThat(d.apnsPriority()).isEqualTo("5");
        assertThat(d.android()).isNotNull();
    }

    @Test
    @DisplayName("an audible delivery wakes the device and uses the type's sound")
    void audibleDelivery() {
        NotificationService.Delivery d = NotificationService.deliveryFor(false, "check_in_request");
        assertThat(d.iosSound()).isEqualTo("sitprep-checkin.caf");
        assertThat(d.apnsPriority()).isEqualTo("10");
        assertThat(NotificationService.deliveryFor(false, "new_member").iosSound()).isEqualTo("default");
    }

    @Test
    @DisplayName("a lockdown warning multicast keeps the short system sound, never the longer alert tone")
    void lockdownHazardKeepsDefault() {
        assertThat(NotificationService.hazardSoundFor("hazard_alert", true)).isEqualTo("default");
        assertThat(NotificationService.hazardSoundFor("hazard_alert", false)).isEqualTo("sitprep-alert.caf");
    }

    @Test
    @DisplayName("everything else keeps the system default")
    void routine() {
        assertThat(NotificationService.iosSoundFor("new_member")).isEqualTo("default");
        assertThat(NotificationService.iosSoundFor("post_comment")).isEqualTo("default");
        assertThat(NotificationService.iosSoundFor(null)).isEqualTo("default");
    }
}
