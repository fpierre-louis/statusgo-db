package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEMA active-recovery guards (audit P1-2).
 *
 * <h2>What was wrong</h2>
 *
 * <p>The ingest query was {@code $filter=incidentEndDate eq null}, described in
 * the code as "currently-active" declarations. Measured on 2026-08-22 it
 * returned <b>628 rows / 299 disasters, 211 of them declared 3+ years ago</b>,
 * the oldest a Kentucky fire complex from <b>November 2000</b> — each stamped
 * {@code severity: "Severe"} and therefore passing CrisisBand's Severe+Extreme
 * gate on a calm day.</p>
 *
 * <h2>The base query, not just the filter</h2>
 *
 * <p>Adding an active-recovery filter on top would have returned <b>nothing</b>.
 * Verified live:</p>
 *
 * <pre>
 *   $filter=incidentEndDate eq null
 *           and (iaProgramDeclared eq true or ihProgramDeclared eq true)
 *   -> count: 0
 * </pre>
 *
 * <p>The two conditions are mutually exclusive: FEMA leaves
 * {@code incidentEndDate} null mostly on Fire Management Assistance grants,
 * which reimburse a state and offer a household nothing, while the major
 * declarations that carry Individual Assistance get an end date. So the query
 * was structurally incapable of returning a row anyone could act on.</p>
 */
class FemaActiveRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode rows;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = FemaActiveRecoveryTest.class
                .getResourceAsStream("/fixtures/fema-declarations-2026-08-22.json")) {
            assertThat(in).as("FEMA fixture present").isNotNull();
            rows = MAPPER.readTree(in).path("DisasterDeclarationsSummaries");
        }
    }

    private static JsonNode row(String decl) {
        for (JsonNode r : rows) {
            if (decl.equals(r.path("femaDeclarationString").asText())) return r;
        }
        throw new AssertionError("fixture lost " + decl);
    }

    // ==================================================================
    // The query
    // ==================================================================

    @Test
    void theQueryFiltersOnRecencyAndAssistance_notOnIncidentEndDate() {
        String url = AlertIngestService.femaPageUrl(0);

        assertThat(url)
                .as("the old filter could never return an actionable row")
                .doesNotContain("incidentEndDate");
        assertThat(url).contains("declarationDate%20ge");
        assertThat(url).contains("iaProgramDeclared%20eq%20true");
        assertThat(url).contains("ihProgramDeclared%20eq%20true");
    }

    @Test
    void theQueryPaginates() {
        assertThat(AlertIngestService.femaPageUrl(0)).endsWith("$skip=0");
        assertThat(AlertIngestService.femaPageUrl(1)).endsWith("$skip=500");
        assertThat(AlertIngestService.femaPageUrl(2)).endsWith("$skip=1000");
    }

    // ==================================================================
    // The filter
    // ==================================================================

    @Test
    void aRecentDeclarationWithAnOpenFilingWindowIsActive() {
        // DR-4932-WV — West Virginia flooding, declared 2026-08-03, IA filing
        // open until 2026-10-03.
        assertThat(AlertIngestService.isActiveRecovery(row("DR-4932-WV"))).isTrue();
    }

    @Test
    void everyDeclarationTheFilterKeepsOffersIndividualAssistance() {
        Set<String> kept = new LinkedHashSet<>();
        for (JsonNode r : rows) {
            if (!AlertIngestService.isActiveRecovery(r)) continue;
            kept.add(r.path("femaDeclarationString").asText());
            assertThat(r.path("iaProgramDeclared").asBoolean(false)
                    || r.path("ihProgramDeclared").asBoolean(false))
                    .as("%s kept but offers no individual help",
                            r.path("femaDeclarationString").asText())
                    .isTrue();
        }
        assertThat(kept).as("active declarations in the fixture").isNotEmpty();
    }

    @Test
    void aClosedOutDeclarationIsNotActive() {
        assertThat(AlertIngestService.isActiveRecovery(
                MAPPER.createObjectNode()
                        .put("iaProgramDeclared", true)
                        .put("declarationDate", "2026-08-01T00:00:00.000Z")
                        .put("disasterCloseoutDate", "2026-08-10T00:00:00.000Z")))
                .isFalse();
    }

    @Test
    void anExpiredFilingWindowIsNotActive() {
        assertThat(AlertIngestService.isActiveRecovery(
                MAPPER.createObjectNode()
                        .put("iaProgramDeclared", true)
                        .put("declarationDate", "2026-08-01T00:00:00.000Z")
                        .put("lastIAFilingDate", "2026-08-10T00:00:00.000Z")))
                .isFalse();
    }

    @Test
    void theKentuckyFireComplexFrom2000IsNotActive() {
        // The oldest row the old query served to every user in the country.
        assertThat(AlertIngestService.isActiveRecovery(
                MAPPER.createObjectNode()
                        .put("femaDeclarationString", "FM-2350-KY")
                        .put("iaProgramDeclared", false)
                        .put("ihProgramDeclared", false)
                        .put("declarationDate", "2000-11-03T00:00:00.000Z")))
                .isFalse();
    }

    @Test
    void aFireManagementGrantIsNotActive_evenWhenRecent() {
        // 296 of the 299 old "active" declarations were these. They reimburse a
        // state for firefighting costs; a household can do nothing with one.
        assertThat(AlertIngestService.isActiveRecovery(
                MAPPER.createObjectNode()
                        .put("femaDeclarationString", "FM-5673-AR")
                        .put("incidentType", "Fire")
                        .put("iaProgramDeclared", false)
                        .put("ihProgramDeclared", false)
                        .put("declarationDate", "2026-08-21T00:00:00.000Z")))
                .isFalse();
    }

    @Test
    void degenerateRowsAreNotActiveRatherThanThrowing() {
        assertThat(AlertIngestService.isActiveRecovery(null)).isFalse();
        assertThat(AlertIngestService.isActiveRecovery(MAPPER.createObjectNode())).isFalse();
        assertThat(AlertIngestService.isActiveRecovery(
                MAPPER.createObjectNode().put("iaProgramDeclared", true))) // no date
                .isFalse();
        assertThat(AlertIngestService.isActiveRecovery(
                MAPPER.createObjectNode()
                        .put("iaProgramDeclared", true)
                        .put("declarationDate", "not a date")))
                .isFalse();
    }

    @Test
    void theFilterIsSelective_mostFixtureRowsDoNotSurvive() {
        long active = 0;
        for (JsonNode r : rows) if (AlertIngestService.isActiveRecovery(r)) active++;
        assertThat(active).isLessThan(rows.size());
        assertThat(active).as("but it does not reject everything").isGreaterThan(0);
    }
}
