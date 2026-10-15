package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PushPolicyService#wouldPush} is {@link PushPolicyService#decide} == Lane A,
 * through the same step function, minus the rate token (Live Activities P2,
 * 2026-10-14; the alerts lane asked for exactly this lock).
 */
class PushPolicyWouldPushTest {

    private UserAlertPreferenceRepo repo;
    private RateLimiterService rateLimiter;
    private PushPolicyService policy;

    @BeforeEach
    void setUp() {
        repo = mock(UserAlertPreferenceRepo.class);
        rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(any(), any())).thenReturn(true);
        policy = new PushPolicyService(repo, rateLimiter);
    }

    private void prefs(Consumer<UserAlertPreference> edit) {
        UserAlertPreference p = new UserAlertPreference();
        p.setUserEmail("u@x.com");
        edit.accept(p);
        when(repo.findByEmail("u@x.com")).thenReturn(Optional.of(p));
    }

    private static final Map<String, Consumer<UserAlertPreference>> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("defaults", p -> { });
        PRESETS.put("push off", p -> p.setPushEnabled(false));
        PRESETS.put("push and inbox off", p -> { p.setPushEnabled(false); p.setInboxEnabled(false); });
        PRESETS.put("group alerts muted", p -> p.setGroupAlerts(false));
        PRESETS.put("quiet hours all day", p -> {
            p.setQuietHoursEnabled(true);
            p.setQuietStart(LocalTime.MIN);
            p.setQuietEnd(LocalTime.of(23, 59, 59));
            p.setTimezone("UTC");
        });
    }

    private static final List<Category> CATEGORIES = List.of(
            Category.GROUP_ALERT_HOUSEHOLD, Category.GROUP_ALERT_ORG, Category.CHECK_IN_REQUEST);

    @Test
    void wouldPushIsDecideEqualsLaneAAcrossThePresetMatrix() {
        for (var preset : PRESETS.entrySet()) {
            for (Category c : CATEGORIES) {
                prefs(preset.getValue());
                boolean expected = policy.decide("u@x.com", c, null).lane() == Lane.A;
                assertThat(policy.wouldPush("u@x.com", c, null))
                        .as(preset.getKey() + " / " + c)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void wouldPushNeverSpendsARateToken() {
        prefs(p -> { });
        clearInvocations(rateLimiter);
        policy.wouldPush("u@x.com", Category.GROUP_ALERT_ORG, null);
        verify(rateLimiter, never()).tryConsume(any(), any());
    }

    @Test
    void anOrgUserOverTheirRateCapStillGetsTheCompanionSignal() {
        // decide() demotes to B once the cap is spent; the companion rides the
        // push that already got through, so it must not be demoted by the same cap.
        prefs(p -> { });
        when(rateLimiter.tryConsume(any(), any())).thenReturn(false);
        assertThat(policy.decide("u@x.com", Category.GROUP_ALERT_ORG, null).lane()).isEqualTo(Lane.B);
        assertThat(policy.wouldPush("u@x.com", Category.GROUP_ALERT_ORG, null)).isTrue();
    }

    @Test
    void noUserNoPush() {
        assertThat(policy.wouldPush(null, Category.GROUP_ALERT_ORG, null)).isFalse();
        assertThat(policy.wouldPush("u@x.com", null, null)).isFalse();
    }
}
