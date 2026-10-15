package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertDispatchService.DispatchTemplate;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who wrote a dispatched alert post's body (S3 CM, {@code community.alertBodyAuthor}).
 *
 * <p>The card labels SitPrep's guidance "Interpreted by SitPrep" and the
 * issuer's text as the issuer's. The dispatcher knows which it wrote but never
 * said; the client then had to leave SitPrep's words unlabelled under
 * "Source: National Weather Service". The answer is decided by rebuilding both
 * candidate bodies and comparing — exact or nothing.</p>
 */
class AlertPostBodyAuthorTest {

    private final AlertDispatchService dispatch = dispatcher();

    private static AlertDispatchService dispatcher() {
        AlertDispatchService d = new AlertDispatchService(null, null, null, null, null, null, new NwsZoneService(), null);
        d.loadTemplates();
        return d;
    }

    private final NormalizedAlert warning = TestAlerts.nws("Tornado Warning")
            .id("urn:oid:body-1")
            .response("Shelter")
            .description("At 405 PM MDT, a severe thunderstorm capable of producing a tornado was located near Lehi.")
            .instruction("TAKE COVER NOW! Move to a basement or an interior room on the lowest floor.")
            .build();

    @Test
    void theTemplatesGuidanceIsSitPreps() {
        DispatchTemplate tpl = dispatch.matchForAlert(warning).orElseThrow();
        String body = AlertDispatchService.fitDescription(AlertDispatchService.fillBody(tpl, warning));
        assertThat(AlertDispatchService.bodyAuthorOf(body, warning, tpl)).isEqualTo("sitprep");
    }

    @Test
    void theIssuersOwnTextIsTheIssuers() {
        DispatchTemplate tpl = dispatch.matchForAlert(warning).orElse(null);
        String body = AlertDispatchService.fitDescription(AlertDispatchService.officialBody(warning));
        assertThat(AlertDispatchService.bodyAuthorOf(body, warning, tpl)).isEqualTo("issuer");
        assertThat(AlertDispatchService.bodyAuthorOf(body, warning, null)).isEqualTo("issuer");
    }

    @Test
    void anythingElseIsUnknown_neverAGuess() {
        DispatchTemplate tpl = dispatch.matchForAlert(warning).orElse(null);
        assertThat(AlertDispatchService.bodyAuthorOf("Somebody's own words about the storm.", warning, tpl)).isNull();
        assertThat(AlertDispatchService.bodyAuthorOf(null, warning, tpl)).isNull();
        assertThat(AlertDispatchService.bodyAuthorOf("x", null, tpl)).isNull();
    }
}
