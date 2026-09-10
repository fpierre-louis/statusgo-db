package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AlertSafetyPolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nwsResponseTypeArraySurvivesIngest() throws Exception {
        String json = """
                {
                  "type": "FeatureCollection",
                  "features": [{
                    "type": "Feature",
                    "geometry": null,
                    "properties": {
                      "id": "urn:test:1",
                      "sender": "w-nws.webmaster@noaa.gov",
                      "sent": "2026-08-27T10:00:00Z",
                      "status": "Actual",
                      "messageType": "Alert",
                      "scope": "Public",
                      "event": "Shelter In Place Warning",
                      "eventCode": {"SAME": ["SPW"]},
                      "severity": "Severe",
                      "urgency": "Immediate",
                      "certainty": "Observed",
                      "response": ["Shelter", "Monitor", "NewFutureValue"],
                      "headline": "Shelter In Place Warning issued by NWS Test",
                      "description": "Official description.",
                      "instruction": "Official instruction.",
                      "areaDesc": "Test Area",
                      "effective": "2026-08-27T10:00:00Z",
                      "expires": "2026-08-27T12:00:00Z",
                      "geocode": {"UGC": ["UTZ001"], "SAME": ["049001"]},
                      "parameters": {
                        "thunderstormDamageThreat": ["DESTRUCTIVE"],
                        "BLOCKCHANNEL": ["EAS", "NWEM"]
                      },
                      "references": []
                    }
                  }]
                }
                """;

        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        NormalizedAlert alert = ingest.parseNwsFeed(MAPPER.readTree(json)).get(0);

        assertThat(alert.response()).isEqualTo("Shelter");
        assertThat(alert.responseTypes()).containsExactly("Shelter", "Monitor", "NewFutureValue");
        assertThat(alert.unknownResponseTypes()).containsExactly("NewFutureValue");
        assertThat(alert.scope()).isEqualTo("Public");
        assertThat(alert.sender()).isEqualTo("w-nws.webmaster@noaa.gov");
        assertThat(alert.sent()).isEqualTo("2026-08-27T10:00:00Z");
        assertThat(alert.eventCodes()).contains("SPW");
        assertThat(alert.parameters()).containsEntry("thunderstormDamageThreat", List.of("DESTRUCTIVE"));
        assertThat(alert.parameters()).containsEntry("BLOCKCHANNEL", List.of("EAS", "NWEM"));
        assertThat(alert.sourceSystem()).isEqualTo("NWS_API");
    }

    @Test
    void unapprovedTemplateSuppressesSitPrepGuidanceButCanStayCritical() throws Exception {
        AlertDispatchService.DispatchTemplate template = reviewedTemplate(
                "source_verified",
                "EVACUATE",
                List.of("Evacuate"),
                List.of("Shelter", "AllClear"),
                "critical_push",
                "supplement_official",
                "https://www.ready.gov/evacuation");
        NormalizedAlert alert = TestAlerts.nws("Evacuation Immediate")
                .responseTypes(List.of("Evacuate"))
                .instruction("Move to higher ground if officials tell you to.")
                .build();

        AlertSafetyPolicy.Decision decision =
                AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY);
        assertThat(decision.movementDirective()).isEqualTo(AlertSafetyPolicy.MovementDirective.EVACUATE);
        assertThat(decision.allowsSitPrepGuidance()).isFalse();
        assertThat(decision.reason()).isEqualTo("template_not_safety_approved");
    }

    @Test
    void sourceVerifiedTemplateIsStillNotHumanApproved() throws Exception {
        AlertDispatchService.DispatchTemplate template = reviewedTemplate(
                "source_verified",
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "critical_push",
                "supplement_official",
                "https://www.weather.gov/safety/tornado-during");
        NormalizedAlert alert = TestAlerts.nws("Policy Test Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Official instruction.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(template.isSafetyApproved()).isFalse();
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY);
        assertThat(decision.allowsSitPrepGuidance()).isFalse();
        assertThat(decision.reason()).isEqualTo("template_not_safety_approved");
    }

    @Test
    void evidenceHostValidationDoesNotUseNaiveGovSuffixChecks() throws Exception {
        AlertDispatchService.DispatchTemplate template = reviewedTemplate(
                "approved",
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "critical_push",
                "supplement_official",
                "https://weather.gov.example.com/safety/tornado-during");

        assertThat(template.isSafetyApproved()).isFalse();
    }

    @Test
    void cdcAndNrcHostsAreAllowedButLookalikesFail() throws Exception {
        AlertDispatchService.DispatchTemplate cdc = reviewedTemplate(
                "approved",
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "attention",
                "supplement_official",
                "https://www.cdc.gov/chemical-emergencies/response/shelter-in-place.html");
        AlertDispatchService.DispatchTemplate nrc = reviewedTemplate(
                "approved",
                "HAZARD_SPECIFIC",
                List.of("Monitor"),
                List.of("AllClear"),
                "attention",
                "official_only",
                "https://www.nrc.gov/about-nrc/emerg-preparedness");
        AlertDispatchService.DispatchTemplate fake = reviewedTemplate(
                "approved",
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "attention",
                "supplement_official",
                "https://cdc.gov.example.com/not-real");

        assertThat(cdc.isSafetyApproved()).isTrue();
        assertThat(nrc.isSafetyApproved()).isTrue();
        assertThat(fake.isSafetyApproved()).isFalse();
    }

    @Test
    void explicitDispatchModeOutranksLegacyWarningTier() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "feed",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Policy Test Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Official instruction.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(template.isWarningTier()).isTrue();
        assertThat(decision.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.FEED);
    }

    /**
     * General indoor safety is not a shelter-in-place order. That ruling stands.
     *
     * <p><b>Amended by RC-1 (2026-09-09).</b> The claim in this test's name is
     * unchanged and still asserted. What changed is the alternative: this
     * previously expected {@code NONE}, and {@code NONE} turned out to be the
     * dangerous state — it is what let a household's saved meeting place stand as
     * the primary action during a warning, which is the P0 this suite now guards.
     * The truthful middle is {@code FOLLOW_OFFICIAL_INSTRUCTION}: it declines to
     * call graduated indoor-safety advice a formal sheltering order, and it still
     * refuses to let a prepared destination read as the current instruction.</p>
     */
    @Test
    void generalIndoorSafetyIsNotAnOfficialShelterInPlaceDirective() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "attention",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Severe Thunderstorm Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Move indoors away from windows.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.movementDirective())
                .as("general indoor safety must never be presented as a shelter-in-place order")
                .isNotEqualTo(AlertSafetyPolicy.MovementDirective.SHELTER_IN_PLACE);
        assertThat(decision.movementDirective())
                .as("but it must not be NONE either — that is what let a saved destination stand")
                .isEqualTo(AlertSafetyPolicy.MovementDirective.FOLLOW_OFFICIAL_INSTRUCTION);
    }

    @Test
    void shelterInPlaceWarningIsATrueShelterMovementDirective() {
        AlertDispatchService dispatch =
                new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
        NormalizedAlert alert = TestAlerts.nws("Shelter In Place Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Shelter in place now.")
                .build();

        AlertSafetyPolicy.Decision decision =
                AlertSafetyPolicy.evaluate(alert, dispatch.matchForAlert(alert).orElseThrow());

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.SHELTER_IN_PLACE);
    }

    @Test
    void impactAwareWarningsNeedRealNwsImpactParametersBeforeCriticalPush() {
        AlertDispatchService dispatch =
                new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();

        NormalizedAlert ordinarySevereStorm = TestAlerts.nws("Severe Thunderstorm Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Likely")
                .responseTypes(List.of("Shelter"))
                .instruction("Go indoors.")
                .build();
        AlertSafetyPolicy.Decision ordinary = AlertSafetyPolicy.evaluate(
                ordinarySevereStorm, dispatch.matchForAlert(ordinarySevereStorm).orElseThrow());
        assertThat(ordinary.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.ATTENTION);

        NormalizedAlert extremeButMissingTag = TestAlerts.nws("Severe Thunderstorm Warning")
                .severity("Extreme")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Shelter"))
                .instruction("Go indoors.")
                .build();
        AlertSafetyPolicy.Decision missingTag = AlertSafetyPolicy.evaluate(
                extremeButMissingTag, dispatch.matchForAlert(extremeButMissingTag).orElseThrow());
        assertThat(missingTag.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.ATTENTION);

        NormalizedAlert destructiveSevereStorm = TestAlerts.nws("Severe Thunderstorm Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Shelter"))
                .parameters(Map.of("thunderstormDamageThreat", List.of("DESTRUCTIVE")))
                .instruction("Go indoors.")
                .build();
        AlertSafetyPolicy.Decision destructive = AlertSafetyPolicy.evaluate(
                destructiveSevereStorm, dispatch.matchForAlert(destructiveSevereStorm).orElseThrow());
        assertThat(destructive.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);

        NormalizedAlert considerableSevereStorm = TestAlerts.nws("Severe Thunderstorm Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Shelter"))
                .parameters(Map.of("thunderstormDamageThreat", List.of("CONSIDERABLE")))
                .instruction("Go indoors.")
                .build();
        AlertSafetyPolicy.Decision considerableStorm = AlertSafetyPolicy.evaluate(
                considerableSevereStorm, dispatch.matchForAlert(considerableSevereStorm).orElseThrow());
        assertThat(considerableStorm.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);

        NormalizedAlert baseFlashFlood = TestAlerts.nws("Flash Flood Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Avoid"))
                .instruction("Avoid flood water.")
                .build();
        AlertSafetyPolicy.Decision baseFlood = AlertSafetyPolicy.evaluate(
                baseFlashFlood, dispatch.matchForAlert(baseFlashFlood).orElseThrow());
        assertThat(baseFlood.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.ATTENTION);

        NormalizedAlert considerableFlashFlood = TestAlerts.nws("Flash Flood Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Avoid"))
                .parameters(Map.of("flashFloodDamageThreat", List.of("CONSIDERABLE")))
                .instruction("Avoid flood water.")
                .build();
        AlertSafetyPolicy.Decision considerableFlood = AlertSafetyPolicy.evaluate(
                considerableFlashFlood, dispatch.matchForAlert(considerableFlashFlood).orElseThrow());
        assertThat(considerableFlood.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);

        NormalizedAlert catastrophicFlashFlood = TestAlerts.nws("Flash Flood Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Avoid"))
                .parameters(Map.of("flashFloodDamageThreat", List.of("CATASTROPHIC")))
                .instruction("Avoid flood water.")
                .build();
        AlertSafetyPolicy.Decision catastrophicFlood = AlertSafetyPolicy.evaluate(
                catastrophicFlashFlood, dispatch.matchForAlert(catastrophicFlashFlood).orElseThrow());
        assertThat(catastrophicFlood.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);

        NormalizedAlert baseSnowSquall = TestAlerts.nws("Snow Squall Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Execute"))
                .instruction("Act now.")
                .build();
        AlertSafetyPolicy.Decision baseSquall = AlertSafetyPolicy.evaluate(
                baseSnowSquall, dispatch.matchForAlert(baseSnowSquall).orElseThrow());
        assertThat(baseSquall.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.ATTENTION);

        NormalizedAlert significantSnowSquall = TestAlerts.nws("Snow Squall Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Execute"))
                .parameters(Map.of("snowSquallImpact", List.of("SIGNIFICANT")))
                .instruction("Act now.")
                .build();
        AlertSafetyPolicy.Decision significantSquall = AlertSafetyPolicy.evaluate(
                significantSnowSquall, dispatch.matchForAlert(significantSnowSquall).orElseThrow());
        assertThat(significantSquall.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);

        NormalizedAlert weaSnowSquall = TestAlerts.nws("Snow Squall Warning")
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Execute"))
                .parameters(Map.of("WEAHandling", List.of("WEA")))
                .instruction("Act now.")
                .build();
        AlertSafetyPolicy.Decision weaSquall = AlertSafetyPolicy.evaluate(
                weaSnowSquall, dispatch.matchForAlert(weaSnowSquall).orElseThrow());
        assertThat(weaSquall.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.CRITICAL_PUSH);

        NormalizedAlert floodWarningWithoutImpactField = TestAlerts.nws("Flood Warning")
                .severity("Extreme")
                .urgency("Immediate")
                .certainty("Observed")
                .responseTypes(List.of("Avoid"))
                .parameters(Map.of("NWSheadline", List.of("FLOOD WARNING NOW IN EFFECT")))
                .instruction("Avoid flood water.")
                .build();
        AlertSafetyPolicy.Decision floodWarning = AlertSafetyPolicy.evaluate(
                floodWarningWithoutImpactField, dispatch.matchForAlert(floodWarningWithoutImpactField).orElseThrow());
        assertThat(floodWarning.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.ATTENTION);
    }

    @Test
    void lifecycleBlocksNonPublicAndCancelledMessages() {
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(
                TestAlerts.nws("Flood Warning").scope("Restricted").build()))
                .isEqualTo("cap_scope_Restricted");
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(
                TestAlerts.nws("Flood Warning").status("Test").build()))
                .isEqualTo("cap_status_Test");
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(
                TestAlerts.nws("Flood Warning").status("Exercise").build()))
                .isEqualTo("cap_status_Exercise");
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(
                TestAlerts.nws("Flood Warning").messageType("Cancel").build()))
                .isEqualTo("cap_message_type_Cancel");
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(
                TestAlerts.nws("Flood Warning").responseTypes(List.of("Avoid", "AllClear")).build()))
                .isEqualTo("cap_response_all_clear");
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(
                TestAlerts.nws("Flood Warning").endsAt("2026-01-01T00:00:00Z").build()))
                .isEqualTo("alert_expired");
    }

    @Test
    void actionsNormalizeKnownAndUnknownResponseTypes() {
        Set<AlertSafetyPolicy.ProtectiveAction> actions = AlertSafetyPolicy.actionsFromAlert(
                TestAlerts.nws("Flood Warning")
                        .responseTypes(List.of("Evacuate", "Monitor", "Mystery"))
                        .build());

        assertThat(actions).contains(
                AlertSafetyPolicy.ProtectiveAction.EVACUATE,
                AlertSafetyPolicy.ProtectiveAction.MONITOR,
                AlertSafetyPolicy.ProtectiveAction.UNKNOWN);
    }

    @Test
    void evacuateCannotResolveToShelterGuidance() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "critical_push",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Shelter In Place Warning")
                .responseTypes(List.of("Evacuate"))
                .instruction("Leave the area now.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.compatibility())
                .isEqualTo(AlertSafetyPolicy.Compatibility.INCOMPATIBLE);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY);
        assertThat(decision.allowsSitPrepGuidance()).isFalse();
    }

    @Test
    void shelterCannotResolveToEvacuateGuidance() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "EVACUATE",
                List.of("Evacuate"),
                List.of("Shelter", "AllClear"),
                "critical_push",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Evacuation Immediate")
                .responseTypes(List.of("Shelter"))
                .instruction("Stay inside until officials say it is clear.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.compatibility())
                .isEqualTo(AlertSafetyPolicy.Compatibility.INCOMPATIBLE);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY);
        assertThat(decision.allowsSitPrepGuidance()).isFalse();
    }

    @Test
    void allClearCannotRenderActiveGuidance() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "AVOID",
                List.of("Avoid"),
                List.of("AllClear"),
                "attention",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Flood Warning")
                .responseTypes(List.of("AllClear"))
                .instruction("The warning has ended.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.SUPPRESS);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.NO_GUIDANCE);
        assertThat(decision.allowsSitPrepGuidance()).isFalse();
        assertThat(decision.reason()).isEqualTo("cap_response_all_clear");
    }

    @Test
    void unknownResponseFallsBackToOfficialOnlyRatherThanGenericGuidance() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "critical_push",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Shelter In Place Warning")
                .responseTypes(List.of("FutureIssuerAction"))
                .instruction("Follow official instructions.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.capActions()).contains(AlertSafetyPolicy.ProtectiveAction.UNKNOWN);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY);
        assertThat(decision.allowsSitPrepGuidance()).isFalse();
    }

    @Test
    void missingResponseTypeDoesNotCrashOrInventCompatibility() throws Exception {
        AlertDispatchService.DispatchTemplate template = approvedTemplate(
                "SHELTER",
                List.of("Shelter"),
                List.of("Evacuate", "AllClear"),
                "critical_push",
                "supplement_official");
        NormalizedAlert alert = TestAlerts.nws("Shelter In Place Warning")
                .response(null)
                .responseTypes(List.of())
                .instruction("Follow official instructions.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

        assertThat(decision.capActions()).contains(AlertSafetyPolicy.ProtectiveAction.UNKNOWN);
        assertThat(decision.compatibility()).isEqualTo(AlertSafetyPolicy.Compatibility.UNKNOWN);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.OFFICIAL_ONLY);
    }

    @Test
    void eventNameAndSeverityAloneCannotForceCriticalPush() {
        NormalizedAlert warningName = TestAlerts.nws("Imaginary Warning")
                .severity("Extreme")
                .urgency("Immediate")
                .certainty("Observed")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(warningName, null);

        assertThat(decision.dispatchMode()).isEqualTo(AlertSafetyPolicy.DispatchMode.FEED);
        assertThat(decision.guidanceMode()).isEqualTo(AlertSafetyPolicy.GuidanceMode.NO_GUIDANCE);
        assertThat(decision.reason()).isEqualTo("no_template");
    }

    @Test
    void everyTemplateRejectsItsDeclaredIncompatibleResponseTypes() throws Exception {
        int checked = 0;

        try (InputStream in = AlertSafetyPolicyTest.class
                .getResourceAsStream("/templates/alert-dispatch-templates.json")) {
            assertThat(in).isNotNull();
            for (JsonNode node : MAPPER.readTree(in).path("templates")) {
                if (!node.isObject()) continue;
                AlertDispatchService.DispatchTemplate template =
                        AlertDispatchService.DispatchTemplate.fromJson(node);
                if (template.incompatibleResponseTypes == null
                        || template.incompatibleResponseTypes.isEmpty()) {
                    continue;
                }
                checked++;
                for (String responseType : template.incompatibleResponseTypes) {
                    NormalizedAlert alert = TestAlerts.nws(
                                    template.eventAny == null || template.eventAny.isEmpty()
                                            ? "Template Test Warning"
                                            : template.eventAny.get(0))
                            .responseTypes(List.of(responseType))
                            .instruction("Official instruction.")
                            .build();

                    AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, template);

                    assertThat(decision.allowsSitPrepGuidance())
                            .as("%s must reject incompatible responseType %s",
                                    template.headline, responseType)
                            .isFalse();
                    assertThat(decision.compatibility())
                            .as("%s compatibility for %s", template.headline, responseType)
                            .isIn(AlertSafetyPolicy.Compatibility.INCOMPATIBLE);
                }
            }
        }

        assertThat(checked)
                .as("production templates should declare incompatible response types")
                .isGreaterThan(40);
    }

    private static AlertDispatchService.DispatchTemplate approvedTemplate(
            String protectiveAction,
            List<String> compatible,
            List<String> incompatible,
            String dispatchMode,
            String guidanceMode) throws Exception {
        return reviewedTemplate(
                "approved",
                protectiveAction,
                compatible,
                incompatible,
                dispatchMode,
                guidanceMode,
                "https://www.weather.gov/safety");
    }

    private static AlertDispatchService.DispatchTemplate reviewedTemplate(
            String reviewStatus,
            String protectiveAction,
            List<String> compatible,
            List<String> incompatible,
            String dispatchMode,
            String guidanceMode,
            String evidenceUrl) throws Exception {
        String json = """
                {
                  "source": "NWS",
                  "eventAny": ["Policy Test Warning"],
                  "tier": "warning",
                  "hazardType": "test",
                  "headline": "Policy test",
                  "body": "Use the reviewed action.",
                  "steps": ["Use the reviewed action."],
                  "protectiveAction": "%s",
                  "compatibleResponseTypes": %s,
                  "incompatibleResponseTypes": %s,
                  "sitprep": {
                    "dispatchMode": "%s",
                    "guidanceMode": "%s",
                    "movementDirective": "none",
                    "impactAware": false
                  },
                  "evidence": [{
                    "agency": "NOAA / National Weather Service",
                    "title": "Safety guidance",
                    "url": "%s",
                    "checkedAt": "2026-08-27",
                    "supports": ["body", "steps[0]"]
                  }],
                  "safetyReview": {
                    "status": "%s",
                    "version": 1,
                    "sourceVerifiedAt": "2026-08-27",
                    "approvedAt": %s
                  }
                }
                """.formatted(
                protectiveAction,
                MAPPER.writeValueAsString(new ArrayList<>(compatible)),
                MAPPER.writeValueAsString(new ArrayList<>(incompatible)),
                dispatchMode,
                guidanceMode,
                evidenceUrl,
                reviewStatus,
                "approved".equals(reviewStatus) ? "\"2026-08-27\"" : "null");
        return AlertDispatchService.DispatchTemplate.fromJson(MAPPER.readTree(json));
    }

    // =====================================================================
    // RC-1 — movement directive integrity
    //
    // These are DATA invariants, not function tests, and that distinction is
    // the whole reason they exist. The P0 this suite now guards survived a
    // green build because `movementDirectiveFor` was correct and the
    // production template file never handed it a usable input: 20 of 52
    // templates declared a protective action and shipped
    // `movementDirective: "none"`, and CAP `Avoid` was not read at all, so
    // `AVOID_AREA` could not be produced by any production path. Every unit
    // test passed throughout. A policy method is only as safe as the
    // configuration it reads, so the configuration is asserted here too.
    // =====================================================================

    /**
     * Templates that declare a protective action and are DELIBERATELY left with
     * no movement directive, each with the reason.
     *
     * <p>A watch says "be ready", not "do this now". Letting one resolve to a
     * movement directive would allow a watch to demote a household's saved
     * meeting place, which is the inverse of the defect RC-1 fixed.</p>
     *
     * <p>Adding an entry here is a deliberate safety decision and should be
     * argued in review. Adding a protective-action template and forgetting the
     * directive is not — that is what the test below catches.</p>
     */
    private static final Map<String, String> MOVEMENT_DIRECTIVE_EXEMPT = Map.of(
            "Tsunami Advisory", "watch tier — a watch is not a movement instruction");

    private static final Set<AlertSafetyPolicy.ProtectiveAction> MOVEMENT_BEARING_ACTIONS = Set.of(
            AlertSafetyPolicy.ProtectiveAction.EVACUATE,
            AlertSafetyPolicy.ProtectiveAction.SHELTER,
            AlertSafetyPolicy.ProtectiveAction.AVOID);

    /**
     * The directives that are TRUTHFUL for a given protective action.
     *
     * <p>More than one is acceptable because a hazard's sheltering guidance may be
     * a formal shelter-in-place order or graduated indoor-safety advice, and
     * {@code follow_official_instruction} is the honest expression of the second.
     * What is never acceptable is {@code NONE} — that is the state that lets a
     * saved destination stand as the current instruction, which is RC-1.</p>
     */
    private static Set<AlertSafetyPolicy.MovementDirective> truthfulDirectivesFor(
            AlertSafetyPolicy.ProtectiveAction action) {
        return switch (action) {
            case EVACUATE -> Set.of(AlertSafetyPolicy.MovementDirective.EVACUATE);
            case SHELTER -> Set.of(
                    AlertSafetyPolicy.MovementDirective.SHELTER_IN_PLACE,
                    AlertSafetyPolicy.MovementDirective.FOLLOW_OFFICIAL_INSTRUCTION);
            case AVOID -> Set.of(
                    AlertSafetyPolicy.MovementDirective.AVOID_AREA,
                    AlertSafetyPolicy.MovementDirective.FOLLOW_OFFICIAL_INSTRUCTION);
            default -> Set.of();
        };
    }

    private static List<AlertDispatchService.DispatchTemplate> productionTemplates() throws Exception {
        List<AlertDispatchService.DispatchTemplate> out = new ArrayList<>();
        try (InputStream in = AlertSafetyPolicyTest.class
                .getResourceAsStream("/templates/alert-dispatch-templates.json")) {
            assertThat(in).isNotNull();
            for (JsonNode node : MAPPER.readTree(in).path("templates")) {
                if (!node.isObject()) continue;
                out.add(AlertDispatchService.DispatchTemplate.fromJson(node));
            }
        }
        return out;
    }

    private static String templateName(AlertDispatchService.DispatchTemplate t) {
        if (t.eventAny != null && !t.eventAny.isEmpty()) return String.join(" / ", t.eventAny);
        return t.headline == null ? "(unnamed template)" : t.headline;
    }

    private static boolean isExempt(AlertDispatchService.DispatchTemplate t) {
        String name = templateName(t);
        return MOVEMENT_DIRECTIVE_EXEMPT.keySet().stream().anyMatch(name::contains);
    }

    /**
     * INVARIANT 1 — a template that declares evacuation, sheltering or avoidance
     * must declare a movement directive, and it must be the matching one.
     *
     * <p>This is the assertion that would have caught RC-1 on the day it was
     * introduced. It fails when somebody adds a protective-action template and
     * leaves {@code movementDirective} at its default.</p>
     */
    @Test
    void everyProtectiveActionTemplateDeclaresAMatchingMovementDirective() throws Exception {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (AlertDispatchService.DispatchTemplate t : productionTemplates()) {
            if (t.protectiveAction == null
                    || !MOVEMENT_BEARING_ACTIONS.contains(t.protectiveAction)
                    || isExempt(t)) {
                continue;
            }
            checked++;
            String declared = t.sitprep == null ? null : t.sitprep.movementDirective;
            Set<AlertSafetyPolicy.MovementDirective> truthful = truthfulDirectivesFor(t.protectiveAction);
            AlertSafetyPolicy.MovementDirective parsed =
                    AlertSafetyPolicy.MovementDirective.parse(declared, AlertSafetyPolicy.MovementDirective.NONE);
            if (!truthful.contains(parsed)) {
                offenders.add(templateName(t) + " declares protectiveAction " + t.protectiveAction
                        + " but movementDirective \"" + declared + "\" — expected one of " + truthful);
            }
        }

        assertThat(offenders)
                .as("every protective-action template must declare the matching movement directive; "
                        + "add a documented entry to MOVEMENT_DIRECTIVE_EXEMPT only when a template "
                        + "deliberately commands no movement")
                .isEmpty();
        assertThat(checked)
                .as("the production template set should contain protective-action templates")
                .isGreaterThan(15);
    }

    /**
     * INVARIANT 2 — the same claim, asserted through the policy rather than over
     * the file, so a regression in {@code movementDirectiveFor} is caught even if
     * the JSON is still correct.
     */
    @Test
    void noProductionTemplateEvaluatesToNoneForItsOwnProtectiveAction() throws Exception {
        List<String> offenders = new ArrayList<>();

        for (AlertDispatchService.DispatchTemplate t : productionTemplates()) {
            if (t.protectiveAction == null
                    || !MOVEMENT_BEARING_ACTIONS.contains(t.protectiveAction)
                    || isExempt(t)) {
                continue;
            }
            // An alert carrying NO response type, so the template's own declared
            // directive is the only thing that can answer.
            NormalizedAlert alert = TestAlerts.nws(
                            t.eventAny == null || t.eventAny.isEmpty()
                                    ? "Template Test Warning"
                                    : t.eventAny.get(0))
                    .instruction("Official instruction.")
                    .build();

            AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(alert, t);
            if (decision.movementDirective() == AlertSafetyPolicy.MovementDirective.NONE) {
                offenders.add(templateName(t) + " (" + t.protectiveAction + ") resolved to NONE");
            }
        }

        assertThat(offenders)
                .as("a template declaring a protective action must never resolve to NONE — "
                        + "that is the state that let a saved destination stand as the primary action")
                .isEmpty();
    }

    /**
     * INVARIANT 3 — {@code AVOID_AREA} is reachable from production data at all.
     *
     * <p>Before RC-1 it was not: no branch produced it and no template declared
     * it, so the frontend's avoid branch was dead code. A count assertion is
     * crude, and it is exactly the thing that was false.</p>
     */
    @Test
    void avoidAreaIsReachableFromTheProductionTemplateSet() throws Exception {
        long avoidTemplates = productionTemplates().stream()
                .filter(t -> t.sitprep != null)
                .filter(t -> AlertSafetyPolicy.MovementDirective.AVOID_AREA.wire()
                        .equalsIgnoreCase(t.sitprep.movementDirective))
                .count();

        assertThat(avoidTemplates)
                .as("at least one production template must declare avoid_area, "
                        + "or the whole avoid path is unreachable")
                .isGreaterThan(0);
    }

    // ---- CAP response-type mapping ------------------------------------------

    @Test
    void capAvoidResolvesToAvoidArea() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Dust Storm Warning")
                .responseTypes(List.of("Avoid"))
                .instruction("Pull off the road.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert, approvedTemplate("AVOID", List.of("Avoid"), List.of("Evacuate"), null, null));

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.AVOID_AREA);
    }

    /**
     * A reviewed template that classifies its hazard as a formal sheltering order
     * resolves to SHELTER_IN_PLACE — for any event name. The pre-RC-1 code honoured
     * Shelter ONLY for the literal string "Shelter In Place Warning", which is why
     * a tornado warning produced NONE.
     */
    @Test
    void aReviewedShelterTemplateResolvesToShelterInPlaceForAnyEventName() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Tornado Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Get to a basement now.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert,
                templateWithDirective("SHELTER", List.of("Shelter"), List.of("Evacuate"), "shelter_in_place"));

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.SHELTER_IN_PLACE);
    }

    /**
     * An UNREVIEWED shelter alert never becomes a shelter-in-place order — but it
     * never becomes NONE either. See the note on
     * {@code generalIndoorSafetyIsNotAnOfficialShelterInPlaceDirective}.
     */
    @Test
    void unreviewedCapShelterResolvesToFollowOfficialInstruction() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Severe Thunderstorm Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Move indoors away from windows.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert, approvedTemplate("SHELTER", List.of("Shelter"), List.of("Evacuate"), null, null));

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.FOLLOW_OFFICIAL_INSTRUCTION);
    }

    @Test
    void capEvacuateStillResolvesToEvacuate() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Tsunami Warning")
                .responseTypes(List.of("Evacuate"))
                .instruction("Move to high ground.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert, approvedTemplate("EVACUATE", List.of("Evacuate"), List.of("Shelter"), null, null));

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.EVACUATE);
    }

    /**
     * The "do not map blindly" gate. A flash-flood template declares
     * {@code Shelter} incompatible because sheltering in place is the wrong
     * answer to rising water; a stray Shelter response type must not become a
     * shelter instruction.
     */
    @Test
    void capShelterIsNotHonouredWhenTheTemplateDeclaresItIncompatible() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Flash Flood Warning")
                .responseTypes(List.of("Shelter"))
                .instruction("Move to higher ground.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert,
                approvedTemplate("AVOID", List.of("Avoid", "Evacuate"), List.of("Shelter"), null, null));

        assertThat(decision.movementDirective())
                .as("an incompatible response type must not become a movement instruction")
                .isNotEqualTo(AlertSafetyPolicy.MovementDirective.SHELTER_IN_PLACE);
    }

    /**
     * A CAP evacuation order outranks the template's classification — and only
     * evacuation does. For Shelter and Avoid the reviewed template wins, because
     * whether a hazard's guidance is a formal order is a safety-review judgment.
     */
    @Test
    void capEvacuateOutranksTheTemplatesDeclaredDirective() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Evacuation Immediate")
                .responseTypes(List.of("Evacuate"))
                .instruction("Leave now.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert,
                templateWithDirective("SHELTER", List.of("Shelter"), List.of(), "shelter_in_place"));

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.EVACUATE);
    }

    /** An alert with no response type falls through to the template. */
    @Test
    void templateDirectiveAnswersWhenCapCarriesNoResponseType() throws Exception {
        NormalizedAlert alert = TestAlerts.nws("Dust Storm Warning")
                .instruction("Pull off the road.")
                .build();

        AlertSafetyPolicy.Decision decision = AlertSafetyPolicy.evaluate(
                alert,
                templateWithDirective("AVOID", List.of("Avoid"), List.of(), "avoid_area"));

        assertThat(decision.movementDirective())
                .isEqualTo(AlertSafetyPolicy.MovementDirective.AVOID_AREA);
    }


    /**
     * An approved template that DECLARES a movement directive. The shared
     * {@link #reviewedTemplate} helper hardcodes {@code "none"}, which is what the
     * pre-RC-1 production file did too — so a directive-bearing template needs its
     * own builder rather than another parameter on a helper eleven tests share.
     */
    private static AlertDispatchService.DispatchTemplate templateWithDirective(
            String protectiveAction,
            List<String> compatible,
            List<String> incompatible,
            String movementDirective) throws Exception {
        String json = """
                {
                  "source": "NWS",
                  "eventAny": ["Directive Test Warning"],
                  "tier": "warning",
                  "hazardType": "test",
                  "headline": "Directive test",
                  "body": "Use the reviewed action.",
                  "steps": ["Use the reviewed action."],
                  "protectiveAction": "%s",
                  "compatibleResponseTypes": %s,
                  "incompatibleResponseTypes": %s,
                  "sitprep": {
                    "dispatchMode": "critical_push",
                    "guidanceMode": "supplement_official",
                    "movementDirective": "%s",
                    "impactAware": false
                  },
                  "evidence": [{
                    "agency": "NOAA / National Weather Service",
                    "title": "Safety guidance",
                    "url": "https://www.weather.gov/safety",
                    "checkedAt": "2026-08-27",
                    "supports": ["body", "steps[0]"]
                  }],
                  "safetyReview": {
                    "status": "approved",
                    "version": 1,
                    "sourceVerifiedAt": "2026-08-27",
                    "approvedAt": "2026-08-27"
                  }
                }
                """.formatted(
                protectiveAction,
                MAPPER.writeValueAsString(new ArrayList<>(compatible)),
                MAPPER.writeValueAsString(new ArrayList<>(incompatible)),
                movementDirective);
        return AlertDispatchService.DispatchTemplate.fromJson(MAPPER.readTree(json));
    }

}
