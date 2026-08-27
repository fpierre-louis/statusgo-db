package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;

import java.util.List;

/**
 * One place tests build a {@link NormalizedAlert}.
 *
 * <p><b>Why this exists.</b> {@code NormalizedAlert} is a record with 17
 * positional components, and the alert epic added five of them in three
 * sittings (P0-2 added {@code event}; P0-3 added {@code urgency},
 * {@code certainty}, {@code messageType}, {@code status}, {@code response};
 * P0-4 added {@code ugc} and {@code same}; the CAP-references pass added {@code references}). Each addition broke every ad-hoc
 * constructor call in the test tree, which is churn that teaches nothing.</p>
 *
 * <p>Production builds this record in exactly three places — the three
 * normalizers — so positional construction is fine there. Tests build it
 * constantly, so they get a builder. The next field addition touches this
 * file and nothing else.</p>
 */
final class TestAlerts {

    private TestAlerts() {}

    /** A live NWS warning: Immediate / Observed / Actual, no polygon, zone AZZ560. */
    static Builder nws(String event) {
        return new Builder()
                .id("id-" + event)
                .source("NWS")
                .event(event)
                .severity("Severe")
                .urgency("Immediate")
                .certainty("Observed")
                .messageType("Alert")
                .status("Actual")
                .response("Avoid")
                .headline(event + " issued for somewhere")
                .description("Body.")
                .area("Area")
                .ugc(List.of("AZZ560"))
                .same(List.of("004007"));
    }

    /** The CAP shape NWS actually ships Watch products in. */
    static Builder nwsWatch(String event) {
        return nws(event).urgency("Future").certainty("Possible").response("Prepare");
    }

    static Builder usgs(String headline) {
        return new Builder().id("q1").source("USGS").severity("Severe").headline(headline);
    }

    static Builder fema(String headline) {
        return new Builder().id("FM-1").source("FEMA").severity("Severe").headline(headline);
    }

    static final class Builder {
        private String id;
        private String source;
        private String event;
        private String severity;
        private String urgency;
        private String certainty;
        private String messageType;
        private String status;
        private String response;
        private String headline;
        private String description;
        private String instruction;
        private String area;
        private String startedAt;
        private String endsAt;
        private Object geometry;
        private List<String> ugc = List.of();
        private List<String> same = List.of();
        private List<String> references = List.of();

        Builder id(String v) { this.id = v; return this; }
        Builder source(String v) { this.source = v; return this; }
        Builder event(String v) { this.event = v; return this; }
        Builder severity(String v) { this.severity = v; return this; }
        Builder urgency(String v) { this.urgency = v; return this; }
        Builder certainty(String v) { this.certainty = v; return this; }
        Builder messageType(String v) { this.messageType = v; return this; }
        Builder status(String v) { this.status = v; return this; }
        Builder response(String v) { this.response = v; return this; }
        Builder headline(String v) { this.headline = v; return this; }
        Builder description(String v) { this.description = v; return this; }
        Builder instruction(String v) { this.instruction = v; return this; }
        Builder area(String v) { this.area = v; return this; }
        Builder startedAt(String v) { this.startedAt = v; return this; }
        Builder endsAt(String v) { this.endsAt = v; return this; }
        Builder geometry(Object v) { this.geometry = v; return this; }
        Builder ugc(List<String> v) { this.ugc = v; return this; }
        Builder same(List<String> v) { this.same = v; return this; }
        /** CAP `references` — the identifiers of the alerts this one replaces. */
        Builder references(List<String> v) { this.references = v; return this; }

        NormalizedAlert build() {
            return new NormalizedAlert(
                    id, source, event, severity, urgency, certainty, messageType,
                    status, response, headline, description, instruction, area,
                    startedAt, endsAt, geometry, ugc, same, references);
        }
    }
}
