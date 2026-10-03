package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import io.sitprep.sitprepapi.websocket.WebSocketPresenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Readiness Tokens T2: an unlock is inbox-only. Never a push — not by lane, not
 * by a missing category, not by a token on the row.
 */
class TokenUnlockNotificationTest {

    @Test
    @DisplayName("TOKEN_UNLOCKED is declared Lane B")
    void tokenUnlockedIsLaneB() {
        assertThat(PushPolicyService.defaultLaneFor(Category.TOKEN_UNLOCKED)).isEqualTo(Lane.B);
    }

    @Test
    @DisplayName("the token_unlocked type maps to TOKEN_UNLOCKED, so a caller without a category can't reach the null-lane send")
    void typeMapsToCategory() {
        assertThat(NotificationService.mapTypeToCategory(NotificationService.TYPE_TOKEN_UNLOCKED))
                .isEqualTo(Category.TOKEN_UNLOCKED);
    }

    @Test
    @DisplayName("logInboxOnly writes a token-less Lane B row + the inbox event, and no banner")
    void logInboxOnlyWritesARowAndNothingElse() {
        WebSocketMessageSender ws = mock(WebSocketMessageSender.class);
        NotificationLogRepo logRepo = mock(NotificationLogRepo.class);
        WebSocketPresenceService presence = mock(WebSocketPresenceService.class);
        PushPolicyService policy = mock(PushPolicyService.class);
        UserInfoRepo users = mock(UserInfoRepo.class);
        when(logRepo.save(any(NotificationLog.class))).thenAnswer(inv -> inv.getArgument(0));
        NotificationService service = new NotificationService(ws, users, logRepo, presence, policy,
                mock(GroupMuteService.class));

        service.logInboxOnly("ana@x.com", NotificationService.TYPE_TOKEN_UNLOCKED, "Drill Crew earned",
                "Your household completed its first practice drill.", "household:3", "/profile?tab=tokens",
                "{\"awardId\":\"household:3\"}", Category.TOKEN_UNLOCKED);

        ArgumentCaptor<NotificationLog> row = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepo).save(row.capture());
        assertThat(row.getValue().getToken()).isNull();
        assertThat(row.getValue().getLane()).isEqualTo("B");
        assertThat(row.getValue().getCategory()).isEqualTo("TOKEN_UNLOCKED");
        assertThat(row.getValue().getType()).isEqualTo("token_unlocked");
        assertThat(row.getValue().getTargetUrl()).isEqualTo("/profile?tab=tokens");
        assertThat(row.getValue().getAdditionalData()).contains("household:3");

        verify(ws).sendInboxEvent(eq("ana@x.com"), any(Map.class));
        verify(ws, never()).sendInAppNotification(any());  // no banner frame
        verifyNoInteractions(policy, presence, users);       // no policy re-route, no FCM-token lookup
    }
}
