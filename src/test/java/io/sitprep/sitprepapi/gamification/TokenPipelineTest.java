package io.sitprep.sitprepapi.gamification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.NotificationService;
import io.sitprep.sitprepapi.service.PushPolicyService;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** T2: publisher timing, evaluator dispatch and safety, notifier policy + fan-out. */
class TokenPipelineTest {

    private static final String ME = "ana@x.com";
    private static final String HH = "hh-1";

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ── Publisher ───────────────────────────────────────────────────────────

    @Test
    void outsideATransactionThePublisherDispatchesAtOnce() {
        TokenEvaluationService evaluator = mock(TokenEvaluationService.class);
        TokenEvent e = TokenEvent.user(TokenEventType.ASK_QUESTION_CREATED, ME, 1L);
        new TokenEventPublisher(evaluator).publishAfterCommit(e);
        verify(evaluator).evaluateAsync(e);
    }

    @Test
    void insideATransactionNothingRunsUntilCommit() {
        TokenEvaluationService evaluator = mock(TokenEvaluationService.class);
        TokenEvent e = TokenEvent.household(TokenEventType.DRILL_COMPLETED, ME, HH, "go-bag");
        TransactionSynchronizationManager.initSynchronization();

        new TokenEventPublisher(evaluator).publishAfterCommit(e);
        verify(evaluator, never()).evaluateAsync(any());   // a rollback here would earn nothing

        List<TransactionSynchronization> syncs = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(evaluator).evaluateAsync(e);
    }

    @Test
    void aFailingEvaluatorNeverReachesTheCaller() {
        TokenEvaluationService evaluator = mock(TokenEvaluationService.class);
        doThrow(new RuntimeException("executor gone")).when(evaluator).evaluateAsync(any());
        TokenEventPublisher publisher = new TokenEventPublisher(evaluator);
        TokenEvent e = TokenEvent.user(TokenEventType.ASK_QUESTION_CREATED, ME, 1L);

        assertThatCode(() -> publisher.publishAfterCommit(e)).doesNotThrowAnyException();

        TransactionSynchronizationManager.initSynchronization();
        publisher.publishAfterCommit(e);
        List<TransactionSynchronization> syncs = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        assertThatCode(() -> syncs.forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
    }

    // ── Evaluator ───────────────────────────────────────────────────────────

    private HouseholdTokenCriteria householdCriteria;
    private UserTokenCriteria userCriteria;
    private TokenAwardService awards;
    private TokenNotifier notifier;
    private HouseholdTokenLedgerRepo householdLedger;
    private HouseholdAccessService access;
    private TokenEvaluationService evaluator;

    @BeforeEach
    void setUpEvaluator() {
        householdCriteria = mock(HouseholdTokenCriteria.class);
        userCriteria = mock(UserTokenCriteria.class);
        awards = mock(TokenAwardService.class);
        notifier = mock(TokenNotifier.class);
        householdLedger = mock(HouseholdTokenLedgerRepo.class);
        access = mock(HouseholdAccessService.class);
        evaluator = new TokenEvaluationService(householdCriteria, userCriteria, awards, notifier, householdLedger, access);
    }

    @Test
    void aNonMembersActionEarnsTheHouseholdNothing() {
        when(access.canReadHousehold(ME, HH)).thenReturn(false);
        evaluator.evaluate(TokenEvent.household(TokenEventType.DRILL_COMPLETED, ME, HH, "go-bag"));
        verifyNoInteractions(householdCriteria, awards, notifier);
    }

    @Test
    void aMetHouseholdTokenIsAwardedAndNotifiedOnlyWhenNew() {
        when(access.canReadHousehold(ME, HH)).thenReturn(true);
        when(householdCriteria.met(HH)).thenReturn(EnumSet.of(TokenKey.DRILL_CREW, TokenKey.MEETING_POINT));
        HouseholdTokenLedger drill = new HouseholdTokenLedger();
        when(awards.awardHousehold(eq(HH), eq(TokenKey.DRILL_CREW), eq(ME), anyString(), any(), any()))
                .thenReturn(Optional.of(drill));
        when(awards.awardHousehold(eq(HH), eq(TokenKey.MEETING_POINT), eq(ME), anyString(), any(), any()))
                .thenReturn(Optional.empty());   // already held

        evaluator.evaluate(TokenEvent.household(TokenEventType.DRILL_COMPLETED, "Ana@X.com", HH, "go-bag"));

        verify(notifier, times(1)).householdAwarded(drill);
        verify(awards, never()).awardHousehold(any(), eq(TokenKey.HOUSEHOLD_READY), any(), any(), any(), any());
    }

    @Test
    void householdReadyFollowsFromHoldingItsThreeParts() {
        when(access.canReadHousehold(ME, HH)).thenReturn(true);
        when(householdCriteria.met(HH)).thenReturn(EnumSet.noneOf(TokenKey.class));
        when(householdLedger.existsByHouseholdIdAndTokenKey(eq(HH), anyString())).thenReturn(true);
        when(awards.awardHousehold(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        evaluator.evaluate(TokenEvent.household(TokenEventType.SUPPLIES_CHANGED, ME, HH, null));

        verify(awards).awardHousehold(eq(HH), eq(TokenKey.HOUSEHOLD_READY), eq(ME), eq("SUPPLIES_CHANGED"), any(), any());
    }

    @Test
    void aCriteriaFailureIsSwallowed() {
        when(access.canReadHousehold(ME, HH)).thenReturn(true);
        when(householdCriteria.met(HH)).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> evaluator.evaluate(TokenEvent.household(TokenEventType.DRILL_COMPLETED, ME, HH, "x")))
                .doesNotThrowAnyException();
        verifyNoInteractions(notifier);
    }

    @Test
    void userCandidatesAwardTheNamedPersonAndSkipWrongScopes() {
        when(userCriteria.candidates(any())).thenReturn(List.of(
                new UserTokenCriteria.Candidate("bo@x.com", TokenKey.TRUSTED_ANSWER, "ASK_ANSWER_ACCEPTED", "5", Map.of()),
                new UserTokenCriteria.Candidate(ME, TokenKey.DRILL_CREW, "X", "1", Map.of())));   // household key: ignored
        UserTokenLedger row = new UserTokenLedger();
        when(awards.awardUser(eq("bo@x.com"), eq(TokenKey.TRUSTED_ANSWER), any(), any(), any())).thenReturn(Optional.of(row));

        evaluator.evaluate(TokenEvent.user(TokenEventType.ASK_ANSWER_ACCEPTED, ME, 5L));

        verify(notifier).userAwarded(row);
        verify(awards, never()).awardUser(any(), eq(TokenKey.DRILL_CREW), any(), any(), any());
    }

    // ── Notifier ────────────────────────────────────────────────────────────

    @Test
    void notifierWritesOnlyOnLaneB() {
        NotificationService notifications = mock(NotificationService.class);
        PushPolicyService policy = mock(PushPolicyService.class);
        TokenNotifier n = new TokenNotifier(notifications, policy, mock(GroupRepo.class), new ObjectMapper(),
                TransactionOperations.withoutTransaction());
        UserTokenLedger award = new UserTokenLedger();
        award.setId(12L);
        award.setUserEmail(ME);
        award.setTokenKey("HELPFUL_QUESTION");

        when(policy.evaluate(ME, Category.TOKEN_UNLOCKED, null)).thenReturn(Lane.C);   // inbox switched off
        n.userAwarded(award);
        when(policy.evaluate(ME, Category.TOKEN_UNLOCKED, null)).thenReturn(Lane.DROP);
        n.userAwarded(award);
        verify(notifications, never()).logInboxOnly(any(), any(), any(), any(), any(), any(), any(), any());

        when(policy.evaluate(ME, Category.TOKEN_UNLOCKED, null)).thenReturn(Lane.B);
        n.userAwarded(award);
        verify(notifications).logInboxOnly(eq(ME), eq("token_unlocked"), eq("New token: First Question"),
                eq("You asked your first preparedness or local safety question."), eq("user:12"), eq("/profile?tab=tokens"),
                any(), eq(Category.TOKEN_UNLOCKED));
    }

    @Test
    void aHouseholdAwardNotifiesEachMemberOnce() {
        NotificationService notifications = mock(NotificationService.class);
        PushPolicyService policy = mock(PushPolicyService.class);
        GroupRepo groups = mock(GroupRepo.class);
        Group g = new Group();
        g.setGroupId(HH);
        g.setMemberEmails(new ArrayList<>(List.of("ana@x.com", "Dana@x.com", "ANA@x.com ")));
        when(groups.findById(HH)).thenReturn(Optional.of(g));
        when(policy.evaluate(anyString(), eq(Category.TOKEN_UNLOCKED), any())).thenReturn(Lane.B);
        TokenNotifier n = new TokenNotifier(notifications, policy, groups, new ObjectMapper(),
                TransactionOperations.withoutTransaction());
        HouseholdTokenLedger award = new HouseholdTokenLedger();
        award.setId(3L);
        award.setHouseholdId(HH);
        award.setTokenKey("DRILL_CREW");

        n.householdAwarded(award);

        verify(notifications).logInboxOnly(eq("ana@x.com"), any(), any(), any(), eq("household:3"), any(), any(), any());
        verify(notifications).logInboxOnly(eq("dana@x.com"), any(), any(), any(), eq("household:3"), any(), any(), any());
        verify(notifications, times(2)).logInboxOnly(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
