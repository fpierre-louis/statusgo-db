package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Practice redesign BE-04: the read side of the skill registry. */
class PracticeSkillServiceTest {

    private static final PracticeCatalog SHIPPED = new PracticeCatalog(PracticeCatalog.DEFAULT_LOCATION);

    private PracticeSkillService service(List<Entry> startable) {
        PracticeAvailabilityService availability = mock(PracticeAvailabilityService.class);
        when(availability.startable(PracticeKind.SKILL)).thenReturn(startable);
        return new PracticeSkillService(availability, SHIPPED);
    }

    @Test
    void productionServesNoSkillUntilOneIsPublished() {
        // The availability service only offers DRAFTs under the local preview gate.
        assertThat(service(List.of()).list(null).skills()).isEmpty();
    }

    @Test
    void underPreviewTheDraftsAreServedAsReviewedCopyAndFilterByHazard() {
        List<Entry> drafts = SHIPPED.keys(PracticeKind.SKILL).stream()
                .map(k -> new Entry(SHIPPED.versions(k).get(0), true)).toList();
        var tornado = service(drafts).list("tornado").skills();
        assertThat(tornado).extracting(PracticeSkillService.SkillDto::key)
                .containsExactly("tornado-after-the-warning", "tornado-know-where-to-go", "tornado-protect-yourselves");
        var where = tornado.get(1);
        assertThat(where.preview()).isTrue();
        assertThat(where.title()).isEqualTo("Know where to go");
        assertThat(where.physicalRehearsal()).isEqualTo("REQUIRED");
        assertThat(where.drills()).containsExactly("tornado-safe-room-speed-round");
        assertThat(where.scenarioNodes()).contains("tornado-warning-shelter-decision#warning_issued");
        assertThat(where.ifThen().routine()).isEqualTo("tornado-night-routine");
        assertThat(where.sources()).isNotEmpty();
    }
}
