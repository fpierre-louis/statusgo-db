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

    /**
     * The movement directive for this alert: the one thing that can demote a
     * household's saved meeting place or evacuation destination in favour of the
     * protective action actually in force.
     *
     * <p><b>Resolution order, and why.</b> The issuer's own CAP {@code responseType}
     * comes first, because it is the authority's statement about what to do. The
     * template's declared directive is the fallback for alerts that carry no
     * response type. Nothing else is consulted — in particular there is no matching
     * on event <i>names</i> or on wording, which would be a heuristic standing in
     * for a structured field and is the class of thing this file exists to avoid.</p>
     *
     * <p><b>RC-1 (2026-09-09).</b> This method previously read CAP for
     * {@code Evacuate} only. {@code Shelter} was honoured for the single literal
     * event name {@code "Shelter In Place Warning"}, and {@code Avoid} was not read
     * at all — so {@link MovementDirective#AVOID_AREA} could not be produced by any
     * production path, and the frontend's entire avoid branch was unreachable. A
     * dust-storm warning, whose protective action is to STOP MOVING, therefore left
     * the household's saved destination standing as the primary action. Both gaps
     * are closed here; see {@link #movementDirectiveFromCap}.</p>
     */
    private static MovementDirective movementDirectiveFor(NormalizedAlert alert,
                                                          DispatchTemplate template,
                                                          Set<ProtectiveAction> capActions) {
        // 1. An evacuation order from the issuer outranks everything.
        if (capActions != null && capActions.contains(ProtectiveAction.EVACUATE)) {
            return MovementDirective.EVACUATE;
        }

        // 2. The template's REVIEWED classification of this hazard. This sits
        //    above the remaining CAP reads on purpose: `Shelter` and `Avoid` say
        //    what the issuer wants people to do, but whether a hazard's sheltering
        //    guidance is a formal shelter-in-place order or graduated indoor-safety
        //    advice is a judgment somebody made in safety review, and a reviewed
        //    judgment outranks a re-derivation.
        MovementDirective declared = MovementDirective.parse(
                template == null || template.sitprep == null ? null : template.sitprep.movementDirective,
                MovementDirective.NONE);
        if (declared != MovementDirective.NONE) {
            return declared;
        }

        // 3. No reviewed classification. Fall back to what the issuer's own
        //    response type supports — see movementDirectiveFromCap.
        return movementDirectiveFromCap(template, capActions);
    }

    /**
     * The directive an unreviewed alert's CAP {@code responseType} can support.
     *
     * <p>Reached only when the template declares no directive of its own, so this
     * is the answer for an alert SitPrep has no reviewed classification for.</p>
     *
     * <p><b>{@code Avoid} becomes {@link MovementDirective#AVOID_AREA}.</b> "Avoid
     * the area" is not a term of art — it means what it says, so reading it
     * directly invents nothing. Before RC-1 this branch did not exist at all and
     * {@code AVOID_AREA} was unreachable in production, which is why a dust-storm
     * warning left a saved destination standing as the primary action.</p>
     *
     * <p><b>{@code Shelter} becomes {@link MovementDirective#FOLLOW_OFFICIAL_INSTRUCTION},
     * NOT shelter-in-place.</b> This preserves a ruling this codebase already made
     * and tests ({@code generalIndoorSafetyIsNotAnOfficialShelterInPlaceDirective}):
     * "move indoors away from windows" during a thunderstorm is general indoor
     * safety, and calling it a shelter-in-place order dilutes a term that means
     * something specific. But {@code NONE} was the wrong alternative — it let the
     * household's saved meeting place stand as the primary action, which is the P0.
     * "Follow official instructions" is the honest middle: it declines to name a
     * protective action SitPrep has not classified, and it still refuses to let a
     * prepared destination read as the current instruction. A hazard whose
     * sheltering guidance IS a formal order says so in its template.</p>
     *
     * <p><b>Both respect the template's {@code incompatibleResponseTypes}.</b>
     * Mapping blindly is the failure mode to avoid: a flash-flood template declares
     * {@code Shelter} incompatible precisely because sheltering in place is the
     * wrong answer to rising water.</p>
     */
    private static MovementDirective movementDirectiveFromCap(DispatchTemplate template,
                                                              Set<ProtectiveAction> capActions) {
        if (capActions == null || capActions.isEmpty()) return MovementDirective.NONE;

        if (capActions.contains(ProtectiveAction.AVOID)
                && !responseTypeIsIncompatible(template, "Avoid")) {
            return MovementDirective.AVOID_AREA;
        }
        if (capActions.contains(ProtectiveAction.SHELTER)
                && !responseTypeIsIncompatible(template, "Shelter")) {
            return MovementDirective.FOLLOW_OFFICIAL_INSTRUCTION;
        }
        return MovementDirective.NONE;
    }

    /** Has this template declared {@code responseType} incompatible with its hazard? */
    private static boolean responseTypeIsIncompatible(DispatchTemplate template, String responseType) {
        if (template == null || template.incompatibleResponseTypes == null) return false;
        for (String declared : template.incompatibleResponseTypes) {
            if (declared != null && declared.equalsIgnoreCase(responseType)) return true;
        }
        return false;
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

    /**
     * Why this message must not be rendered <b>at all</b>, or null.
     *
     * <p>Distinct from {@link #lifecycleBlockReason} and deliberately narrower:
     * these are the reasons a message is not a publishable account of anything,
     * ever — a drill, a private-scope message, a retraction, a protocol
     * acknowledgement. They do not expire and are not survived by the passage
     * of time.</p>
     *
     * <p><b>Why the split exists.</b> Alert history is made <i>entirely</i> of
     * alerts that have ended, so a history surface cannot use
     * {@code lifecycleBlockReason} as its row filter — {@code alert_expired}
     * would suppress every row it was built to show. It uses this instead, and
     * still routes each card through the full policy so an ended alert's
     * present-tense guidance stays suppressed. Different questions: "may we
     * show this message" versus "is this hazard still on".</p>
     *
     * <p>A {@code Cancel} is blocked here rather than merely aged out on
     * purpose. It is not an event; it is a retraction of one, and the thing it
     * retracts is already its own row. Listing it would double-count the
     * event.</p>
     */
    public static String publishabilityBlockReason(NormalizedAlert alert) {
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
        return null;
    }

    /**
     * Why this alert is not a live instruction, or null.
     *
     * <p>Everything {@link #publishabilityBlockReason} blocks, plus the three
     * ways CAP says "this is over": an {@code AllClear} response, {@code Past}
     * urgency, and an {@code endsAt} in the past.</p>
     */
    public static String lifecycleBlockReason(NormalizedAlert alert) {
        String unpublishable = publishabilityBlockReason(alert);
        if (unpublishable != null) return unpublishable;
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
