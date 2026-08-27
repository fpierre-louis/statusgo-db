package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Central safety decision for alert rendering and dispatch.
 *
 * <p>The policy is intentionally boring: it reads structured CAP/provider
 * fields and template metadata, then returns a small decision object. It does
 * not inspect the wording of either official instructions or SitPrep copy at
 * runtime; text scanning belongs in lint/tests, not in a life-safety gate.</p>
 */
public final class AlertSafetyPolicy {

    private AlertSafetyPolicy() {}

    public enum ProtectiveAction {
        EVACUATE, SHELTER, AVOID, PREPARE, MONITOR, EXECUTE, ASSESS, ALL_CLEAR,
        NONE, HAZARD_SPECIFIC, UNKNOWN
    }

    public enum DispatchMode {
        CRITICAL_PUSH("critical_push"),
        ATTENTION("attention"),
        PREPARE("prepare"),
        FEED("feed"),
        SUPPRESS("suppress");

        private final String wire;
        DispatchMode(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        static DispatchMode parse(String raw, DispatchMode fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (DispatchMode mode : values()) {
                if (mode.name().equals(key) || mode.wire.equalsIgnoreCase(raw)) return mode;
            }
            return fallback;
        }
    }

    public enum GuidanceMode {
        SUPPLEMENT_OFFICIAL("supplement_official"),
        OFFICIAL_ONLY("official_only"),
        NO_GUIDANCE("no_guidance");

        private final String wire;
        GuidanceMode(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        static GuidanceMode parse(String raw, GuidanceMode fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (GuidanceMode mode : values()) {
                if (mode.name().equals(key) || mode.wire.equalsIgnoreCase(raw)) return mode;
            }
            return fallback;
        }
    }

    public enum MovementDirective {
        NONE("none"),
        EVACUATE("evacuate"),
        SHELTER_IN_PLACE("shelter_in_place"),
        AVOID_AREA("avoid_area"),
        FOLLOW_OFFICIAL_INSTRUCTION("follow_official_instruction");

        private final String wire;
        MovementDirective(String wire) { this.wire = wire; }
        public String wire() { return wire; }

        static MovementDirective parse(String raw, MovementDirective fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (MovementDirective directive : values()) {
                if (directive.name().equals(key) || directive.wire.equalsIgnoreCase(raw)) {
                    return directive;
                }
            }
            return fallback;
        }
    }

    public enum Compatibility {
        COMPATIBLE("compatible"),
        INCOMPATIBLE("incompatible"),
        UNKNOWN("unknown");

        private final String wire;
        Compatibility(String wire) { this.wire = wire; }
        public String wire() { return wire; }
    }

    public record Decision(
            DispatchMode dispatchMode,
            GuidanceMode guidanceMode,
            Compatibility compatibility,
            Set<ProtectiveAction> capActions,
            MovementDirective movementDirective,
            String reason
    ) {
        public boolean allowsSitPrepGuidance() {
            return guidanceMode == GuidanceMode.SUPPLEMENT_OFFICIAL
                    && compatibility == Compatibility.COMPATIBLE;
        }

        public boolean criticalPush() {
            return dispatchMode == DispatchMode.CRITICAL_PUSH;
        }
    }

    public static Decision evaluate(NormalizedAlert alert, DispatchTemplate template) {
        Set<ProtectiveAction> capActions = actionsFromAlert(alert);
        MovementDirective movementDirective = movementDirectiveFor(alert, template, capActions);
        String lifecycleBlock = lifecycleBlockReason(alert);
        if (lifecycleBlock != null) {
            return new Decision(
                    DispatchMode.SUPPRESS,
                    GuidanceMode.NO_GUIDANCE,
                    Compatibility.INCOMPATIBLE,
                    capActions,
                    movementDirective,
                    lifecycleBlock);
        }

        DispatchMode dispatchMode = dispatchModeFor(alert, template, capActions);
        Compatibility compatibility = compatibilityFor(alert, template, capActions);
        GuidanceMode requestedGuidance = GuidanceMode.parse(
                template == null || template.sitprep == null ? null : template.sitprep.guidanceMode,
                GuidanceMode.SUPPLEMENT_OFFICIAL);

        if (template == null) {
            return new Decision(dispatchMode, GuidanceMode.NO_GUIDANCE,
                    Compatibility.UNKNOWN, capActions, movementDirective, "no_template");
        }
        if (!template.isSafetyApproved()) {
            return new Decision(dispatchMode, officialFallbackMode(alert),
                    compatibility, capActions, movementDirective, "template_not_safety_approved");
        }
        if (compatibility != Compatibility.COMPATIBLE) {
            return new Decision(dispatchMode, officialFallbackMode(alert),
                    compatibility, capActions, movementDirective, "template_" + compatibility.wire());
        }
        return new Decision(dispatchMode, requestedGuidance, compatibility,
                capActions, movementDirective, "template_compatible");
    }

    private static GuidanceMode officialFallbackMode(NormalizedAlert alert) {
        return hasOfficialText(alert) ? GuidanceMode.OFFICIAL_ONLY : GuidanceMode.NO_GUIDANCE;
    }

    private static boolean hasOfficialText(NormalizedAlert alert) {
        return notBlank(alert == null ? null : alert.instruction())
                || notBlank(alert == null ? null : alert.description())
                || notBlank(alert == null ? null : alert.headline());
    }

    private static DispatchMode dispatchModeFor(NormalizedAlert alert,
                                                DispatchTemplate template,
                                                Set<ProtectiveAction> capActions) {
        if (template == null) return DispatchMode.FEED;
        DispatchMode legacy = template.isWarningTier()
                && AlertDispatchService.tierMatchesAlertShape(alert, template)
                ? DispatchMode.CRITICAL_PUSH
                : DispatchMode.PREPARE;
        DispatchMode declared = DispatchMode.parse(
                template.sitprep == null ? null : template.sitprep.dispatchMode,
                legacy);
        if (template.sitprep != null && template.sitprep.impactAware) {
            return hasCriticalImpactSignal(alert)
                    ? DispatchMode.CRITICAL_PUSH
                    : declared == DispatchMode.CRITICAL_PUSH ? DispatchMode.ATTENTION : declared;
        }
        return declared;
    }

    private static boolean hasCriticalImpactSignal(NormalizedAlert alert) {
        return hasNwsHighImpactParameter(alert);
    }

    private static boolean hasNwsHighImpactParameter(NormalizedAlert alert) {
        if (alert == null || alert.parameters() == null || alert.parameters().isEmpty()) {
            return false;
        }
        String event = alert.event();
        if ("Severe Thunderstorm Warning".equalsIgnoreCase(event)) {
            return parameterValueIn(alert, "thunderstormDamageThreat",
                    "CONSIDERABLE", "DESTRUCTIVE");
        }
        if ("Flash Flood Warning".equalsIgnoreCase(event)) {
            return parameterValueIn(alert, "flashFloodDamageThreat",
                    "CONSIDERABLE", "CATASTROPHIC");
        }
        if ("Snow Squall Warning".equalsIgnoreCase(event)) {
            return parameterValueIn(alert, "snowSquallImpact", "SIGNIFICANT")
                    || parameterValueIn(alert, "WEAHandling", "WEA");
        }
        // Live NWS Flood Warning samples expose metadata such as VTEC,
        // NWSheadline, BLOCKCHANNEL, and eventEndingTime, but no documented
        // flood damage-threat extension equivalent to Flash Flood Warning.
        return false;
    }

    private static boolean parameterValueIn(NormalizedAlert alert,
                                            String parameterName,
                                            String... allowedValues) {
        List<String> values = alert.parameters().get(parameterName);
        if (values == null || values.isEmpty()) return false;
        Set<String> allowed = new LinkedHashSet<>();
        for (String value : allowedValues) {
            allowed.add(value.toUpperCase(Locale.ROOT));
        }
        for (String value : values) {
            if (value != null && allowed.contains(value.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static MovementDirective movementDirectiveFor(NormalizedAlert alert,
                                                          DispatchTemplate template,
                                                          Set<ProtectiveAction> capActions) {
        if (capActions != null && capActions.contains(ProtectiveAction.EVACUATE)) {
            return MovementDirective.EVACUATE;
        }
        MovementDirective declared = MovementDirective.parse(
                template == null || template.sitprep == null ? null : template.sitprep.movementDirective,
                MovementDirective.NONE);
        if (declared != MovementDirective.NONE) {
            return declared;
        }
        if (capActions != null && capActions.contains(ProtectiveAction.SHELTER)
                && alert != null && "Shelter In Place Warning".equalsIgnoreCase(alert.event())) {
            return MovementDirective.SHELTER_IN_PLACE;
        }
        return MovementDirective.NONE;
    }

    private static Compatibility compatibilityFor(NormalizedAlert alert,
                                                  DispatchTemplate template,
                                                  Set<ProtectiveAction> capActions) {
        if (template == null) return Compatibility.UNKNOWN;
        if (capActions.contains(ProtectiveAction.ALL_CLEAR)) return Compatibility.INCOMPATIBLE;
        if (template.incompatibleResponseTypes != null) {
            for (String response : template.incompatibleResponseTypes) {
                if (containsResponse(alert, response)) return Compatibility.INCOMPATIBLE;
            }
        }
        if (template.compatibleResponseTypes != null && !template.compatibleResponseTypes.isEmpty()) {
            for (String response : template.compatibleResponseTypes) {
                if (containsResponse(alert, response)) return Compatibility.COMPATIBLE;
            }
            return responseTypes(alert).isEmpty()
                    ? Compatibility.UNKNOWN
                    : Compatibility.INCOMPATIBLE;
        }
        if (template.protectiveAction != null && capActions.contains(template.protectiveAction)) {
            return Compatibility.COMPATIBLE;
        }
        return Compatibility.UNKNOWN;
    }

    public static Set<ProtectiveAction> actionsFromAlert(NormalizedAlert alert) {
        Set<ProtectiveAction> out = new LinkedHashSet<>();
        for (String response : responseTypes(alert)) {
            out.add(actionFromResponse(response));
        }
        if (out.isEmpty()) out.add(ProtectiveAction.UNKNOWN);
        return Set.copyOf(out);
    }

    private static List<String> responseTypes(NormalizedAlert alert) {
        if (alert == null) return List.of();
        if (alert.responseTypes() != null && !alert.responseTypes().isEmpty()) {
            return alert.responseTypes();
        }
        return notBlank(alert.response()) ? List.of(alert.response()) : List.of();
    }

    private static boolean containsResponse(NormalizedAlert alert, String expected) {
        if (expected == null) return false;
        for (String response : responseTypes(alert)) {
            if (expected.equalsIgnoreCase(response)) return true;
        }
        return false;
    }

    public static ProtectiveAction actionFromResponse(String response) {
        if (response == null || response.isBlank()) return ProtectiveAction.UNKNOWN;
        return switch (response.trim().toLowerCase(Locale.ROOT)) {
            case "evacuate" -> ProtectiveAction.EVACUATE;
            case "shelter" -> ProtectiveAction.SHELTER;
            case "avoid" -> ProtectiveAction.AVOID;
            case "prepare" -> ProtectiveAction.PREPARE;
            case "monitor" -> ProtectiveAction.MONITOR;
            case "execute" -> ProtectiveAction.EXECUTE;
            case "assess" -> ProtectiveAction.ASSESS;
            case "allclear", "all_clear", "all clear" -> ProtectiveAction.ALL_CLEAR;
            case "none" -> ProtectiveAction.NONE;
            default -> ProtectiveAction.UNKNOWN;
        };
    }

    public static String lifecycleBlockReason(NormalizedAlert alert) {
        if (alert == null) return "missing_alert";
        if (notBlank(alert.status()) && !"Actual".equalsIgnoreCase(alert.status())) {
            return "cap_status_" + alert.status();
        }
        if (notBlank(alert.scope()) && !"Public".equalsIgnoreCase(alert.scope())) {
            return "cap_scope_" + alert.scope();
        }
        if (notBlank(alert.messageType())) {
            String messageType = alert.messageType().trim().toLowerCase(Locale.ROOT);
            if (messageType.equals("cancel") || messageType.equals("ack") || messageType.equals("error")) {
                return "cap_message_type_" + alert.messageType();
            }
        }
        if (containsResponse(alert, "AllClear")) return "cap_response_all_clear";
        if ("Past".equalsIgnoreCase(alert.urgency())) return "cap_urgency_past";
        Instant expires = parseInstant(alert.endsAt());
        if (expires != null && expires.isBefore(Instant.now())) return "alert_expired";
        return null;
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso); }
        catch (Exception ignored) { return null; }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
