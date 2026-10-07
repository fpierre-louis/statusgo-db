package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.dto.AlertPresetDtos.AlertPresetDto;
import io.sitprep.sitprepapi.dto.AlertPresetDtos.AlertPresetsResponse;
import io.sitprep.sitprepapi.dto.UserAlertPreferenceDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Alert presets (CONTRACT §6, Ready for More B2): four convenience bundles over
 * {@link UserAlertPreference}.
 *
 * <p><b>Not a second policy engine.</b> A preset is only a PATCH: a
 * {@link UserAlertPreferenceDto} whose null fields mean "leave as is", applied
 * through {@link PushPolicyService#updatePreferences}. Lanes, quiet hours and
 * the critical bypass stay in {@link PushPolicyService}. The preset label is
 * not stored; {@code active} is recomputed from the preference fields.</p>
 *
 * <p><b>Safety floor.</b> Every preset sets the {@link #SAFETY_FLOOR} fields to
 * TRUE. Each of them, set false, silences a life-safety path: {@code pushEnabled}
 * demotes every critical category to the inbox, {@code inboxEnabled} strips the
 * inbox row a deferred push falls back to, and the five category toggles DROP
 * warnings, household alerts and plan activations outright. So a preset can
 * only strengthen safety, never weaken it; {@link #requireSafetyFloor} runs over
 * every definition in the constructor, so a bad edit fails the Spring context load and
 * {@code AlertPresetServiceTest}.</p>
 *
 * <p><b>Copy is checked against the code.</b> {@code PushPolicyService.CRITICAL_BYPASS}
 * lets only NWS Severe/Extreme, USGS M6+ ("Severe"), PLAN_ACTIVATION_RECEIVED and
 * GROUP_ALERT_HOUSEHOLD through quiet hours. WILDFIRE_NEAR and CHECK_IN_REQUEST
 * do not, and a quiet-hours-deferred hazard push writes no inbox row
 * ({@code AlertDispatchService.pushSevereAlert} keeps Lane A only), while a
 * deferred {@code NotificationService} send does (Lane B → log row). The
 * QUIET_HOURS safety note says exactly that.</p>
 */
@Service
public class AlertPresetService {

    /** One preference field a preset may set: its name and its entity getter. */
    record Field(String name, Function<UserAlertPreferenceDto, Object> fromPatch,
                 Function<UserAlertPreference, Object> fromPref) {}

    static final List<Field> SAFETY_FLOOR = List.of(
            new Field("pushEnabled", UserAlertPreferenceDto::pushEnabled, UserAlertPreference::isPushEnabled),
            new Field("inboxEnabled", UserAlertPreferenceDto::inboxEnabled, UserAlertPreference::isInboxEnabled),
            new Field("nwsAlerts", UserAlertPreferenceDto::nwsAlerts, UserAlertPreference::isNwsAlerts),
            new Field("earthquakes", UserAlertPreferenceDto::earthquakes, UserAlertPreference::isEarthquakes),
            new Field("wildfires", UserAlertPreferenceDto::wildfires, UserAlertPreference::isWildfires),
            new Field("groupAlerts", UserAlertPreferenceDto::groupAlerts, UserAlertPreference::isGroupAlerts),
            new Field("planActivations", UserAlertPreferenceDto::planActivations, UserAlertPreference::isPlanActivations));

    /** Fields compared for {@code active}. Timezone is deliberately absent. */
    private static final List<Field> COMPARED;
    static {
        List<Field> f = new ArrayList<>(SAFETY_FLOOR);
        f.add(new Field("activationAcks", UserAlertPreferenceDto::activationAcks, UserAlertPreference::isActivationAcks));
        f.add(new Field("taskAssignments", UserAlertPreferenceDto::taskAssignments, UserAlertPreference::isTaskAssignments));
        f.add(new Field("pendingMembers", UserAlertPreferenceDto::pendingMembers, UserAlertPreference::isPendingMembers));
        f.add(new Field("drills", UserAlertPreferenceDto::drills, UserAlertPreference::isDrills));
        f.add(new Field("quietHoursEnabled", UserAlertPreferenceDto::quietHoursEnabled, UserAlertPreference::isQuietHoursEnabled));
        f.add(new Field("quietStart", UserAlertPreferenceDto::quietStart, UserAlertPreference::getQuietStart));
        f.add(new Field("quietEnd", UserAlertPreferenceDto::quietEnd, UserAlertPreference::getQuietEnd));
        COMPARED = List.copyOf(f);
    }

    /**
     * A preset definition. {@code patch} null fields are left unchanged.
     * {@code takesTimezone}: the apply body's timezone is copied into the patch
     * (only when {@link ZoneId#of} accepts it).
     */
    public record Preset(String key, String title, String description, String safetyNote,
                         UserAlertPreferenceDto patch, boolean takesTimezone) {}

    private static final Boolean T = Boolean.TRUE;
    private static final Boolean F = Boolean.FALSE;

    static final List<Preset> PRESETS = List.of(
            new Preset("BALANCED", "Balanced",
                    "Every warning and household alert, plus the updates that keep a plan moving: "
                            + "acknowledgments, task assignments, join requests, and drill reminders.",
                    "Weather, earthquake, and wildfire warnings, plan activations, and household alerts stay on. "
                            + "Quiet hours are off, so they can reach you at any hour.",
                    floor(T, T, T, T, F, null, null), false),
            new Preset("HAZARD_AWARE", "Hazard aware",
                    "Every hazard warning near you, household alerts, and drill reminders. "
                            + "Your settings for acknowledgments, tasks, and join requests stay as they are.",
                    "Weather, earthquake, and wildfire warnings, plan activations, and household alerts stay on. "
                            + "Quiet hours are off, so warnings can reach you at any hour.",
                    floor(null, null, null, T, F, null, null), false),
            new Preset("HOUSEHOLD_FOCUS", "Household focus",
                    "Built around the people you plan with: household alerts, check-in requests, plan activations, "
                            + "acknowledgments, tasks, and join requests, with every warning still on.",
                    "Household alerts and plan activations stay on alongside weather, earthquake, and wildfire "
                            + "warnings. Quiet hours are off, so they can reach you at any hour.",
                    floor(T, T, T, T, F, null, null), false),
            new Preset("QUIET_HOURS", "Quiet hours",
                    "Quiet from 9 PM to 7 AM in your time zone. Acknowledgment updates and drill reminders are turned off.",
                    "Severe weather warnings, major earthquakes, plan activations, and household alerts still come "
                            + "through. Wildfire warnings and other notifications stay silent overnight; messages and "
                            + "requests from your people wait in your inbox.",
                    floor(F, null, null, F, T, LocalTime.of(21, 0), LocalTime.of(7, 0)), true));

    private final PushPolicyService pushPolicy;

    public AlertPresetService(PushPolicyService pushPolicy) {
        this.pushPolicy = pushPolicy;
        PRESETS.forEach(AlertPresetService::requireSafetyFloor);
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    @Transactional
    public AlertPresetsResponse list(String email) {
        return view(pushPolicy.getOrCreate(email));
    }

    @Transactional
    public AlertPresetsResponse apply(String email, String key, String timezone) {
        Preset preset = find(key);
        UserAlertPreferenceDto patch = preset.patch();
        if (preset.takesTimezone() && isValidZone(timezone)) {
            patch = withTimezone(patch, timezone.trim());
        }
        return view(pushPolicy.updatePreferences(email, patch));
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /**
     * Throws if {@code p} leaves any {@link #SAFETY_FLOOR} field anything other
     * than TRUE — false would silence a life-safety path, and null (omitted)
     * would let a stale false survive the preset.
     */
    static void requireSafetyFloor(Preset p) {
        if (p == null || p.patch() == null) {
            throw new IllegalStateException("Alert preset has no definition");
        }
        for (Field f : SAFETY_FLOOR) {
            if (!Boolean.TRUE.equals(f.fromPatch().apply(p.patch()))) {
                throw new IllegalStateException("Alert preset " + p.key() + " must set " + f.name()
                        + " to true (safety floor); it sets " + f.fromPatch().apply(p.patch()));
            }
        }
    }

    /** Current prefs equal every field this preset specifies (timezone ignored). */
    static boolean isActive(Preset p, UserAlertPreference pref) {
        for (Field f : COMPARED) {
            Object want = f.fromPatch().apply(p.patch());
            if (want != null && !Objects.equals(want, f.fromPref().apply(pref))) return false;
        }
        return true;
    }

    static Preset find(String key) {
        if (key != null) {
            String k = key.trim().toUpperCase(Locale.ROOT);
            for (Preset p : PRESETS) if (p.key().equals(k)) return p;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown alert preset");
    }

    private static AlertPresetsResponse view(UserAlertPreference pref) {
        List<AlertPresetDto> out = new ArrayList<>(PRESETS.size());
        for (Preset p : PRESETS) {
            out.add(new AlertPresetDto(p.key(), p.title(), p.description(), p.safetyNote(), isActive(p, pref)));
        }
        return new AlertPresetsResponse(List.copyOf(out), UserAlertPreferenceDto.fromEntity(pref));
    }

    private static boolean isValidZone(String tz) {
        if (tz == null || tz.isBlank()) return false;
        try {
            ZoneId.of(tz.trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The seven safety-floor fields TRUE, plus the preset's own values (null = unchanged). */
    private static UserAlertPreferenceDto floor(Boolean activationAcks, Boolean taskAssignments,
                                                Boolean pendingMembers, Boolean drills,
                                                Boolean quietHoursEnabled, LocalTime quietStart,
                                                LocalTime quietEnd) {
        return new UserAlertPreferenceDto(T, T, T, T, T, T, T,
                activationAcks, taskAssignments, pendingMembers, drills,
                quietHoursEnabled, quietStart, quietEnd, null);
    }

    private static UserAlertPreferenceDto withTimezone(UserAlertPreferenceDto d, String tz) {
        return new UserAlertPreferenceDto(d.pushEnabled(), d.inboxEnabled(), d.nwsAlerts(), d.earthquakes(),
                d.wildfires(), d.groupAlerts(), d.planActivations(), d.activationAcks(), d.taskAssignments(),
                d.pendingMembers(), d.drills(), d.quietHoursEnabled(), d.quietStart(), d.quietEnd(), tz);
    }
}
