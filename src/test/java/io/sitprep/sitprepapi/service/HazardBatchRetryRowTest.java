package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.notifications.NotificationPresentationBuilder;
import io.sitprep.sitprepapi.repo.GroupPostRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D10, the writer side: an agency retry re-sends the push to a recipient who
 * already holds this alert's inbox row, and must not give them a second one.
 * The batch also reports how many of its failures were "no token" — the
 * failure a retry cannot fix.
 *
 * <p>No test here may reach the FCM branch: if another test in the JVM has
 * initialised the default FirebaseApp, that would be a real send.</p>
 */
class HazardBatchRetryRowTest {

    NotificationLogRepo logRepo;
    NotificationService service;

    @BeforeEach
    void setUp() {
        logRepo = mock(NotificationLogRepo.class);
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new NotificationService(mock(WebSocketMessageSender.class), mock(UserInfoRepo.class), logRepo,
                mock(WebSocketPresenceService.class), mock(PushPolicyService.class), mock(GroupMuteService.class));
        ReflectionTestUtils.setField(service, "presentationBuilder", new NotificationPresentationBuilder(
                mock(UserInfoRepo.class), mock(GroupRepo.class), mock(GroupPostRepo.class), mock(PostRepo.class)));
    }

    @Test
    void noTokenFailuresAreCounted() {
        NotificationService.HazardBatchResult r = service.sendHazardAlertBatch(
                List.of(user("a@x.com", null), user("b@x.com", "")),
                "Water advisory", "Use bottled water", "agency-alert:17", "/community/posts/17");

        assertThat(r.failed()).isEqualTo(2);
        assertThat(r.noToken()).isEqualTo(2);
        verify(logRepo, times(2)).save(any(NotificationLog.class));   // first send: each gets its row
    }

    @Test
    void aRecipientWhoAlreadyHasTheRowGetsNoSecondOne() {
        // The no-token branch is the one a unit test can reach without FCM
        // (FirebaseMessaging is static and this suite has no inline mock
        // maker); every branch writes through the same alreadyLogged guard.
        NotificationService.HazardBatchResult r = service.sendHazardAlertBatch(
                List.of(user("Again@X.com", null), user("new@x.com", null)),
                "Water advisory", "Use bottled water", "agency-alert:17", "/community/posts/17",
                null, false, Set.of("again@x.com"));

        assertThat(r.attempted()).isEqualTo(2);
        assertThat(r.noToken()).isEqualTo(2);
        ArgumentCaptor<NotificationLog> rows = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo, times(1)).save(rows.capture());
        assertThat(rows.getValue().getRecipientEmail()).isEqualTo("new@x.com");
    }

    private static UserInfo user(String email, String token) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken(token);
        return u;
    }
}
