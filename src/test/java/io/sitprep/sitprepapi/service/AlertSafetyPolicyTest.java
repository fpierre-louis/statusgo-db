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
    void unapprovedTemplateSuppressesSitPrepGuidanceButCanStayCritical() {
        AlertDispatchService dispatch =
                new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
        NormalizedAlert alert = TestAlerts.nws("Evacuation Immediate")
                .responseTypes(List.of("Evacuate"))
                .instruction("Move to higher ground if officials tell you to.")
                .build();

        AlertSafetyPolicy.Decision decision =
                AlertSafetyPolicy.evaluate(alert, dispatch.matchForAlert(alert).orElseThrow());

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

        assertThat(decision.movementDirective()).isEqualTo(AlertSafetyPolicy.MovementDirective.NONE);
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
}
