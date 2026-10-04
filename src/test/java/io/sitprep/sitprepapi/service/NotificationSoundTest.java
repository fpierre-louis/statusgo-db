package io.sitprep.sitprepapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which bundled sound an audible iOS push plays (sonic identity v1, 2026-10-04).
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
    @DisplayName("my family's alerts play the household tone")
    void householdTypes() {
        for (String t : new String[]{"alert", "group_status", "PLAN_ACTIVATION", "plan_activation"}) {
            assertThat(NotificationService.iosSoundFor(t)).as(t).isEqualTo("sitprep-household.caf");
        }
    }

    @Test
    @DisplayName("a hazard plays the hazard tone, distinct from the household one")
    void hazardType() {
        assertThat(NotificationService.iosSoundFor("hazard_alert")).isEqualTo("sitprep-hazard.caf");
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
        assertThat(NotificationService.deliveryFor(false, "new_member").iosSound()).isEqualTo("sitprep-note.caf");
    }

    @Test
    @DisplayName("a lockdown warning is silent but haptic: the silent sound, never a tone")
    void lockdownHazardIsSilentButHaptic() {
        assertThat(NotificationService.hazardSoundFor("hazard_alert", true)).isEqualTo("sitprep-silent.caf");
        assertThat(NotificationService.hazardSoundFor("hazard_alert", false)).isEqualTo("sitprep-hazard.caf");
    }

    @Test
    @DisplayName("everything routine plays the quiet SitPrep note")
    void routine() {
        assertThat(NotificationService.iosSoundFor("new_member")).isEqualTo("sitprep-note.caf");
        assertThat(NotificationService.iosSoundFor("post_comment")).isEqualTo("sitprep-note.caf");
        assertThat(NotificationService.iosSoundFor(null)).isEqualTo("sitprep-note.caf");
    }
}
