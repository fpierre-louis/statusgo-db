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
 * lets only these through quiet hours: NWS Severe/Extreme warnings, USGS M6+
 * ("Severe"), the {@code emergency} tier of a verified agency's alert,
 * PLAN_ACTIVATION_RECEIVED and GROUP_ALERT_HOUSEHOLD. Everything else that would
 * push (WILDFIRE_NEAR — the NWS Fire Warning —, CHECK_IN_REQUEST, DIRECT_MESSAGE,
 * a warning NWS rated below Severe, an agency advisory or notice, the readiness
 * and account reminders) is demoted to Lane B, and Lane B writes an inbox row
 * with no push on every send path: {@code NotificationService}'s Lane B branch,
 * {@code AlertDispatchService.pushSevereAlert} →
 * {@code NotificationService.logHazardAlertInboxOnly} for hazards, and
 * {@code AgencyAlertDispatchService} for agency alerts. The QUIET_HOURS note
 * names only what is true of that (re-verified 2026-10-07, when two
 * owner-approved decisions widened what comes through):</p>
 * <ul>
 *   <li><b>"strong earthquakes nearby"</b> — since 2026-10-07 an M6.0+ quake
 *       pushes to located users within 80 km of the epicenter
 *       ({@code AlertDispatchService.isMajorQuakePush}), and
 *       {@code USGS_QUAKE_MAJOR} bypasses quiet hours. M5.5–5.9 is
 *       feed-only at any hour, so the note says "strong", not "earthquakes".
 *       (A quake first dispatched more than two hours after it struck is
 *       feed-only too; the clause is about what quiet hours hold, and they
 *       never hold a fresh strong quake.)</li>
 *   <li><b>"official emergency alerts"</b> — a verified agency's alert sent at
 *       the {@code emergency} tier. Its {@code advisory} and {@code notice}
 *       tiers wait for morning, so the note does not say "official alerts".</li>
 *   <li>It does not say "every other alert". Feed-only warnings (Red Flag,
 *       watches) and M5.5–5.9 quakes never reach the inbox at any hour.</li>
 * </ul>
 * <p>{@code AlertPresetServiceTest.theQuietHoursNoteIsWhatThePolicyDoes} pins
 * the wording to those lanes.</p>
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

    /** Every clause is a lane {@code AlertPresetServiceTest} asserts; see the class note. */
    static final String QUIET_HOURS_SAFETY_NOTE =
            "Severe weather warnings, strong earthquakes nearby, official emergency alerts, "
                    + "plan activations, and household alerts still come through. "
                    + "Fire warnings, check-in requests, and messages go to your inbox instead of "
                    + "your lock screen.";

    private static final Boolean T = Boolean.TRUE;
    private static final Boolean F = Boolean.FALSE;

    static final List<Preset> PRESETS = List.of(
            new Preset("BALANCED", "Balanced",
                    "Every warning and household alert, plus plan updates, tasks, and drill reminders.",
                    "Safety alerts can reach you at any hour.",
                    floor(T, T, T, T, F, null, null), false),
            new Preset("HAZARD_AWARE", "Hazard aware",
                    "Warnings and household alerts first, with fewer coordination pings.",
                    "Acknowledgment, task, and join-request notifications are turned off, including in your inbox.",
                    floor(F, F, F, T, F, null, null), false),
            new Preset("HOUSEHOLD_FOCUS", "Household focus",
                    "Every household and plan update, without weekly practice reminders.",
                    "Safety alerts can reach you at any hour.",
                    floor(T, T, T, F, F, null, null), false),
            new Preset("QUIET_HOURS", "Quiet hours",
                    "Quiet from 9 PM to 7 AM. Acknowledgments and drill reminders are off.",
                    QUIET_HOURS_SAFETY_NOTE,
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

    /** The fields a preset specifies (non-null patch values), by name. Timezone excluded. */
    static java.util.Map<String, Object> specifiedFields(Preset p) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Field f : COMPARED) {
            Object v = f.fromPatch().apply(p.patch());
            if (v != null) out.put(f.name(), v);
        }
        return out;
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
