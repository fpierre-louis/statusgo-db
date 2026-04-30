package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.UserAlertPreference;

import java.time.LocalTime;

/**
 * Wire shape for {@link UserAlertPreference}. Used by both GET (full
 * shape returned to the FE) and PATCH (partial update — every field
 * is a boxed type so null means "don't change", letting the client
 * send only the toggled field instead of echoing the whole record).
 *
 * <p>Spec: {@code docs/PUSH_NOTIFICATION_POLICY.md} "UserAlertPreference
 * entity". Fields mirror the entity 1:1 except for type wrapping for
 * PATCH semantics.</p>
 */
public record UserAlertPreferenceDto(
        // Master switches
        Boolean pushEnabled,
        Boolean inboxEnabled,

        // Per-category opt-outs
        Boolean nwsAlerts,
        Boolean earthquakes,
        Boolean wildfires,
        Boolean groupAlerts,
        Boolean planActivations,
        Boolean activationAcks,
        Boolean taskAssignments,
        Boolean pendingMembers,

        // Quiet hours
        Boolean quietHoursEnabled,
        LocalTime quietStart,
        LocalTime quietEnd,
        String timezone
) {

    public static UserAlertPreferenceDto fromEntity(UserAlertPreference p) {
        return new UserAlertPreferenceDto(
                p.isPushEnabled(),
                p.isInboxEnabled(),
                p.isNwsAlerts(),
                p.isEarthquakes(),
                p.isWildfires(),
                p.isGroupAlerts(),
                p.isPlanActivations(),
                p.isActivationAcks(),
                p.isTaskAssignments(),
                p.isPendingMembers(),
                p.isQuietHoursEnabled(),
                p.getQuietStart(),
                p.getQuietEnd(),
                p.getTimezone()
        );
    }
}
