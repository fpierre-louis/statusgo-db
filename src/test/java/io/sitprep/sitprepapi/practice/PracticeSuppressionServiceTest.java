package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.practice.PracticeSuppressionService.Reason;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver;
import io.sitprep.sitprepapi.readiness.ActiveResponseResolver.ActiveResponse;
import io.sitprep.sitprepapi.readiness.ReadinessAction;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.ConcealmentSafetyService;
import io.sitprep.sitprepapi.service.RiskProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Active response suppresses Practice; an unreadable signal fails closed. */
class PracticeSuppressionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");
    private static final String HH = "hh-1";
    private static final String ME = "adult@example.com";

    private GroupRepo groupRepo;
    private ActiveResponseResolver resolver;
    private RiskProfileService risk;
    private UserInfoRepo users;
    private ConcealmentSafetyService concealment;
    private PracticeSuppressionService service;
    private Group household;

    @BeforeEach
    void setUp() {
        groupRepo = mock(GroupRepo.class);
        resolver = mock(ActiveResponseResolver.class);
        risk = mock(RiskProfileService.class);
        users = mock(UserInfoRepo.class);
        concealment = mock(ConcealmentSafetyService.class);
        service = new PracticeSuppressionService(groupRepo, resolver, risk, users, concealment,
                Clock.fixed(NOW, ZoneOffset.UTC));

        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        when(groupRepo.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(users.findByUserEmailIgnoreCase(ME)).thenReturn(Optional.of(new UserInfo()));
    }

    private void active(ActiveResponseResolver.Kind kind, ReadinessAction action, Map<String, String> params) {
        when(resolver.resolve(eq(household), any(), eq(NOW)))
                .thenReturn(new ActiveResponse(kind, "t", "d", action, params));
    }

    @Test
    void calmMeansNoSuppression() {
        assertThat(service.check(HH, ME)).isEmpty();
    }

    @Test
    void liveActivationWaitsAndPointsAtTheRealSituation() {
        active(ActiveResponseResolver.Kind.PLAN_ACTIVATION, ReadinessAction.OPEN_ACTIVE_SITUATION,
                Map.of("activationId", "act-9"));
        var s = service.check(HH, ME).orElseThrow();
        assertThat(s.reason()).isEqualTo(Reason.PLAN_ACTIVATION);
        assertThat(s.action()).isEqualTo(ReadinessAction.OPEN_ACTIVE_SITUATION);
        assertThat(s.params()).containsEntry("activationId", "act-9");
        assertThat(s.detail()).isEqualTo(PracticeSuppressionService.WAIT);
    }

    @Test
    void checkInAndOfficialAlertWait() {
        active(ActiveResponseResolver.Kind.CHECK_IN, ReadinessAction.OPEN_CHECK_IN, Map.of());
        assertThat(service.check(HH, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.CHECK_IN);

        active(ActiveResponseResolver.Kind.OFFICIAL_ALERT, ReadinessAction.OPEN_ACTIVE_ALERTS, Map.of());
        assertThat(service.check(HH, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.OFFICIAL_ALERT);
    }

    @Test
    void lockdownNearTheCallerWaitsEvenWithoutAHousehold() {
        when(concealment.isConcealmentSensitiveFor(any())).thenReturn(true);
        assertThat(service.check(null, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.LOCKDOWN);
    }

    @Test
    void unreadableRiskProfileFailsClosed() {
        when(risk.resolveFor(household)).thenThrow(new IllegalStateException("alerts feed down"));
        assertThat(service.check(HH, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.UNVERIFIED);
    }

    @Test
    void unreadableConcealmentFailsClosed() {
        when(concealment.isConcealmentSensitiveFor(any())).thenThrow(new IllegalStateException("down"));
        assertThat(service.check(null, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.UNVERIFIED);
    }

    @Test
    void aNonHouseholdGroupContributesNoHouseholdSignal() {
        Group school = new Group();
        school.setGroupId("school-1");
        school.setGroupType("School");
        when(groupRepo.findByGroupId("school-1")).thenReturn(Optional.of(school));
        when(resolver.resolve(eq(school), any(), any()))
                .thenReturn(new ActiveResponse(ActiveResponseResolver.Kind.CHECK_IN, "t", "d", null, Map.of()));
        assertThat(service.check("school-1", ME)).isEmpty();
    }
}
