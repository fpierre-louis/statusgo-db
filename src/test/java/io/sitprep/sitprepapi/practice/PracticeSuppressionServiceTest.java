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
    void checkInWaits() {
        active(ActiveResponseResolver.Kind.CHECK_IN, ReadinessAction.OPEN_CHECK_IN, Map.of());
        assertThat(service.check(HH, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.CHECK_IN);
    }

    @Test
    void anOfficialAlertNudgesInsteadOfWaiting() {
        // EXEC-H1: the resolver no longer returns an alert, so check() is calm,
        // and headsUp() carries the alert in Practice's words.
        when(risk.resolveFor(household)).thenReturn(new io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto(
                "household_zip", "UT", "Utah", java.util.List.of(), java.util.List.of(),
                java.util.List.of(new io.sitprep.sitprepapi.dto.RiskProfileDtos.ActiveAlertDto("a1", "NWS", "Moderate",
                        "flood", "Flood Watch issued", "Area", "Be ready", null, null, "Flood Watch")),
                NOW, "v"));
        assertThat(service.check(HH, ME)).isEmpty();
        var h = service.headsUp(HH).orElseThrow();
        assertThat(h.title()).isEqualTo("Flood Watch near home");
        assertThat(h.detail()).endsWith("Just here to practice? Keep going.");
    }

    @Test
    void lockdownNearTheCallerWaitsEvenWithoutAHousehold() {
        when(concealment.isConcealmentSensitiveFor(any())).thenReturn(true);
        assertThat(service.check(null, ME)).map(PracticeSuppressionService.Suppression::reason).contains(Reason.LOCKDOWN);
    }

    @Test
    void unreadableRiskProfileFailsClosed() {
        when(risk.resolveFor(household)).thenThrow(new IllegalStateException("alerts feed down"));
        var s = service.check(HH, ME).orElseThrow();
        assertThat(s.reason()).isEqualTo(Reason.UNVERIFIED);
        // Truthful: an unknown is never presented as a known emergency, and has no live link to offer.
        assertThat(s.title()).isEqualTo("Practice is paused for a moment");
        assertThat(s.detail()).isEqualTo("SitPrep can't confirm the current safety situation right now.");
        assertThat(s.action()).isNull();
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
