package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.RunContent;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.StartDenial;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The three switch layers and the start-vs-continue split. */
class PracticeAvailabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");

    private PracticeContentControlRepo repo;
    private Version live;
    private Version retired;
    private Version draft;
    private PracticeCatalog catalog;

    @BeforeEach
    void setUp() {
        repo = mock(PracticeContentControlRepo.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        live = version(approve(scenario("live", 1), PublishState.PUBLISHED));
        retired = version(approve(scenario("old", 1), PublishState.RETIRED));
        draft = version(scenario("wip", 1));
        catalog = PracticeCatalog.of(List.of(live, retired, draft));
    }

    private PracticeAvailabilityService service(boolean enabled, boolean preview) {
        return new PracticeAvailabilityService(catalog, repo, enabled, preview, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void disable(String key) {
        PracticeContentControl c = new PracticeContentControl();
        c.setContentKey(key);
        c.setDisabledAt(NOW);
        c.setDisabledReason("unsafe wording");
        c.setUpdatedAt(NOW);
        when(repo.findById(key)).thenReturn(Optional.of(c));
        when(repo.findAll()).thenReturn(List.of(c));
    }

    @Test
    void productionCatalogIsPublishedOnly() {
        assertThat(service(true, false).startable(PracticeKind.ADULT_SCENARIO))
                .extracting(e -> e.version().key()).containsExactly("live");
    }

    @Test
    void previewAddsDraftsFlaggedAsPreview() {
        var entries = service(true, true).startable(PracticeKind.ADULT_SCENARIO);
        assertThat(entries).extracting(e -> e.version().key()).containsExactly("live", "wip");
        assertThat(entries).filteredOn(e -> e.version().key().equals("wip")).singleElement()
                .satisfies(e -> assertThat(e.preview()).isTrue());
        assertThat(entries).filteredOn(e -> e.version().key().equals("live")).singleElement()
                .satisfies(e -> assertThat(e.preview()).isFalse());
    }

    @Test
    void globalSwitchOffEmptiesEverything() {
        PracticeAvailabilityService off = service(false, true);
        assertThat(off.startable(PracticeKind.ADULT_SCENARIO)).isEmpty();
        assertThat(off.checkStart("live").denial()).isEqualTo(StartDenial.PRACTICE_OFF);
        assertThat(off.checkRun("live", 1, live.contentHash()).status()).isEqualTo(RunContent.DISABLED);
    }

    @Test
    void killSwitchStopsStartsAndRunsAtOnce() {
        disable("live");
        PracticeAvailabilityService s = service(true, false);
        assertThat(s.startable(PracticeKind.ADULT_SCENARIO)).isEmpty();
        assertThat(s.checkStart("live").denial()).isEqualTo(StartDenial.DISABLED);
        var run = s.checkRun("live", 1, live.contentHash());
        assertThat(run.status()).isEqualTo(RunContent.DISABLED);
        assertThat(run.version()).isNull(); // no unsafe text leaks through the run view
        assertThat(run.canContinue()).isFalse();
    }

    @Test
    void retiredFinishesButCannotStart() {
        PracticeAvailabilityService s = service(true, true);
        assertThat(s.checkStart("old").denial()).isEqualTo(StartDenial.NOT_PUBLISHED);
        var run = s.checkRun("old", 1, retired.contentHash());
        assertThat(run.status()).isEqualTo(RunContent.RETIRED);
        assertThat(run.canContinue()).isTrue();
    }

    @Test
    void unknownKeyAndUnpublishedKey() {
        PracticeAvailabilityService s = service(true, false);
        assertThat(s.checkStart("nope").denial()).isEqualTo(StartDenial.NOT_FOUND);
        assertThat(s.checkStart("wip").denial()).isEqualTo(StartDenial.NOT_PUBLISHED);
        assertThat(service(true, true).checkStart("wip").allowed()).isTrue();
    }

    @Test
    void aRunNeverSeesContentWhoseHashMoved() {
        PracticeAvailabilityService s = service(true, false);
        assertThat(s.checkRun("live", 1, "sha256:somethingelse").status()).isEqualTo(RunContent.MISSING);
        assertThat(s.checkRun("live", 9, live.contentHash()).status()).isEqualTo(RunContent.MISSING);
        assertThat(s.checkRun("live", 1, live.contentHash()).status()).isEqualTo(RunContent.AVAILABLE);
    }

    @Test
    void aPreviewRunDoesNotContinueWherePreviewsAreOff() {
        assertThat(service(true, true).checkRun("wip", 1, draft.contentHash()).status()).isEqualTo(RunContent.AVAILABLE);
        assertThat(service(true, false).checkRun("wip", 1, draft.contentHash()).status()).isEqualTo(RunContent.DISABLED);
    }

    @Test
    void previewFlagIsHonoredOnlyInAPreviewEnvironment() {
        assertThat(PracticeAvailabilityService.previewAllowed(false, new String[]{"local"})).isFalse();
        assertThat(PracticeAvailabilityService.previewAllowed(true, new String[]{})).isFalse();      // Heroku today
        assertThat(PracticeAvailabilityService.previewAllowed(true, null)).isFalse();
        assertThat(PracticeAvailabilityService.previewAllowed(true, new String[]{"staging"})).isFalse();
        assertThat(PracticeAvailabilityService.previewAllowed(true, new String[]{"local", "prod"})).isFalse();
        assertThat(PracticeAvailabilityService.previewAllowed(true, new String[]{"production", "practice-preview"})).isFalse();
        assertThat(PracticeAvailabilityService.previewAllowed(true, new String[]{"local"})).isTrue();
        assertThat(PracticeAvailabilityService.previewAllowed(true, new String[]{"practice-preview"})).isTrue();
    }

    @Test
    void theSpringConstructorAppliesTheGuard() {
        var env = new org.springframework.mock.env.MockEnvironment();
        assertThat(new PracticeAvailabilityService(catalog, repo, true, true, env).previewActive()).isFalse();
        assertThat(new PracticeAvailabilityService(catalog, repo, true, true, env).checkStart("wip").denial())
                .isEqualTo(StartDenial.NOT_PUBLISHED);
        env.setActiveProfiles("local");
        assertThat(new PracticeAvailabilityService(catalog, repo, true, true, env).previewActive()).isTrue();
    }

    @Test
    void disableAndEnableRecordWhoAndWhy() {
        PracticeAvailabilityService s = service(true, false);
        PracticeContentControl c = s.disable("live", "unsafe wording", "mod@example.com");
        assertThat(c.getDisabledAt()).isEqualTo(NOW);
        assertThat(c.getDisabledReason()).isEqualTo("unsafe wording");
        assertThat(c.getUpdatedBy()).isEqualTo("mod@example.com");

        when(repo.findById("live")).thenReturn(Optional.of(c));
        PracticeContentControl back = s.enable("live", "mod2@example.com").orElseThrow();
        assertThat(back.isDisabled()).isFalse();
        assertThat(back.getDisabledReason()).isEqualTo("unsafe wording"); // last reason kept for operators
        assertThat(back.getUpdatedBy()).isEqualTo("mod2@example.com");
    }

    @Test
    void statusListShowsEveryVersionAndItsSwitch() {
        disable("old");
        var rows = service(true, false).statusList();
        assertThat(rows).extracting(PracticeAvailabilityService.ContentStatus::key)
                .containsExactlyInAnyOrder("live", "old", "wip");
        assertThat(rows).filteredOn(r -> r.key().equals("live")).singleElement()
                .satisfies(r -> assertThat(r.startable()).isTrue());
        assertThat(rows).filteredOn(r -> r.key().equals("old")).singleElement()
                .satisfies(r -> {
                    assertThat(r.disabled()).isTrue();
                    assertThat(r.disabledReason()).isEqualTo("unsafe wording");
                });
    }
}
