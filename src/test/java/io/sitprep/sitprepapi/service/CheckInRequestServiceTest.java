package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CheckInRequest;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.CheckInRequestRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RC-2 · "was this person actually asked" is a fact SitPrep did not record.
 *
 * <p>Before this service, {@code requestCheckIn} sent notifications and
 * persisted nothing, so a member whose category is muted was never contacted,
 * left no trace anywhere, and rendered on the roster as NO RESPONSE — a claim
 * about someone nobody had asked.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckInRequestServiceTest {

    @Mock CheckInRequestRepo repo;
    private CheckInRequestService service;

    private static final Instant ACTIVATED = Instant.parse("2026-09-10T02:00:00Z");

    @BeforeEach
    void setUp() {
        service = new CheckInRequestService(repo);
        when(repo.findByGroupIdAndWindowStartedAtGreaterThanEqual(anyString(), any()))
                .thenReturn(List.of());
    }

    private static Group household(String alert, Instant activatedAt) {
        Group g = new Group();
        g.setGroupId("hh-1");
        g.setGroupType("Household");
        g.setAlert(alert);
        g.setAlertActivatedAt(activatedAt);
        g.setMemberEmails(List.of("a@probe.app", "b@probe.app"));
        return g;
    }

    @Test
    void anOpenAlertAnchorsTheWindowToWhenItOpened() {
        Group g = household("Active", ACTIVATED);
        assertThat(CheckInRequestService.windowStartFor(g, Instant.now())).isEqualTo(ACTIVATED);
    }

    /**
     * A nudge outside a crisis is still an ask, and it becomes its own window
     * rather than borrowing a situation that does not exist.
     */
    @Test
    void withNoAlertOpenTheAskIsItsOwnWindow() {
        Group g = household("Cleared", null);
        Instant now = Instant.parse("2026-09-10T05:00:00Z");
        assertThat(CheckInRequestService.windowStartFor(g, now)).isEqualTo(now);
    }

    @Test
    void recordsOneRowPerPersonAgainstTheOpenWindow() {
        service.recordAsked(household("Active", ACTIVATED),
                List.of("a@probe.app", "B@Probe.app"), "owner@probe.app");

        ArgumentCaptor<List<CheckInRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(saved.capture());

        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue()).allSatisfy(r -> {
            assertThat(r.getWindowStartedAt()).isEqualTo(ACTIVATED);
            assertThat(r.getRequestedByEmail()).isEqualTo("owner@probe.app");
            // Lower-cased on write, like every other email column.
            assertThat(r.getSubjectEmail()).isEqualTo(r.getSubjectEmail().toLowerCase());
        });
    }

    @Test
    void reAskingInsideTheSameWindowUpdatesRatherThanDuplicating() {
        CheckInRequest existing = new CheckInRequest(
                "hh-1", "a@probe.app", ACTIVATED,
                ACTIVATED.plus(1, ChronoUnit.MINUTES), "owner@probe.app");
        when(repo.findByGroupIdAndWindowStartedAtGreaterThanEqual("hh-1", ACTIVATED))
                .thenReturn(List.of(existing));

        service.recordAsked(household("Active", ACTIVATED), List.of("a@probe.app"), "someone@probe.app");

        ArgumentCaptor<List<CheckInRequest>> saved = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0)).isSameAs(existing);
        assertThat(existing.getRequestedByEmail()).isEqualTo("someone@probe.app");
    }

    /**
     * The read the roster makes. An email ABSENT from this map was not asked,
     * which is a different fact from having been asked and not answered — and
     * conflating the two is the whole defect.
     */
    @Test
    void reportsWhoWasAskedAndOmitsWhoWasNot() {
        when(repo.findByGroupIdAndWindowStartedAtGreaterThanEqual("hh-1", ACTIVATED))
                .thenReturn(List.of(new CheckInRequest(
                        "hh-1", "a@probe.app", ACTIVATED, ACTIVATED, "owner@probe.app")));

        Map<String, Instant> asked = service.askedAtByEmail(household("Active", ACTIVATED));

        assertThat(asked).containsKey("a@probe.app");
        assertThat(asked).doesNotContainKey("b@probe.app");
    }

    @Test
    void bookkeepingFailureNeverBreaksTheAsk() {
        when(repo.findByGroupIdAndWindowStartedAtGreaterThanEqual(anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        // No throw. An ask that was sent but not recorded is a lesser failure
        // than an ask that did not happen because bookkeeping failed.
        service.recordAsked(household("Active", ACTIVATED), List.of("a@probe.app"), "owner@probe.app");
        assertThat(service.askedAtByEmail(household("Active", ACTIVATED))).isEmpty();
    }

    @Test
    void anEmptyRosterWritesNothing() {
        service.recordAsked(household("Active", ACTIVATED), List.of(), "owner@probe.app");
        verify(repo, never()).saveAll(any());
    }
}
