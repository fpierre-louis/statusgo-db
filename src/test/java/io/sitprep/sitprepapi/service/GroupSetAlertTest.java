package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code setAlert} must change the alert and NOTHING else.
 *
 * <p>The reason this test exists is the shape of what it replaces. Toggling an
 * alert used to mean {@code PUT /groups/{id}} with an entire group object,
 * assembled by the frontend from a snapshot taken when the page mounted — so a
 * toggle replayed every field as it looked minutes ago and silently reverted
 * anything another admin had changed since. On a life-safety control, and
 * through an offline outbox that could replay it much later.</p>
 *
 * <p>The first test is the whole point of the endpoint: a concurrent rename has
 * to survive. The rest pin the five side effects of a transition, because
 * missing any one of them fails silently rather than loudly.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupSetAlertTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;

    private GroupService service;

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService, notificationService);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Group stored(String alert) {
        Group g = new Group();
        g.setGroupId("g-hoa");
        g.setGroupName("Traverse Ridge HOA");
        g.setGroupType("HOA/Neighborhood");
        g.setAlert(alert);
        g.setOwnerEmail("owner@probe.app");
        g.setAdminEmails(List.of("owner@probe.app"));
        g.setMemberEmails(List.of("owner@probe.app", "mv@probe.app"));
        g.setDescription("Neighbors on the east bench.");
        when(groupRepo.findByGroupId("g-hoa")).thenReturn(Optional.of(g));
        return g;
    }

    // ── the reason the endpoint exists ───────────────────────────────────────

    @Test
    void aConcurrentRenameSurvivesAnAlertToggle() {
        Group g = stored("Inactive");
        // Another admin renamed the circle and rewrote its description after
        // the toggling admin's page loaded. Under the old whole-object PUT both
        // edits were reverted; setAlert never carries those fields at all.
        g.setGroupName("Traverse Ridge Neighbors");
        g.setDescription("Now also running the tool library.");

        Group saved = service.setAlert("g-hoa", true, "owner@probe.app");

        assertThat(saved.getAlert()).isEqualTo("Active");
        assertThat(saved.getGroupName()).isEqualTo("Traverse Ridge Neighbors");
        assertThat(saved.getDescription()).isEqualTo("Now also running the tool library.");
        assertThat(saved.getMemberEmails()).containsExactly("owner@probe.app", "mv@probe.app");
    }

    // ── the five side effects of a transition ────────────────────────────────

    @Test
    void activatingStampsTheTimeAndResetsTheReminderCounter() {
        Group g = stored("Inactive");
        g.setCheckInRemindersFired(4);

        Group saved = service.setAlert("g-hoa", true, "owner@probe.app");

        assertThat(saved.getAlertActivatedAt()).isNotNull();
        assertThat(saved.getCheckInRemindersFired()).isZero();
    }

    @Test
    void endingClearsTheTimeAndResetsTheCounterToo() {
        Group g = stored("Active");
        g.setCheckInRemindersFired(3);

        Group saved = service.setAlert("g-hoa", false, "owner@probe.app");

        assertThat(saved.getAlert()).isEqualTo("Inactive");
        assertThat(saved.getAlertActivatedAt()).isNull();
        // Reset on BOTH transitions, so a manual end cannot leave a pending
        // tick to fire a stale reminder.
        assertThat(saved.getCheckInRemindersFired()).isZero();
    }

    @Test
    void activatingBroadcastsTheAlertFrame() {
        stored("Inactive");
        TransactionSynchronizationManager.initSynchronization();

        service.setAlert("g-hoa", true, "owner@probe.app");
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(ws).sendGroupAlertStatus(org.mockito.ArgumentMatchers.eq("g-hoa"), any());
    }

    @Test
    void aNoOpToggleChangesNothingAndBroadcastsNothing() {
        Group g = stored("Active");
        g.setCheckInRemindersFired(2);
        TransactionSynchronizationManager.initSynchronization();

        Group saved = service.setAlert("g-hoa", true, "owner@probe.app");
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        // Already active: the counter must NOT be reset, or a re-tap during an
        // alert would silently restart the reminder cadence.
        assertThat(saved.getCheckInRemindersFired()).isEqualTo(2);
        verify(ws, never()).sendGroupAlertStatus(any(), any());
    }

    @Test
    void aHouseholdRecordsCheckinStartedAndEnded() {
        Group g = stored("Inactive");
        g.setGroupType(HouseholdEventService.HOUSEHOLD_GROUP_TYPE);

        service.setAlert("g-hoa", true, "owner@probe.app");
        verify(householdEventService).recordCheckinStarted("g-hoa", "owner@probe.app");

        g.setAlert("Active");
        service.setAlert("g-hoa", false, "owner@probe.app");
        verify(householdEventService).recordCheckinEnded("g-hoa", "owner@probe.app");
    }

    @Test
    void aNonHouseholdRecordsNoCheckinEvents() {
        stored("Inactive");
        service.setAlert("g-hoa", true, "owner@probe.app");
        verify(householdEventService, never()).recordCheckinStarted(any(), any());
    }

    @Test
    void theActorIsRecordedAsLastUpdatedBy() {
        stored("Inactive");
        Group saved = service.setAlert("g-hoa", true, "mv@probe.app");
        assertThat(saved.getLastUpdatedBy()).isEqualTo("mv@probe.app");
    }
}
