package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.service.AlertIngestService.NormalizedAlert;
import io.sitprep.sitprepapi.service.AlertSafetyPolicy.Decision;
import io.sitprep.sitprepapi.service.AlertSafetyPolicy.DispatchMode;
import io.sitprep.sitprepapi.service.AlertSafetyPolicy.GuidanceMode;
import io.sitprep.sitprepapi.service.AlertSafetyPolicy.MovementDirective;
import io.sitprep.sitprepapi.util.Vtec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 (handoff §27): the safety policy reads the message's OWN P-VTEC.
 *
 * <p>The defect the C2 review found: NWS ships the cancellation of a warning
 * as a CAP {@code Update} (not a {@code Cancel}) whose only VTEC entry is
 * {@code CAN}, and it keeps the original end time — so nothing CAP-shaped says
 * "over". Before P1 that message was {@code critical_push}-eligible and carried
 * SitPrep's shelter steps. VTEC is the issuer's own statement that the event
 * ended, so it is a lifecycle block like {@code AllClear} or a past end.</p>
 *
 * <p>Fixed clock: every alert ends at {@link #FUTURE}, far past both
 * {@link #NOW} and the wall clock {@code evaluate} reads, so no case here can
 * pass by having quietly expired.</p>
 */
class AlertVtecLifecyclePolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String FUTURE = "2099-10-14T18:00:00Z";

    private static AlertDispatchService dispatch;

    @BeforeAll
    static void templates() {
        dispatch = new AlertDispatchService(null, null, null, null, null, null, null, null);
        dispatch.loadTemplates();
    }

    private static Vtec vtec(String action, String ph, String sig, String etn) {
        return new Vtec(action, "KPSR", ph, sig, etn, null, null, 2026);
    }

    /** A live, approved, critical_push Tornado Warning Update — CAP says nothing is over. */
    private static TestAlerts.Builder tornadoUpdate(Vtec... entries) {
        return TestAlerts.nws("Tornado Warning")
                .messageType("Update")
                .response("Shelter")
                .instruction("Take cover now.")
                .endsAt(FUTURE)
                .vtec(List.of(entries));
    }

    private static Decision evaluate(NormalizedAlert a) {
        return AlertSafetyPolicy.evaluate(a, dispatch.matchForAlert(a).orElseThrow());
    }

    private static void assertBlocked(Decision d) {
        assertThat(d.reason()).isEqualTo("vtec_terminal");
        assertThat(d.dispatchMode()).isEqualTo(DispatchMode.SUPPRESS);
        assertThat(d.criticalPush()).isFalse();
        assertThat(d.guidanceMode()).isEqualTo(GuidanceMode.NO_GUIDANCE);
        assertThat(d.allowsSitPrepGuidance()).isFalse();
        assertThat(d.movementDirective()).isEqualTo(MovementDirective.NONE);
        assertThat(d.capActions()).isEmpty();
    }

    @Test
    @DisplayName("all-CAN Update with a future end: blocked, no push, no guidance, cancelled")
    void allCanUpdateIsCancelled() {
        NormalizedAlert a = tornadoUpdate(vtec("CAN", "TO", "W", "0042")).build();

        // Sanity: without VTEC this exact message is a live critical push.
        assertThat(evaluate(tornadoUpdate().build()).criticalPush()).isTrue();

        Decision d = evaluate(a);
        assertBlocked(d);
        assertThat(d.asIssuedMovementDirective())
                .as("S1 D2: the record keeps what the warning said while it was live")
                .isEqualTo(MovementDirective.SHELTER_IN_PLACE);
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(a)).isEqualTo("vtec_terminal");
        assertThat(AlertDispatchService.isStillInForce(a)).as("dispatch pushEligible").isFalse();
        assertThat(AlertDerivations.lifecycleState(a, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_CANCELLED);
    }

    @Test
    @DisplayName("all-EXP Update with a future end: blocked, expired")
    void allExpUpdateIsExpired() {
        NormalizedAlert a = tornadoUpdate(vtec("EXP", "TO", "W", "0042")).build();
        assertBlocked(evaluate(a));
        assertThat(AlertDispatchService.isStillInForce(a)).isFalse();
        assertThat(AlertDerivations.lifecycleState(a, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_EXPIRED);
    }

    @Test
    @DisplayName("a CAP Alert (not just an Update) whose own VTEC is all terminal is blocked too")
    void allTerminalOnAnAlert() {
        NormalizedAlert a = tornadoUpdate(vtec("CAN", "TO", "W", "0042")).messageType("Alert").build();
        assertBlocked(evaluate(a));
        assertThat(AlertDerivations.lifecycleState(a, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_CANCELLED);
    }

    @Test
    @DisplayName("several terminal entries: blocked; the first entry names the state, as lifecycleInfo does")
    void severalTerminalEntries() {
        NormalizedAlert canFirst = tornadoUpdate(vtec("CAN", "TO", "W", "0042"), vtec("EXP", "TO", "W", "0043")).build();
        NormalizedAlert expFirst = tornadoUpdate(vtec("EXP", "TO", "W", "0043"), vtec("CAN", "TO", "W", "0042")).build();
        assertBlocked(evaluate(canFirst));
        assertBlocked(evaluate(expFirst));
        assertThat(AlertDerivations.lifecycleState(canFirst, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_CANCELLED);
        assertThat(AlertDerivations.lifecycleState(expFirst, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_EXPIRED);
    }

    @Test
    @DisplayName("mixed CAN + CON (multi-segment product): still live — part of it continues")
    void mixedCanConIsNotBlocked() {
        NormalizedAlert a = tornadoUpdate(vtec("CAN", "TO", "W", "0042"), vtec("CON", "TO", "W", "0043")).build();
        Decision d = evaluate(a);
        assertThat(d.reason()).isEqualTo("template_compatible");
        assertThat(d.criticalPush()).isTrue();
        assertThat(d.allowsSitPrepGuidance()).isTrue();
        assertThat(d.movementDirective()).isEqualTo(MovementDirective.SHELTER_IN_PLACE);
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(a)).isNull();
        assertThat(AlertDispatchService.isStillInForce(a)).isTrue();
        assertThat(AlertDerivations.lifecycleState(a, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_UPDATED);

        for (String live : List.of("NEW", "EXT", "EXA", "EXB")) {
            NormalizedAlert m = tornadoUpdate(vtec("EXP", "TO", "W", "0042"), vtec(live, "TO", "W", "0043")).build();
            assertThat(AlertSafetyPolicy.lifecycleBlockReason(m)).as(live).isNull();
        }
    }

    @Test
    @DisplayName("no VTEC: unchanged — Update is updated and live, Alert is active and live")
    void noVtecIsUnchanged() {
        NormalizedAlert update = tornadoUpdate().build();
        Decision d = evaluate(update);
        assertThat(d.reason()).isEqualTo("template_compatible");
        assertThat(d.criticalPush()).isTrue();
        assertThat(d.movementDirective()).isEqualTo(MovementDirective.SHELTER_IN_PLACE);
        assertThat(AlertDerivations.lifecycleState(update, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_UPDATED);

        NormalizedAlert alert = tornadoUpdate().messageType("Alert").build();
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(alert)).isNull();
        assertThat(AlertDerivations.lifecycleState(alert, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_ACTIVE);
    }

    @Test
    @DisplayName("UPG: the old product's own UPG is terminal (superseded); UPG + NEW is the live new product")
    void upgrade() {
        NormalizedAlert oldWatch = TestAlerts.nwsWatch("Flash Flood Watch").messageType("Update")
                .endsAt(FUTURE).vtec(List.of(vtec("UPG", "FF", "A", "0007"))).build();
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(oldWatch)).isEqualTo("vtec_terminal");
        assertThat(AlertDerivations.lifecycleState(oldWatch, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_SUPERSEDED);

        NormalizedAlert newWarning = TestAlerts.nws("Flash Flood Warning").endsAt(FUTURE)
                .vtec(List.of(vtec("UPG", "FF", "A", "0007"), vtec("NEW", "FF", "W", "0042"))).build();
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(newWarning)).isNull();
        assertThat(evaluate(newWarning).dispatchMode()).isNotEqualTo(DispatchMode.SUPPRESS);
        assertThat(AlertDerivations.lifecycleState(newWarning, NOW)).isEqualTo(AlertDerivations.LIFECYCLE_ACTIVE);
    }

    @Test
    @DisplayName("existing reasons and CAP answers keep precedence over the VTEC rule")
    void existingReasonsWin() {
        NormalizedAlert pastEnd = tornadoUpdate(vtec("EXP", "TO", "W", "0042")).endsAt("2026-01-01T00:00:00Z").build();
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(pastEnd)).isEqualTo("alert_expired");

        NormalizedAlert cancel = tornadoUpdate(vtec("CAN", "TO", "W", "0042")).messageType("Cancel").build();
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(cancel)).isEqualTo("cap_message_type_Cancel");
        assertThat(AlertDerivations.lifecycleState(cancel, NOW))
                .as("the CAP Cancel answer is unchanged")
                .isEqualTo(AlertDerivations.LIFECYCLE_SUPERSEDED);

        NormalizedAlert allClear = tornadoUpdate(vtec("CAN", "TO", "W", "0042")).response("AllClear").build();
        assertThat(AlertSafetyPolicy.lifecycleBlockReason(allClear)).isEqualTo("cap_response_all_clear");
    }

    @Test
    @DisplayName("MemberAlertAreaService: nobody is 'inside' an alert its own VTEC cancelled")
    void memberAreaSkipsVtecCancelled() {
        NormalizedAlert cancelled = tornadoUpdate(vtec("CAN", "TO", "W", "0042")).id("urn:oid:can").build();
        NormalizedAlert live = tornadoUpdate(vtec("CON", "TO", "W", "0043")).id("urn:oid:con").build();
        List<MemberAlertAreaService.AreaAlert> areas = List.of(
                new MemberAlertAreaService.AreaAlert(cancelled, null, false, Set.of("AZZ560")),
                new MemberAlertAreaService.AreaAlert(live, null, false, Set.of("AZZ560")));
        assertThat(MemberAlertAreaService.match(areas, 33.3, -110.5, Set.of("AZZ560"), NOW))
                .containsExactly("urn:oid:con");
    }
}
