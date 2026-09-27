package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.dto.ResourceListingDto;
import io.sitprep.sitprepapi.dto.SubmitResourceRequest;
import io.sitprep.sitprepapi.repo.ResourceListingRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BE-6: hours on the resource board — 400 on malformed, submitter-only edits, null without hours. */
class ResourceListingHoursTest {

    private static final Map<String, Object> WEEKDAYS = Map.of(
            "tz", "America/Denver",
            "weekly", Map.of("mon", List.of(List.of("09:00", "21:00"))));

    private ResourceListingRepo repo;
    private ResourceListingService service;
    private ResourceListing row;

    @BeforeEach
    void setUp() {
        repo = mock(ResourceListingRepo.class);
        service = new ResourceListingService(repo, null);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        row = new ResourceListing();
        row.setId(9L);
        row.setTitle("Cooling center");
        row.setSubmittedByEmail("ann@x.com");
        when(repo.findById(9L)).thenReturn(Optional.of(row));
    }

    private static SubmitResourceRequest submit(Object hours) {
        return new SubmitResourceRequest("Cooling center", null, "cooling-center", null, null,
                40.39, -111.85, hours);
    }

    private static HttpStatus status(Throwable t) {
        return HttpStatus.valueOf(((ResponseStatusException) t).getStatusCode().value());
    }

    @Test
    void submitStoresValidHoursAndRefusesMalformedOnes() {
        ResourceListingDto dto = service.submit(submit(WEEKDAYS), "ann@x.com");
        assertThat(dto.hours()).containsEntry("tz", "America/Denver");
        assertThat(dto.openNow()).isNotNull();

        assertThatThrownBy(() -> service.submit(submit(Map.of("tz", "Nowhere/Land", "weekly", Map.of())), "ann@x.com"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void noHoursMeansNoOpenState() {
        ResourceListingDto dto = service.submit(submit(null), "ann@x.com");
        assertThat(dto.hours()).isNull();
        assertThat(dto.openNow()).as("never assumed open").isNull();
        assertThat(dto.closesAt()).isNull();
        assertThat(dto.opensAt()).isNull();
    }

    @Test
    void theReadIsComputedAtReadTime() {
        row.setHoursJson(new HashMap<>(WEEKDAYS));
        // Mon 2026-09-28 12:00 MDT
        ResourceListingDto open = service.toDto(row, null, Instant.parse("2026-09-28T18:00:00Z"));
        assertThat(open.openNow()).isTrue();
        assertThat(open.closesAt()).isEqualTo(Instant.parse("2026-09-29T03:00:00Z"));
        // Tue — closed, next opening next Monday
        ResourceListingDto closed = service.toDto(row, null, Instant.parse("2026-09-29T18:00:00Z"));
        assertThat(closed.openNow()).isFalse();
        assertThat(closed.opensAt()).isEqualTo(Instant.parse("2026-10-05T15:00:00Z"));
    }

    @Test
    void aStoredScheduleThatNoLongerParsesReportsNothing() {
        row.setHoursJson(new HashMap<>(Map.of("tz", "Not/AZone", "weekly", Map.of())));
        ResourceListingDto dto = service.toDto(row, null, Instant.now());
        assertThat(dto.hours()).isNull();
        assertThat(dto.openNow()).isNull();
    }

    @Test
    void onlyTheSubmitterMayChangeHours() {
        assertThatThrownBy(() -> service.updateHours(9L, Map.of("hours", WEEKDAYS), "bob@x.com"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.FORBIDDEN));

        row.setSubmittedByEmail(null); // OFFICIAL / imported rows
        assertThatThrownBy(() -> service.updateHours(9L, Map.of("hours", WEEKDAYS), "ann@x.com"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.FORBIDDEN));

        when(repo.findById(10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateHours(10L, Map.of("hours", WEEKDAYS), "ann@x.com"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void theSubmitterSetsAndClearsHours() {
        assertThat(service.updateHours(9L, Map.of("hours", WEEKDAYS), "ANN@x.com").hours()).isNotNull();

        Map<String, Object> clear = new HashMap<>();
        clear.put("hours", null);
        assertThat(service.updateHours(9L, clear, "ann@x.com").hours()).isNull();
        assertThat(row.getHoursJson()).isNull();

        assertThatThrownBy(() -> service.updateHours(9L, Map.of("title", "x"), "ann@x.com"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.updateHours(9L, Map.of("hours", "9-5"), "ann@x.com"))
                .satisfies(t -> assertThat(status(t)).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
