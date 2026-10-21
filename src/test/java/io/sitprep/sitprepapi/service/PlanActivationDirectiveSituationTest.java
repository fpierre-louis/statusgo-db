package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.dto.PlanActivationDtos;

import io.sitprep.sitprepapi.domain.*;
import io.sitprep.sitprepapi.dto.AlertCardDto;
import io.sitprep.sitprepapi.dto.AlertFeedResponse;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActiveSituationDto;
import io.sitprep.sitprepapi.dto.PlanActivationDtos.ActivationDetailDto;
import io.sitprep.sitprepapi.dto.PublicActivationDtos.PublicActivationDto;
import io.sitprep.sitprepapi.dto.PublicActivationDtos.PublicSituationDto;
import io.sitprep.sitprepapi.repo.*;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * The Active Situation, through the REAL {@link ActivationDirectiveResolver}.
 *
 * <p>Every other PlanActivation* suite mocks the resolver, which is how D6
 * shipped: a mocked resolver returns null, the service falls back to
 * UNVERIFIED, and nobody ever saw what a plain activation looks like once the
 * real resolver has looked at an empty feed. Here only the alert feed is a
 * stub, so the resolver's answer and the service's use of it are tested
 * together — for the household and for an anonymous link holder, who has no
 * live alert layer of their own and so believes whatever this says.
 */
class PlanActivationDirectiveSituationTest {

    private static final String ACT_ID = "act-dir-1";
    private static final String OWNER = "owner@x.com";

    private PlanActivationRepo activationRepo;
    private AlertFeedService feed;
    private AlertIngestService ingest;
    private PlanActivationService service;
    private GroupRepo groupRepo;

    @BeforeEach
    void setUp() {
        activationRepo = mock(PlanActivationRepo.class);
        groupRepo = mock(GroupRepo.class);
        MeetingPlaceRepo meetingPlaceRepo = mock(MeetingPlaceRepo.class);
        EvacuationPlanRepo evacuationPlanRepo = mock(EvacuationPlanRepo.class);
        feed = mock(AlertFeedService.class);
        ingest = mock(AlertIngestService.class);

        service = new PlanActivationService(activationRepo, mock(PlanActivationAckRepo.class),
                mock(UserInfoRepo.class), meetingPlaceRepo, evacuationPlanRepo,
                mock(OriginLocationRepo.class), mock(EmergencyContactGroupRepo.class),
                mock(EmergencyContactRepo.class), mock(WebSocketMessageSender.class),
                groupRepo, mock(NotificationService.class),
                mock(HouseholdAccessService.class), mock(HouseholdResolver.class),
                mock(GoBagService.class), mock(HouseholdEventService.class), mock(GroupService.class),
                new ActivationDirectiveResolver(feed, ingest));

        MeetingPlace m = new MeetingPlace();
        m.setId(4242L);
        m.setName("Oak Park");
        m.setAddress("12 Oak St");
        m.setLat(40.5);
        m.setLng(-111.9);
        EvacuationPlan e = new EvacuationPlan();
        e.setId(9191L);
        e.setName("Primary evac");
        e.setDestination("Aunt Rosa's");
        e.setLat(40.6);
        e.setLng(-111.8);
        when(meetingPlaceRepo.findById(4242L)).thenReturn(Optional.of(m));
        when(evacuationPlanRepo.findById(9191L)).thenReturn(Optional.of(e));
    }

    /** "Activate my plan" with no alert behind it — the common case. */
    private PlanActivation plainActivation() {
        PlanActivation a = new PlanActivation();
        a.setId(ACT_ID);
        a.setOwnerEmail(OWNER);
        a.setOwnerName("Dana");
        a.setMeetingPlaceId(4242L);
        a.setEvacPlanId(9191L);
        a.setMeetingMode("PRIMARY");
        a.setEvacMode("PRIMARY");
        a.setOperationalMode("GATHERING");
        a.setMovementDirective("none");
        a.setLat(40.5);
        a.setLng(-111.9);
        a.setActivatedAt(Instant.now());
        a.setExpiresAt(Instant.now().plus(3, ChronoUnit.HOURS));
        return a;
    }

    /** Activated under an official evacuation order. */
    private PlanActivation evacuationActivation() {
        PlanActivation a = plainActivation();
        a.setOperationalMode("EVACUATING");
        a.setMovementDirective("evacuate");
        a.setGoverningAlertSource("NWS");
        a.setGoverningAlertId("EVAC-ORIGINAL");
        a.setGoverningAlertEvent("Evacuation Immediate");
        a.setGoverningAlertHeadline("Evacuate now");
        a.setGoverningAlertLifecycleState("active");
        return a;
    }

    /** A live card matched to the point by its zone — containment-quality, so it may govern. */
    private AlertCardDto card(String id, String event, String directive) {
        return card(id, event, directive, "zone");
    }

    private AlertCardDto card(String id, String event, String directive, String matchType) {
        return new AlertCardDto(id, "NWS", event, event, "warning", false, false,
                event + " in effect", null, List.of(), null, "active", List.of(), null,
                null, new AlertCardDto.Location("Area", matchType, null, null, null),
                0, null, Instant.now().plus(2, ChronoUnit.HOURS).toString(),
                null, null,
                new AlertCardDto.Safety(null, null, null, List.of(), directive, null));
    }

    private void feedReturns(AlertCardDto... cards) {
        when(feed.feedFor(anyDouble(), anyDouble()))
                .thenReturn(new AlertFeedResponse(List.of(cards), null));
    }

    private void stored(PlanActivation a) {
        when(activationRepo.findById(ACT_ID)).thenReturn(Optional.of(a));
    }

    private ActiveSituationDto ownerSituation() {
        Object dto = service.getActivation(ACT_ID, OWNER).orElseThrow();
        assertThat(dto).isInstanceOf(ActivationDetailDto.class);
        return ((ActivationDetailDto) dto).activeSituation();
    }

    private PublicSituationDto publicSituation() {
        Object dto = service.getActivation(ACT_ID, null).orElseThrow();
        assertThat(dto).isInstanceOf(PublicActivationDto.class);
        return ((PublicActivationDto) dto).activeSituation();
    }

    // ── WHO SAYS SO ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a household's own evacuation never claims official guidance")
    void householdEvacuationIsAttributedToThePlan() {
        PlanActivation a = plainActivation();
        a.setOperationalMode("EVACUATING");
        stored(a);
        feedReturns();

        ActiveSituationDto s = ownerSituation();
        assertThat(s.primaryActionKind()).isEqualTo("evacuate");
        assertThat(s.suppressedAction()).isEqualTo("Navigate to meeting spot");
        assertThat(s.suppressedReason())
                .startsWith("This plan goes to the destination.")
                .doesNotContainIgnoringCase("official");
        assertThat(publicSituation().suppressedReason()).doesNotContainIgnoringCase("official");
    }

    @Test
    @DisplayName("an evacuation an official alert ordered says officials said so")
    void officialEvacuationIsAttributedToOfficials() {
        stored(evacuationActivation());
        feedReturns(card("EVAC-ORIGINAL", "Evacuation Immediate", "evacuate"));

        ActiveSituationDto s = ownerSituation();
        assertThat(s.movementDirective()).isEqualTo("evacuate");
        assertThat(s.suppressedReason()).startsWith("Officials say to evacuate.");
    }

    @Test
    @DisplayName("the push says what to do and where, and credits officials only for their order")
    void pushStatesTheActionWithItsSource() {
        assertThat(service.pushActionSentence(plainActivation())).isEqualTo("Meet at Oak Park.");

        PlanActivation householdEvac = plainActivation();
        householdEvac.setOperationalMode("EVACUATING");
        assertThat(service.pushActionSentence(householdEvac)).isEqualTo("Go to Primary evac.");

        assertThat(service.pushActionSentence(evacuationActivation()))
                .isEqualTo("Officials say to evacuate. Go to Primary evac.");

        PlanActivation stayHome = plainActivation();
        stayHome.setOperationalMode("SHELTERING");
        assertThat(service.pushActionSentence(stayHome)).isEqualTo("Stay home.");
    }

    // ── MEET, THEN LEAVE + CHANGING A LIVE PLAN (evacuation plan clarity) ──

    private PlanActivation meetThenLeave() {
        PlanActivation a = plainActivation();
        a.setRoutePlan("MEET_THEN_GO");
        a.setStage("MEETING");
        return a;
    }

    @Test
    @DisplayName("meet, then leave: step 1 says meet first and names where everyone goes next")
    void meetThenLeaveFirstStep() {
        stored(meetThenLeave());
        feedReturns();

        ActiveSituationDto s = ownerSituation();
        assertThat(s.primaryAction()).isEqualTo("Meet at the meeting spot first");
        assertThat(s.primaryActionKind()).isEqualTo("meet");
        assertThat(s.nextAction()).isEqualTo("Then go to Primary evac together");
        assertThat(s.stage()).isEqualTo("MEETING");
        assertThat(publicSituation().nextAction()).isEqualTo("Then go to Primary evac together");
        assertThat(service.pushActionSentence(meetThenLeave()))
                .isEqualTo("Meet at Oak Park first, then go to Primary evac.");
    }

    @Test
    @DisplayName("the owner's \"We've met, now go\" moves the plan to the destination")
    void ownerMovesToSecondStep() {
        PlanActivation a = meetThenLeave();
        stored(a);
        feedReturns();
        when(activationRepo.save(any(PlanActivation.class))).thenAnswer(i -> i.getArgument(0));

        var detail = service.changeActivation(ACT_ID,
                new PlanActivationDtos.ChangeActivationRequest(null, null, null, "GOING"), OWNER);

        assertThat(a.getStage()).isEqualTo("GOING");
        assertThat(detail.activeSituation().primaryActionKind()).isEqualTo("evacuate");
        assertThat(detail.activeSituation().suppressedReason())
                .isEqualTo("Everyone's together. Use the meeting spot if anyone gets separated.");
        assertThat(detail.viewerCanChange()).isTrue();
    }

    @Test
    @DisplayName("a household member who is not an admin cannot change the plan")
    void memberCannotChange() {
        stored(meetThenLeave());
        assertThatThrownBy(() -> service.changeActivation(ACT_ID,
                new PlanActivationDtos.ChangeActivationRequest(null, null, null, "GOING"), "member@x.com"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("an ended plan cannot be changed")
    void endedPlanCannotChange() {
        PlanActivation a = meetThenLeave();
        a.setEndedAt(Instant.now());
        stored(a);
        assertThatThrownBy(() -> service.changeActivation(ACT_ID,
                new PlanActivationDtos.ChangeActivationRequest("LEAVE", null, null, null), OWNER))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    @DisplayName("switching to stay home drops the destination")
    void switchToStayHome() {
        PlanActivation a = meetThenLeave();
        stored(a);
        feedReturns();
        when(activationRepo.save(any(PlanActivation.class))).thenAnswer(i -> i.getArgument(0));

        service.changeActivation(ACT_ID,
                new PlanActivationDtos.ChangeActivationRequest("STAY", null, null, null), OWNER);

        // "Stay home" is the whole instruction: no place rides along (owner, 2026-10-21).
        assertThat(a.getEvacPlanId()).isNull();
        assertThat(a.getMeetingPlaceId()).isNull();
        assertThat(a.getRoutePlan()).isNull();
        assertThat(service.pushActionSentence(a)).isEqualTo("Stay home.");
    }

    @Test
    @DisplayName("the Live Activity reads: step line + id, and who may change it")
    void liveActivityReads() {
        PlanActivation a = meetThenLeave();
        a.setHouseholdId("hh-1");
        io.sitprep.sitprepapi.domain.Group hh = new io.sitprep.sitprepapi.domain.Group();
        hh.setGroupId("hh-1");
        hh.setGroupType("Household");
        hh.setOwnerEmail(OWNER);
        when(groupRepo.findById("hh-1")).thenReturn(Optional.of(hh));
        when(activationRepo.findActiveByOwnerEmail(any(), any())).thenReturn(List.of(a));
        when(activationRepo.findLiveByHouseholdId(any(), any())).thenReturn(List.of(a));
        when(activationRepo.findLiveForHousehold(any(), any())).thenCallRealMethod();

        var step = service.currentStep("hh-1").orElseThrow();
        assertThat(step.line()).isEqualTo("Meet at Oak Park first, then go to Primary evac.");
        assertThat(step.activationId()).isEqualTo(ACT_ID);
        assertThat(service.canChangePlan(OWNER, "hh-1")).isTrue();
        assertThat(service.canChangePlan("member@x.com", "hh-1")).isFalse();
        assertThat(service.canChangePlan(OWNER, "nope")).isFalse();
    }

    @Test
    @DisplayName("meet up at a destination is one place: the old meeting spot drops")
    void meetUpAtDestinationDropsTheSpot() {
        PlanActivation a = meetThenLeave();
        stored(a);
        feedReturns();
        when(activationRepo.save(any(PlanActivation.class))).thenAnswer(i -> i.getArgument(0));

        service.changeActivation(ACT_ID,
                new PlanActivationDtos.ChangeActivationRequest("LEAVE", null, null, null), OWNER);

        assertThat(a.getMeetingPlaceId()).isNull();
        assertThat(a.getEvacPlanId()).isEqualTo(9191L);
        assertThat(service.pushActionSentence(a)).isEqualTo("Go to Primary evac.");
    }

    @Test
    @DisplayName("meet up at a destination says meet, not go, and carries no meeting spot")
    void meetUpAtDestinationReadsAsMeetUp() {
        PlanActivation a = meetThenLeave();
        stored(a);
        feedReturns();
        when(activationRepo.save(any(PlanActivation.class))).thenAnswer(i -> i.getArgument(0));

        var detail = service.changeActivation(ACT_ID,
                new PlanActivationDtos.ChangeActivationRequest("MEET_AT_DESTINATION", null, null, null), OWNER);

        assertThat(a.getRoutePlan()).isEqualTo("MEET_AT_DESTINATION");
        assertThat(a.getMeetingPlaceId()).isNull();
        assertThat(detail.activeSituation().primaryAction()).isEqualTo("Meet at the destination");
        assertThat(detail.activeSituation().suppressedReason()).isNull();
        assertThat(service.pushActionSentence(a)).isEqualTo("Meet at Primary evac.");
    }

    @Test
    @DisplayName("a household's own stay never mentions a meeting spot, even on an old row that kept one")
    void householdStayHasNoMeetingSpotReason() {
        PlanActivation a = plainActivation();
        a.setOperationalMode("SHELTERING");
        stored(a);
        feedReturns();

        ActiveSituationDto s = ownerSituation();
        assertThat(s.primaryAction()).isEqualTo("Stay home");
        assertThat(s.suppressedAction()).isNull();
        assertThat(s.suppressedReason()).isNull();
    }

    // ── D6 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a plain activation with nothing live: no invented official guidance, plan navigation intact")
    void plainActivationIsNotNarrowed() {
        stored(plainActivation());
        feedReturns();

        ActiveSituationDto s = ownerSituation();
        assertThat(s.movementDirective()).isEqualTo("none");
        assertThat(s.currentDirective()).isEqualTo("none");
        assertThat(s.directiveChanged()).isFalse();
        assertThat(s.primaryAction()).isNotEqualTo("Follow official instructions");
        assertThat(s.primaryActionKind()).isNotEqualTo("official");
        assertThat(s.suppressedAction()).isNull();
        assertThat(s.governingAlert()).as("no alert ever governed this").isNull();
        // CURRENT, not SUPERSEDED_UNRESOLVED: nothing lapsed, so the client's
        // "no longer in force" record has nothing to render.
        assertThat(s.directiveStatus()).isEqualTo("CURRENT");

        PublicSituationDto p = publicSituation();
        assertThat(p.movementDirective()).isEqualTo("none");
        assertThat(p.directiveChanged()).isFalse();
        assertThat(p.primaryAction()).isNotEqualTo("Follow official instructions");
        assertThat(p.suppressedAction()).isNull();
        assertThat(p.governingAlert()).isNull();
        assertThat(p.directiveStatus()).isEqualTo("CURRENT");
    }

    @Test
    @DisplayName("activated under a Watch with no directive that has since lapsed: none, the lapse is shown, navigation intact")
    void lapsedNoDirectiveAlertIsShownButDoesNotNarrow() {
        PlanActivation a = plainActivation();
        a.setGoverningAlertSource("NWS");
        a.setGoverningAlertId("WATCH-1");
        a.setGoverningAlertEvent("Flash Flood Watch");
        a.setGoverningAlertHeadline("Flash Flood Watch in effect");
        a.setGoverningAlertLifecycleState("active");
        stored(a);
        feedReturns();

        ActiveSituationDto s = ownerSituation();
        assertThat(s.movementDirective()).isEqualTo("none");
        assertThat(s.directiveChanged()).isFalse();
        assertThat(s.suppressedAction()).isNull();
        // A real alert WAS stored, so reporting its lapse is true.
        assertThat(s.directiveStatus()).isEqualTo("SUPERSEDED_UNRESOLVED");
        assertThat(s.governingAlert().id()).isEqualTo("WATCH-1");
        assertThat(s.governingAlert().lifecycleState()).isEqualTo("expired");

        PublicSituationDto p = publicSituation();
        assertThat(p.movementDirective()).isEqualTo("none");
        assertThat(p.suppressedAction()).isNull();
        assertThat(p.governingAlert().id()).isEqualTo("WATCH-1");
    }

    @Test
    @DisplayName("a lapsed evacuation still narrows conservatively, for owner and link holder")
    void lapsedDirectiveStillNarrows() {
        stored(evacuationActivation());
        feedReturns();

        ActiveSituationDto s = ownerSituation();
        assertThat(s.movementDirective()).isEqualTo("follow_official_instruction");
        assertThat(s.directiveStatus()).isEqualTo("SUPERSEDED_UNRESOLVED");
        assertThat(s.directiveChanged()).isTrue();
        assertThat(s.suppressedAction()).isEqualTo("Plan navigation");
        assertThat(s.governingAlert().lifecycleState()).isEqualTo("expired");

        PublicSituationDto p = publicSituation();
        assertThat(p.movementDirective()).isEqualTo("follow_official_instruction");
        assertThat(p.suppressedAction()).isEqualTo("Plan navigation");
    }

    // ── R5 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("disagreeing live directives: evacuate governs and both DTOs say they conflict")
    void conflictingDirectivesSurface() {
        stored(plainActivation());
        feedReturns(
                card("SHELTER-1", "Shelter In Place Warning", "shelter_in_place"),
                card("EVAC-1", "Evacuation Immediate", "evacuate"));

        ActiveSituationDto s = ownerSituation();
        assertThat(s.movementDirective()).isEqualTo("evacuate");
        assertThat(s.governingAlert().id()).isEqualTo("EVAC-1");
        assertThat(s.directiveConflicting()).isTrue();

        PublicSituationDto p = publicSituation();
        assertThat(p.movementDirective()).isEqualTo("evacuate");
        assertThat(p.directiveConflicting()).isTrue();
    }

    @Test
    @DisplayName("one live directive: not conflicting")
    void singleDirectiveIsNotConflicting() {
        stored(evacuationActivation());
        feedReturns(card("EVAC-ORIGINAL", "Evacuation Immediate", "evacuate"));

        assertThat(ownerSituation().directiveConflicting()).isFalse();
        assertThat(publicSituation().directiveConflicting()).isFalse();
    }

    // ── R4 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an evacuation polygon 40 mi away never reaches the household or the link holder")
    void distantPolygonDoesNotGovernTheSituation() {
        stored(plainActivation());
        // First vertex ~40 mi north of the household: matchTypeFor says POLYGON.
        when(ingest.getSnapshot()).thenReturn(new AlertIngestService.Snapshot(List.of(
                TestAlerts.nws("Evacuation Immediate").id("FAR-EVAC").geometry(java.util.Map.of(
                        "type", "Polygon", "coordinates", List.of(List.of(
                                List.of(-111.95, 41.08), List.of(-111.85, 41.08), List.of(-111.85, 41.2),
                                List.of(-111.95, 41.2), List.of(-111.95, 41.08))))).build()),
                Instant.now(), Instant.now()));
        feedReturns(card("FAR-EVAC", "Evacuation Immediate", "evacuate", "polygon"));

        ActiveSituationDto s = ownerSituation();
        assertThat(s.movementDirective()).isEqualTo("none");
        assertThat(s.primaryActionKind()).isNotEqualTo("evacuate");
        assertThat(s.suppressedAction()).isNull();
        assertThat(s.governingAlert()).isNull();

        PublicSituationDto p = publicSituation();
        assertThat(p.movementDirective()).isEqualTo("none");
        assertThat(p.suppressedAction()).isNull();
        assertThat(p.governingAlert()).isNull();
    }
}
