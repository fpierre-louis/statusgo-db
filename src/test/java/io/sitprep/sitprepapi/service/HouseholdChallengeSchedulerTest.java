package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserAlertPreference;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.NotificationLogRepo;
import io.sitprep.sitprepapi.repo.UserAlertPreferenceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HouseholdChallengeSchedulerTest {

    private static final String HOUSEHOLD = "hh-1";

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock UserAlertPreferenceRepo preferenceRepo;
    @Mock NotificationLogRepo notificationLogRepo;
    @Mock NotificationService notificationService;

    private HouseholdChallengeScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new HouseholdChallengeScheduler(
                groupRepo, userInfoRepo, preferenceRepo, notificationLogRepo, notificationService);
        lenient().when(preferenceRepo.findByEmail(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void mondayMorningKickoffSendsToHouseholdMembers() {
        Group household = household(List.of("Owner@Example.com", "member@example.com"));
        when(userInfoRepo.findByUserEmailLowerIn(List.of("owner@example.com", "member@example.com")))
                .thenReturn(List.of(user("owner@example.com"), user("member@example.com")));
        when(notificationLogRepo.countByRecipientTypeReferenceSince(
                anyString(), eq(HouseholdChallengeScheduler.TYPE_KICKOFF), eq(HOUSEHOLD), any()))
                .thenReturn(0L);

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-08-31T15:05:00Z")); // Monday 9:05 AM Denver

        assertThat(sent).isEqualTo(2);
        verify(notificationService, times(2)).deliverPresenceAwareForGroup(
                anyString(),
                eq("This week's household drill"),
                eq("Tap to start - most drills take about 5 minutes."),
                eq("Pierre-Louis Household"),
                eq("/images/group-alert-icon.png"),
                eq(HouseholdChallengeScheduler.TYPE_KICKOFF),
                eq(HOUSEHOLD),
                eq("/?challenge=open"),
                contains("\"weekKey\":\"2026-W36\""),
                anyString(),
                eq(HOUSEHOLD),
                eq(Category.WEEKLY_DRILL_REMINDER)
        );
    }

    @Test
    void thursdayEveningNudgeUsesTheNudgeType() {
        Group household = household(List.of("owner@example.com"));
        when(userInfoRepo.findByUserEmailLowerIn(List.of("owner@example.com")))
                .thenReturn(List.of(user("owner@example.com")));
        when(notificationLogRepo.countByRecipientTypeReferenceSince(
                anyString(), eq(HouseholdChallengeScheduler.TYPE_NUDGE), eq(HOUSEHOLD), any()))
                .thenReturn(0L);

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-09-04T00:35:00Z")); // Thursday 6:35 PM Denver

        assertThat(sent).isEqualTo(1);
        verify(notificationService).deliverPresenceAwareForGroup(
                eq("owner@example.com"),
                eq("Still time for this week's drill"),
                eq("Halfway through the week - your household hasn't logged it yet."),
                eq("Pierre-Louis Household"),
                eq("/images/group-alert-icon.png"),
                eq(HouseholdChallengeScheduler.TYPE_NUDGE),
                eq(HOUSEHOLD),
                eq("/?challenge=open"),
                contains("\"slot\":\"weekly_drill_nudge\""),
                eq("token-owner@example.com"),
                eq(HOUSEHOLD),
                eq(Category.WEEKLY_DRILL_REMINDER)
        );
    }

    @Test
    void completedCurrentWeekSuppressesBothSlots() {
        Group household = household(List.of("owner@example.com"));
        household.getChallengeProgress().put("2026-W36", true);

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-08-31T15:05:00Z"));

        assertThat(sent).isZero();
        verifyNoInteractions(userInfoRepo, notificationLogRepo, notificationService);
    }

    @Test
    void activeHouseholdAlertSuppressesDrillNoise() {
        Group household = household(List.of("owner@example.com"));
        household.setAlert("Active");

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-08-31T15:05:00Z"));

        assertThat(sent).isZero();
        verifyNoInteractions(userInfoRepo, notificationLogRepo, notificationService);
    }

    @Test
    void outsideSlotWindowDoesNotSend() {
        Group household = household(List.of("owner@example.com"));

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-08-31T15:20:00Z")); // 9:20 AM Denver

        assertThat(sent).isZero();
        verifyNoInteractions(userInfoRepo, notificationLogRepo, notificationService);
    }

    @Test
    void existingRecipientLogSuppressesDuplicateForThatSlot() {
        Group household = household(List.of("owner@example.com", "member@example.com"));
        when(userInfoRepo.findByUserEmailLowerIn(List.of("owner@example.com", "member@example.com")))
                .thenReturn(List.of(user("owner@example.com"), user("member@example.com")));
        when(notificationLogRepo.countByRecipientTypeReferenceSince(
                eq("owner@example.com"), eq(HouseholdChallengeScheduler.TYPE_KICKOFF), eq(HOUSEHOLD), any()))
                .thenReturn(1L);
        when(notificationLogRepo.countByRecipientTypeReferenceSince(
                eq("member@example.com"), eq(HouseholdChallengeScheduler.TYPE_KICKOFF), eq(HOUSEHOLD), any()))
                .thenReturn(0L);

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-08-31T15:05:00Z"));

        assertThat(sent).isEqualTo(1);
        verify(notificationService, never()).deliverPresenceAwareForGroup(
                eq("owner@example.com"), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(notificationService).deliverPresenceAwareForGroup(
                eq("member@example.com"), anyString(), anyString(), anyString(), anyString(),
                eq(HouseholdChallengeScheduler.TYPE_KICKOFF), eq(HOUSEHOLD), eq("/?challenge=open"),
                anyString(), anyString(), eq(HOUSEHOLD), eq(Category.WEEKLY_DRILL_REMINDER));
    }

    @Test
    void memberPreferenceTimezoneControlsTheLocalFireWindow() {
        Group household = household(List.of("owner@example.com"));
        UserAlertPreference pref = new UserAlertPreference();
        pref.setUserEmail("owner@example.com");
        pref.setTimezone("America/New_York");
        when(preferenceRepo.findByEmail("owner@example.com")).thenReturn(Optional.of(pref));
        when(userInfoRepo.findByUserEmailLowerIn(List.of("owner@example.com")))
                .thenReturn(List.of(user("owner@example.com")));
        when(notificationLogRepo.countByRecipientTypeReferenceSince(
                anyString(), eq(HouseholdChallengeScheduler.TYPE_KICKOFF), eq(HOUSEHOLD), any()))
                .thenReturn(0L);

        int sent = scheduler.maybeSendForHousehold(
                household,
                Instant.parse("2026-08-31T13:05:00Z")); // Monday 9:05 AM New York

        assertThat(sent).isEqualTo(1);
        verify(notificationService).deliverPresenceAwareForGroup(
                eq("owner@example.com"), anyString(), anyString(), anyString(), anyString(),
                eq(HouseholdChallengeScheduler.TYPE_KICKOFF), eq(HOUSEHOLD), eq("/?challenge=open"),
                contains("\"weekKey\":\"2026-W36\""), anyString(), eq(HOUSEHOLD),
                eq(Category.WEEKLY_DRILL_REMINDER));
    }

    @Test
    void sweepLoadsOnlyHouseholdsAndReturnsRecipientCount() {
        Group household = household(List.of("owner@example.com"));
        when(groupRepo.findHouseholdsForChallengeSweep(any(Pageable.class))).thenReturn(List.of(household));
        when(userInfoRepo.findByUserEmailLowerIn(List.of("owner@example.com")))
                .thenReturn(List.of(user("owner@example.com")));

        int sent = scheduler.sweepOnce(Instant.parse("2026-08-31T15:05:00Z"));

        assertThat(sent).isEqualTo(1);
        verify(groupRepo).findHouseholdsForChallengeSweep(any(Pageable.class));
    }

    private static Group household(List<String> members) {
        Group g = new Group();
        g.setGroupId(HOUSEHOLD);
        g.setGroupName("Pierre-Louis Household");
        g.setGroupType(HouseholdEventService.HOUSEHOLD_GROUP_TYPE);
        g.setMemberEmails(members);
        g.setChallengeProgress(new HashMap<>());
        return g;
    }

    private static UserInfo user(String email) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setFcmtoken("token-" + email);
        return u;
    }
}
