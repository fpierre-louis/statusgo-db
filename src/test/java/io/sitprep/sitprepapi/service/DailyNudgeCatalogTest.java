package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers.Condition;
import io.sitprep.sitprepapi.service.DailyNudgeCatalog.Nudge;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXEC-3B: every nudge points somewhere real, every slot×condition has a row,
 * and the selection rules hold (event days, 14-day no-repeat, neighbour cap).
 */
class DailyNudgeCatalogTest {

    private final DailyNudgeCatalog catalog = new DailyNudgeCatalog();

    // Drill ids verified against Status Now/src/me/challenges/challenges.js on
    // 2026-10-01, and routes verified against the FE router.
    private static final Set<String> KNOWN_DRILLS = Set.of(
            "meeting-spots", "go-bag", "meet-a-neighbor", "poweroutage-blackout-test", "flood-underpass-scan",
            "flashlights", "water-3day", "blizzard-car-kit-mini-pack", "blizzard-warm-room-sprint",
            "wildfire-smoke-go-hunt", "earthquake-drop-cover-relay", "exit-drill", "smoke-alarms", "out-of-town",
            "utility-shutoffs", "first-aid", "contact-tree", "medical-needs", "emergency-roles", "key-documents",
            "evac-route");
    private static final Set<String> KNOWN_ROUTES = Set.of(
            "/hazards?focus=weather", "/hazards?focus=air", "/playbooks/power-outage", "/blizzard-prep");

    @Test
    void everyDestinationIsReal() {
        for (Nudge n : catalog.all()) {
            String d = n.destination();
            boolean ok = d.startsWith("/practice/drill/")
                    ? KNOWN_DRILLS.contains(d.substring("/practice/drill/".length()))
                    : KNOWN_ROUTES.contains(d);
            assertThat(ok).as("%s -> %s", n.id(), d).isTrue();
        }
    }

    @Test
    void everySlotAndConditionHasAMatrixRow() {
        for (Condition c : Condition.values()) {
            for (BriefSlot s : BriefSlot.values()) {
                assertThat(catalog.matrixRow(c, s)).as("%s/%s", c, s).isNotNull();
            }
        }
    }

    @Test
    void nudgesAreOffersNotOrders() {
        for (Nudge n : catalog.all()) {
            String t = n.text().toLowerCase();
            assertThat(t).as(n.id()).doesNotContain("you should").doesNotContain("you must");
        }
    }

    @Test
    void eventWindowsFor2026() {
        // Great ShakeOut: third Thursday of October = Oct 15, mornings only.
        assertThat(catalog.activeEvent(LocalDate.of(2026, 10, 15), BriefSlot.MORNING).id()).isEqualTo("EV-SHAKEOUT");
        assertThat(catalog.activeEvent(LocalDate.of(2026, 10, 15), BriefSlot.EVENING)).isNull();
        assertThat(catalog.activeEvent(LocalDate.of(2026, 10, 8), BriefSlot.MORNING)).isNull();
        // Fire Prevention Week: the Sunday-Saturday week containing Oct 9 = Oct 4-10, evenings.
        assertThat(catalog.activeEvent(LocalDate.of(2026, 10, 4), BriefSlot.EVENING).id()).isEqualTo("EV-FIREWEEK");
        assertThat(catalog.activeEvent(LocalDate.of(2026, 10, 10), BriefSlot.EVENING).id()).isEqualTo("EV-FIREWEEK");
        assertThat(catalog.activeEvent(LocalDate.of(2026, 10, 11), BriefSlot.EVENING)).isNull();
        // Preparedness Month: September mornings.
        assertThat(catalog.activeEvent(LocalDate.of(2026, 9, 20), BriefSlot.MORNING).id()).isEqualTo("EV-PREPMONTH");
    }

    @Test
    void theTipIsAFormulaNotAHistory() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.MORNING, day, "40.4|-111.9").id())
                .isEqualTo(catalog.forView(Condition.FAIR, BriefSlot.MORNING, day, "40.4|-111.9").id());
    }

    @Test
    void aSlotNeverRepeatsWithinFourteenDays() {
        for (BriefSlot slot : BriefSlot.values()) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 14; i++) {
                String id = catalog.forView(Condition.FAIR, slot, LocalDate.of(2026, 11, 2).plusDays(i), "40.4|-111.9").id();
                assertThat(seen.add(id)).as("%s repeated %s within 14 days", slot, id).isTrue();
            }
        }
    }

    @Test
    void theThreeSlotsDifferOnTheSameDay() {
        LocalDate day = LocalDate.of(2026, 11, 2);
        Set<String> ids = new HashSet<>();
        for (BriefSlot slot : BriefSlot.values()) ids.add(catalog.forView(Condition.FAIR, slot, day, "40.4|-111.9").id());
        assertThat(ids).hasSize(3);
    }

    @Test
    void weatherThatMattersGetsItsMatrixRow() {
        assertThat(catalog.forView(Condition.WIND, BriefSlot.MIDDAY, LocalDate.of(2026, 10, 15), "x").id()).isEqualTo("D-WIND");
    }

    @Test
    void eventsShowOnTheirFirstDayAndWeeklyNotDaily() {
        // ShakeOut 2026: Thursday Oct 15, mornings.
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.MORNING, LocalDate.of(2026, 10, 15), "x").id()).isEqualTo("EV-SHAKEOUT");
        // Fire Prevention Week 2026 starts Sunday Oct 4: that evening, not Monday's.
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.EVENING, LocalDate.of(2026, 10, 4), "x").id()).isEqualTo("EV-FIREWEEK");
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.EVENING, LocalDate.of(2026, 10, 5), "x").id()).isNotEqualTo("EV-FIREWEEK");
        // Preparedness Month: Sept 1 and 8, not Sept 2.
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.MORNING, LocalDate.of(2026, 9, 1), "x").id()).isEqualTo("EV-PREPMONTH");
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.MORNING, LocalDate.of(2026, 9, 8), "x").id()).isEqualTo("EV-PREPMONTH");
        assertThat(catalog.forView(Condition.FAIR, BriefSlot.MORNING, LocalDate.of(2026, 9, 2), "x").id()).isNotEqualTo("EV-PREPMONTH");
        // Bad weather beats the event.
        assertThat(catalog.forView(Condition.WIND, BriefSlot.MORNING, LocalDate.of(2026, 10, 15), "x").id()).isEqualTo("M-WIND");
    }
}
