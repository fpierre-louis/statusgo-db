package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.ActivityDetailDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CatalogDto;
import io.sitprep.sitprepapi.practice.FamilyPracticeDtos.CompletionDto;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import io.sitprep.sitprepapi.practice.PracticeSuppressionService.Reason;
import io.sitprep.sitprepapi.practice.PracticeSuppressionService.Suppression;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.family;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FamilyPracticeServiceTest {

    private static final String HH = "hh-1";
    private static final String ME = "adult@example.com";
    private static final Instant NOW = Instant.parse("2026-10-19T18:00:00Z");

    private final List<FamilyPracticeCompletion> table = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private PracticeSuppressionService suppression;
    private FamilyPracticeService service;
    private Version live;

    @BeforeEach
    void setUp() {
        live = version(approve(family("kit-builder", 1), PublishState.PUBLISHED));
        PracticeCatalog catalog = PracticeCatalog.of(List.of(live));
        PracticeContentControlRepo controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PracticeAvailabilityService availability = new PracticeAvailabilityService(catalog, controls, true, false, clock);

        suppression = mock(PracticeSuppressionService.class);
        when(suppression.check(any(), any())).thenReturn(Optional.empty());
        when(suppression.headsUp(any())).thenReturn(Optional.empty());

        GroupRepo groups = mock(GroupRepo.class);
        Group household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        when(groups.findByGroupId(HH)).thenReturn(Optional.of(household));
        HouseholdAccessService access = mock(HouseholdAccessService.class);
        service = new FamilyPracticeService(availability, suppression, inMemoryRepo(), groups, access, clock);
    }

    private FamilyPracticeCompletionRepo inMemoryRepo() {
        FamilyPracticeCompletionRepo repo = mock(FamilyPracticeCompletionRepo.class);
        org.mockito.stubbing.Answer<FamilyPracticeCompletion> save = inv -> {
            FamilyPracticeCompletion row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(ids.getAndIncrement());
                table.add(row);
            }
            return row;
        };
        when(repo.saveAndFlush(any())).thenAnswer(save);
        when(repo.findByHouseholdIdAndActivityKeyAndContentVersion(any(), any(), any(Integer.class)))
                .thenAnswer(inv -> table.stream().filter(row ->
                        inv.getArgument(0).equals(row.getHouseholdId())
                                && inv.getArgument(1).equals(row.getActivityKey())
                                && ((Integer) inv.getArgument(2)) == row.getContentVersion()).findFirst());
        when(repo.findByHouseholdIdOrderByCompletedAtDesc(any())).thenAnswer(inv -> table.stream()
                .filter(row -> inv.getArgument(0).equals(row.getHouseholdId()))
                .sorted((a, b) -> b.getCompletedAt().compareTo(a.getCompletedAt())).toList());
        return repo;
    }

    @Test
    void catalogAndDetailExposeServerOwnedActivityWithoutCompletionTags() {
        CatalogDto catalog = service.catalog(ME, HH);
        assertThat(catalog.activities()).singleElement().satisfies(card -> {
            assertThat(card.key()).isEqualTo("kit-builder");
            assertThat(card.completedAt()).isNull();
        });

        ActivityDetailDto detail = service.detail("kit-builder", ME, HH);
        assertThat(detail.contentHash()).isEqualTo(live.contentHash());
        assertThat(detail.facilitation()).contains("decide together");
        assertThat(detail.activity().recapTitle()).isEqualTo("A few basics to remember");
        assertThat(detail.activity().recapBody()).startsWith("FEMA recommends");
        assertThat(detail.activity().items()).extracting(FamilyPracticeDtos.ItemDto::key).containsExactly("water");
        assertThat(detail.activity().nextStep().action()).isEqualTo(io.sitprep.sitprepapi.readiness.ReadinessAction.OPEN_HOME_STOCKPILE);
    }

    @Test
    void completionStoresOnlyHouseholdContentIdentityAndServerTagsAndIsIdempotent() {
        CompletionDto first = service.complete("kit-builder", "Adult@Example.com", HH, 1, live.contentHash());
        CompletionDto again = service.complete("kit-builder", ME, HH, 1, live.contentHash());

        assertThat(again.completedAt()).isEqualTo(first.completedAt()).isEqualTo(NOW);
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getCompletedByEmail()).isEqualTo(ME);
        assertThat(table.get(0).getDebriefTags()).containsExactly("discussed_core_kit_items");
        assertThat(service.progress(HH, ME).activities()).singleElement()
                .satisfies(p -> assertThat(p.completedVersions()).isEqualTo(1));
    }

    @Test
    void rejectsAStaleContentIdentityAndNeverAcceptsClientPicks() {
        assertThatThrownBy(() -> service.complete("kit-builder", ME, HH, 1, "sha256:stale"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(table).isEmpty();
        assertThat(FamilyPracticeDtos.CompleteRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("contentVersion", "contentHash");
    }

    @Test
    void aPreviewCompletionIsNeverStored() {
        Version draft = version(family("kit-builder", 1));
        PracticeContentControlRepo controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        PracticeAvailabilityService preview = new PracticeAvailabilityService(
                PracticeCatalog.of(List.of(draft)), controls, true, true, clock);
        GroupRepo groups = mock(GroupRepo.class);
        Group household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        when(groups.findByGroupId(HH)).thenReturn(Optional.of(household));
        FamilyPracticeCompletionRepo repo = inMemoryRepo();
        FamilyPracticeService previewService = new FamilyPracticeService(preview, suppression, repo, groups,
                mock(HouseholdAccessService.class), clock);

        // Invariant: unpublished/preview content never creates a household completion record.
        CompletionDto done = previewService.complete("kit-builder", ME, HH, 1, draft.contentHash());
        previewService.complete("kit-builder", ME, HH, 1, draft.contentHash());

        assertThat(done.contentHash()).isEqualTo(draft.contentHash());
        verify(repo, never()).saveAndFlush(any());
        verify(repo, never()).save(any());
        assertThat(table).isEmpty();
        assertThat(previewService.progress(HH, ME).activities()).isEmpty();
        assertThat(previewService.catalog(ME, HH).activities()).singleElement()
                .satisfies(card -> assertThat(card.completedAt()).isNull());
        assertThat(previewService.detail("kit-builder", ME, HH).completedAt()).isNull();

        // The same version, once published, starts the household from "not done".
        Version published = version(approve(family("kit-builder", 1), PublishState.PUBLISHED));
        assertThat(published.contentHash()).isEqualTo(draft.contentHash());
        assertThat(service.catalog(ME, HH).activities()).singleElement()
                .satisfies(card -> assertThat(card.completedAt()).isNull());
    }

    @Test
    void activeResponseBlocksCompletion() {
        when(suppression.check(HH, ME)).thenReturn(Optional.of(new Suppression(
                Reason.CHECK_IN, "Checking in", "Practice can wait", null, java.util.Map.of())));
        assertThatThrownBy(() -> service.complete("kit-builder", ME, HH, 1, live.contentHash()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(table).isEmpty();
    }
}
