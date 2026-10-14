package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.util.AlertSourceFingerprint;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A FEMA declaration's revision ref through the real poll (S2 contract §1, §10).
 *
 * <p>FEMA sends one row per designated area and orders them only by
 * declarationDate, which every row of one declaration shares. So the order
 * within a declaration, and where a $skip page boundary falls, is not stable.
 * None of that may reach the fingerprint: an unchanged declaration must keep
 * its ref, or the persistence layer writes a revision and a snapshot on a poll
 * where nothing changed. A declaration that gains a county is a change.</p>
 */
class FemaRevisionStabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    private static String row(String fipsCounty, String name, String rowId) {
        return """
            {"femaDeclarationString":"DR-9999-OK","disasterNumber":9999,"state":"OK",
             "declarationType":"DR","declarationDate":"2026-09-01T00:00:00.000Z",
             "incidentBeginDate":"2026-08-20T00:00:00.000Z","incidentEndDate":"2026-08-25T00:00:00.000Z",
             "lastIAFilingDate":"2026-11-01T00:00:00.000Z",
             "incidentType":"Severe Storm","declarationTitle":"SEVERE STORMS",
             "fipsStateCode":"40","fipsCountyCode":"%s",
             "designatedArea":"%s (County)","ihProgramDeclared":true,"id":"%s"}"""
                .formatted(fipsCounty, name, rowId);
    }

    private static String page(List<String> rows) {
        return "{\"DisasterDeclarationsSummaries\":[" + String.join(",", rows) + "]}";
    }

    /** Polls once, answering each FEMA page URL with {@code pages}; the one FEMA alert. */
    private static NormalizedAlert poll(Function<String, String> pages) {
        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        ingest.setUpstreamForTest((url, accept) -> url.contains("fema.gov")
                        ? MAPPER.readTree(pages.apply(url))
                        : MAPPER.readTree("{\"features\":[]}"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        ingest.refreshNow();
        List<NormalizedAlert> fema = ingest.getSnapshot().alerts().stream()
                .filter(a -> "FEMA".equals(a.source())).toList();
        assertThat(fema).hasSize(1);
        return fema.get(0);
    }

    private static NormalizedAlert pollOnePage(List<String> rows) {
        return poll(url -> url.endsWith("$skip=0") ? page(rows) : page(List.of()));
    }

    private static String ref(NormalizedAlert a) {
        return AlertSourceFingerprint.revisionRef(a);
    }

    @Test
    void theSameRowsInTheOtherOrderKeepTheRef() {
        String cleveland = row("027", "Cleveland", "a"), oklahoma = row("109", "Oklahoma", "b");

        NormalizedAlert one = pollOnePage(List.of(cleveland, oklahoma));
        NormalizedAlert two = pollOnePage(List.of(oklahoma, cleveland));

        assertThat(one.area()).isEqualTo("Cleveland (County), Oklahoma (County)");
        assertThat(two.area()).isEqualTo(one.area());
        assertThat(two.same()).isEqualTo(one.same());
        assertThat(ref(two)).isEqualTo(ref(one));
    }

    /**
     * 501 counties: the area string is cut at 240 chars, and the rows fill a
     * whole page and spill onto a second. Reversed, or split at a different
     * row, the declaration reads the same.
     */
    @Test
    void reorderedOrSplitAcrossPages_aTruncatedDeclarationKeepsTheRef() {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            rows.add(row(String.format("%03d", i), "County" + String.format("%03d", i), "id" + i));
        }
        List<String> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);

        // One full page in arrival order, the 501st on the next.
        NormalizedAlert inOrder = poll(url -> url.endsWith("$skip=0") ? page(rows.subList(0, 500))
                : url.endsWith("$skip=500") ? page(rows.subList(500, 501)) : page(List.of()));
        // Reversed: the first page now holds the other 500.
        NormalizedAlert backwards = poll(url -> url.endsWith("$skip=0") ? page(reversed.subList(0, 500))
                : url.endsWith("$skip=500") ? page(reversed.subList(500, 501)) : page(List.of()));

        assertThat(inOrder.area()).hasSize(238).endsWith("…").startsWith("County001 (County), County002");
        assertThat(inOrder.same()).hasSize(501);
        assertThat(backwards.area()).isEqualTo(inOrder.area());
        assertThat(ref(backwards)).isEqualTo(ref(inOrder));
    }

    @Test
    void aDeclarationThatGainsACountyIsANewRevision() {
        String cleveland = row("027", "Cleveland", "a"), oklahoma = row("109", "Oklahoma", "b");

        NormalizedAlert before = pollOnePage(List.of(cleveland));
        NormalizedAlert after = pollOnePage(List.of(oklahoma, cleveland));

        assertThat(ref(after)).isNotEqualTo(ref(before));
        assertThat(after.id()).isEqualTo(before.id());
    }

    /** Reading the captured page backwards changes nothing about any declaration. */
    @Test
    void theCapturedPageReadBackwardsKeepsEveryRef() throws Exception {
        List<String> rows = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/fema-declarations-2026-08-22.json")) {
            MAPPER.readTree(in).path("DisasterDeclarationsSummaries").forEach(n -> rows.add(n.toString()));
        }
        Instant captured = Instant.parse("2026-08-22T12:00:00Z");
        List<String> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);

        assertThat(refsOf(rows, captured)).isNotEmpty().isEqualTo(refsOf(reversed, captured));
    }

    private static List<String> refsOf(List<String> rows, Instant at) {
        AlertIngestService ingest = new AlertIngestService(new NwsZoneService());
        ingest.setUpstreamForTest((url, accept) -> url.contains("fema.gov")
                        ? MAPPER.readTree(url.endsWith("$skip=0") ? page(rows) : page(List.of()))
                        : MAPPER.readTree("{\"features\":[]}"),
                Clock.fixed(at, ZoneOffset.UTC));
        ingest.refreshNow();
        return ingest.getSnapshot().alerts().stream()
                .filter(a -> "FEMA".equals(a.source()))
                .map(AlertSourceFingerprint::revisionRef)
                .sorted()
                .toList();
    }

    /** Pages are stable only with a tie-breaker inside one declarationDate. */
    @Test
    void theQueryBreaksDeclarationDateTiesById() {
        assertThat(AlertIngestService.femaPageUrl(0)).contains("$orderby=declarationDate%20desc,id&");
    }
}
