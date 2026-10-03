package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.AlertPostRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/** V89 — a dispatched alert's own area rides its post, as real GeoJSON. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AlertAreaTest {

    @Autowired PostService postService;
    @Autowired AlertPostRepo alertPostRepo;
    @Autowired ObjectMapper objectMapper;
    @Autowired AlertDispatchService dispatchService;
    @Autowired HazardService hazardService;
    @MockBean NominatimGeocodeService geocode;

    private static final Map<String, Object> POLYGON = Map.of(
            "type", "Polygon",
            "coordinates", List.of(List.of(
                    List.of(-111.9, 40.7), List.of(-111.8, 40.7),
                    List.of(-111.8, 40.8), List.of(-111.9, 40.7))));

    @Test
    void aHazardPostCarriesItsMapFacts() throws Exception {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
        var dto = hazardService.report(new HazardService.ReportRequest(
                "flood", 40.39, -111.85, "Water over the road", List.of(), 40.3901, -111.8501, null),
                "hazard-reporter-" + System.nanoTime() + "@example.com");
        PostDto read = postService.findDtoById(dto.id(), "viewer@example.com").orElseThrow();
        JsonNode h = objectMapper.readTree(objectMapper.writeValueAsString(read)).path("community").path("hazard");
        assertThat(h.path("category").asText()).isEqualTo("flood");
        assertThat(h.path("state").asText()).isEqualTo("reported");
        assertThat(h.path("radiusM").asInt()).isEqualTo(dto.radiusM());
    }

    @Test
    void onlyAPolygonIsKeptAsAnArea() {
        assertThat(AlertDispatchService.areaGeojsonOf(POLYGON)).contains("\"Polygon\"");
        assertThat(AlertDispatchService.areaGeojsonOf(Map.of("type", "Point", "coordinates", List.of(1, 2)))).isNull();
        assertThat(AlertDispatchService.areaGeojsonOf(null)).isNull();
    }

    @Test
    void aPostDispatchedBeforeV89GetsItsAreaOnTheNextTickButAResolvedOneDoesNot() {
        Object live = POLYGON;

        AlertPost old = new AlertPost();
        old.setAlertId("nws-backfill-1");
        old.setGeocellId("841");
        old.setPostId(424242L);
        old = alertPostRepo.save(old);
        dispatchService.backfillArea(old, live);
        assertThat(alertPostRepo.findById(old.getId()).orElseThrow().getAreaGeojson()).contains("Polygon");

        AlertPost resolved = new AlertPost();
        resolved.setAlertId("nws-backfill-2");
        resolved.setGeocellId("841");
        resolved.setPostId(424243L);
        resolved.setResolvedAt(java.time.Instant.now());
        resolved = alertPostRepo.save(resolved);
        dispatchService.backfillArea(resolved, live);
        assertThat(alertPostRepo.findById(resolved.getId()).orElseThrow().getAreaGeojson()).isNull();
    }

    @Test
    void anAlertPostCarriesItsAreaAsJsonAndOtherPostsDoNot() throws Exception {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
        Post alert = new Post();
        alert.setKind("alert-update");
        alert.setTitle("Flash flood warning");
        alert.setDescription("Move to higher ground.");
        alert.setLatitude(40.75);
        alert.setLongitude(-111.85);
        PostDto created = postService.create(alert, "system@sitprep.app");

        AlertPost ap = new AlertPost();
        ap.setAlertId("test-alert-" + created.id());
        ap.setGeocellId("841");
        ap.setPostId(created.id());
        ap.setAreaGeojson(AlertDispatchService.areaGeojsonOf(POLYGON));
        alertPostRepo.save(ap);

        PostDto read = postService.findDtoById(created.id(), "viewer@example.com").orElseThrow();
        JsonNode area = objectMapper.readTree(objectMapper.writeValueAsString(read)).path("community").path("area");
        assertThat(area.path("type").asText()).isEqualTo("Polygon");
        assertThat(area.path("coordinates").get(0).size()).isEqualTo(4);

        Post plain = new Post();
        plain.setKind("post");
        plain.setDescription("Not an alert");
        PostDto other = postService.create(plain, "someone@example.com");
        JsonNode none = objectMapper.readTree(objectMapper.writeValueAsString(other)).path("community");
        assertThat(none.has("area")).isFalse();
    }
}
