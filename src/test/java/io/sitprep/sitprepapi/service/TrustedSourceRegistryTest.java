package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.TrustedSourceRegistry.SourceIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trusted-source table and the record type (S2 contract §2).
 *
 * <p>Official identity comes from the adapter's source key and a reviewed
 * table — never from what the payload calls itself.</p>
 */
class TrustedSourceRegistryTest {

    private static AlertDispatchService dispatch;

    @BeforeAll
    static void setUp() {
        dispatch = new AlertDispatchService(null, null, null, null, null, null, new NwsZoneService(), null);
        dispatch.loadTemplates();
    }

    @Test
    void theThreeVerifiedSourcesAreOfficial_withNoLogoYet() {
        assertThat(TrustedSourceRegistry.of("NWS"))
                .isEqualTo(new SourceIdentity("NWS", "National Weather Service", true, null));
        assertThat(TrustedSourceRegistry.of("usgs"))
                .isEqualTo(new SourceIdentity("USGS", "U.S. Geological Survey", true, null));
        assertThat(TrustedSourceRegistry.of("FEMA"))
                .isEqualTo(new SourceIdentity("FEMA", "FEMA", true, null));
    }

    @Test
    void anUnknownSourceIsNotOfficial_andGetsNoName() {
        assertThat(TrustedSourceRegistry.of("Totally National Weather Service"))
                .isEqualTo(new SourceIdentity("TOTALLY NATIONAL WEATHER SERVICE", null, false, null));
        assertThat(TrustedSourceRegistry.of(null)).isEqualTo(new SourceIdentity(null, null, false, null));
    }

    @Test
    void identityIgnoresWhatThePayloadSaysAboutItself() {
        // A sender string claiming NWS does not make a non-NWS source official:
        // the registry is keyed on the adapter's source only.
        var a = TestAlerts.nws("Flood Warning").source("PARTNER").sender("w-nws.webmaster@noaa.gov")
                .senderName("NWS Salt Lake City UT").build();
        assertThat(TrustedSourceRegistry.of(a.source()).official()).isFalse();
    }

    @Test
    void usgsAndFemaHaveFixedRecordTypes() {
        assertThat(TrustedSourceRegistry.recordTypeOf(TestAlerts.usgs("M6.4 — x").build(), null))
                .isEqualTo("earthquake_information");
        // Even with a FEMA template matched (tier "watch").
        var fema = TestAlerts.fema("Hurricane — HURRICANE X").build();
        assertThat(TrustedSourceRegistry.recordTypeOf(fema, dispatch.matchForAlert(fema).orElse(null)))
                .isEqualTo("disaster_declaration");
    }

    @Test
    void nwsRecordTypeIsTheTemplateTierWhenOneCoversTheProduct() {
        // Template tier, not the product name: Evacuation Immediate and Flood
        // Statement are warning-tier by review; Freeze Warning is advisory-tier (R1,
        // 2026-10-15: its own template, split from Freeze Watch).
        for (String[] c : new String[][] {
                { "Evacuation Immediate", "warning" },
                { "Flood Statement", "warning" },
                { "Freeze Warning", "advisory" },
                { "Flood Warning", "warning" },
                { "Flood Watch", "watch" } }) {
            var a = TestAlerts.nws(c[0]).build();
            var tpl = dispatch.matchForAlert(a).orElseThrow();
            assertThat(TrustedSourceRegistry.recordTypeOf(a, tpl)).as(c[0]).isEqualTo(c[1]);
        }
    }

    @Test
    void nwsWithoutATemplateFallsBackToTheProductSuffix() {
        assertThat(TrustedSourceRegistry.recordTypeOf(TestAlerts.nws("Wind Advisory").build(), null))
                .isEqualTo("advisory");
        assertThat(TrustedSourceRegistry.recordTypeOf(TestAlerts.nws("Special Weather Statement").build(), null))
                .isEqualTo("statement");
        assertThat(TrustedSourceRegistry.recordTypeOf(TestAlerts.nws("Gale Warning").build(), null))
                .isEqualTo("warning");
        assertThat(TrustedSourceRegistry.recordTypeOf(TestAlerts.nws("Hydrologic Outlook").build(), null))
                .isEqualTo("information");
        assertThat(TrustedSourceRegistry.recordTypeOf(TestAlerts.nws("x").event(null).build(), null))
                .isEqualTo("information");
    }
}
