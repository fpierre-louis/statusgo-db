package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.domain.AlertModeState;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import io.sitprep.sitprepapi.dto.ConditionsReading.Day;
import io.sitprep.sitprepapi.dto.ConditionsReading.Next6h;
import io.sitprep.sitprepapi.dto.ConditionsReading.Now;
import io.sitprep.sitprepapi.service.DailyBriefScheduler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EXEC-3B: when a cell gets a brief. Slots in the cell's own zone (DST-safe),
 * adaptive cadence, alert suppression, once per slot, and the off switch.
 */
class DailyBriefSchedulerTest {

    private static final ZoneId DENVER = ZoneId.of("America/Denver");
    private static final BriefCellService.Cell CELL = new BriefCellService.Cell("40.4|-111.9", 40.4, -111.9);

    private BriefCellService cells;
    private ConditionsService conditions;
    private AlertModeService alertMode;
    private final AtomicReference<Instant> now = new AtomicReference<>();
    private final AtomicReference<Now> weather = new AtomicReference<>();

    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeEach
    void setUp() {
        cells = mock(BriefCellService.class);
        conditions = mock(ConditionsService.class);
        alertMode = mock(AlertModeService.class);
        when(cells.cells()).thenReturn(List.of(CELL));
        weather.set(fair());
        when(conditions.timezoneFor(anyDouble(), anyDouble())).thenReturn("America/Denver");
        when(conditions.readingFor(anyDouble(), anyDouble(), any())).thenAnswer(i -> reading(i.getArgument(2)));
        calmMode();
    }

    private static Now fair() {
        return new Now(68, 68, 8, 247, "WSW", 12, false, false, 32, "Good");
    }

    private static Now windy() {
        return new Now(60, 60, 34, 270, "W", 50, false, false, 30, "Good");
    }

    private ConditionsReading reading(BriefSlot slot) {
        Now n = weather.get();
        Next6h next = new Next6h(0, n.windMph(), n.gustMph(), n.aqi(), n.feelsF(), n.feelsF(), false, false);
        Day day = new Day(75, 55, 75, 55);
        ConditionTiers.Assessment a = ConditionTiers.classify(n, next, day, day, slot);
        return new ConditionsReading(40.4, -111.9, "America/Denver", now.get(), n, next, day, day,
                a.condition().name(), a.tier().name());
    }

    private void calmMode() {
        AlertModeState calm = mock(AlertModeState.class);
        when(calm.getState()).thenReturn(AlertModeService.CALM);
        when(alertMode.getForLatLng(anyDouble(), anyDouble())).thenReturn(calm);
    }

    private void at(int year, int month, int day, int hour, int minute) {
        now.set(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, DENVER).toInstant());
    }

    private DailyBriefScheduler scheduler(boolean enabled) {
        return new DailyBriefScheduler(cells, conditions, new DailyNudgeCatalog(), new DailyBriefComposer(),
                alertMode, clock, enabled, true);
    }

    @Test
    void slotWindowsAreLocalAndAnHourLong() {
        assertThat(DailyBriefScheduler.dueSlot(ZonedDateTime.of(2026, 11, 2, 6, 59, 0, 0, DENVER))).isNull();
        assertThat(DailyBriefScheduler.dueSlot(ZonedDateTime.of(2026, 11, 2, 7, 0, 0, 0, DENVER)))
                .isEqualTo(BriefSlot.MORNING);
        assertThat(DailyBriefScheduler.dueSlot(ZonedDateTime.of(2026, 11, 2, 7, 59, 0, 0, DENVER)))
                .isEqualTo(BriefSlot.MORNING);
        assertThat(DailyBriefScheduler.dueSlot(ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, DENVER))).isNull();
        assertThat(DailyBriefScheduler.dueSlot(ZonedDateTime.of(2026, 11, 2, 18, 30, 0, 0, DENVER)))
                .isEqualTo(BriefSlot.EVENING);
    }

    @Test
    void theMorningAfterTheClocksChangeIsStillSevenLocal() {
        // US DST ends 2026-11-01. 7:15 local the next morning is 14:15 UTC, not 13:15.
        ZonedDateTime local = Instant.parse("2026-11-02T14:15:00Z").atZone(DENVER);
        assertThat(DailyBriefScheduler.dueSlot(local)).isEqualTo(BriefSlot.MORNING);
    }

    @Test
    void theMorningBriefFiresOnceThenTheSlotIsDone() {
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
        at(2026, 11, 2, 7, 20);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.ALREADY_FIRED);
    }

    @Test
    void outsideASlotNothingHappens() {
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 10, 0);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.NOT_DUE);
    }

    @Test
    void aFairDayGetsOneBriefNotThree() {
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
        at(2026, 11, 2, 12, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.UNCHANGED);
        at(2026, 11, 2, 18, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void middayPostsWhenConditionsChange() {
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
        weather.set(windy());
        at(2026, 11, 2, 12, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
    }

    @Test
    void fireWeekEveningPostsEvenOnAnUnchangedDay() {
        DailyBriefScheduler s = scheduler(true);
        at(2026, 10, 6, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
        at(2026, 10, 6, 18, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);   // the event is due
        at(2026, 10, 7, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
        at(2026, 10, 7, 18, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.UNCHANGED); // already used this week
    }

    @Test
    void alertAndCrisisModeSuppressTheBrief() {
        AlertModeState alert = mock(AlertModeState.class);
        when(alert.getState()).thenReturn(AlertModeService.ALERT);
        when(alertMode.getForLatLng(anyDouble(), anyDouble())).thenReturn(alert);
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.SUPPRESSED);
        verify(conditions, never()).readingFor(anyDouble(), anyDouble(), any());
    }

    @Test
    void attentionModeStillBriefs() {
        AlertModeState attention = mock(AlertModeState.class);
        when(attention.getState()).thenReturn(AlertModeService.ATTENTION);
        when(alertMode.getForLatLng(anyDouble(), anyDouble())).thenReturn(attention);
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
    }

    @Test
    void noReadingRetriesInsideTheWindow() {
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        when(conditions.readingFor(anyDouble(), anyDouble(), any())).thenReturn(null);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.NO_READING);
        when(conditions.readingFor(anyDouble(), anyDouble(), any())).thenAnswer(i -> reading(i.getArgument(2)));
        at(2026, 11, 2, 7, 20);
        assertThat(s.consider(CELL)).isEqualTo(Outcome.BRIEFED);
    }

    @Test
    void theDryRunIsReviewableNotJustLogged() {
        // Heroku keeps 1,500 log lines; a day of briefs must survive in the
        // review buffer, newest first, with why a slot did not post.
        DailyBriefScheduler s = scheduler(true);
        at(2026, 11, 2, 7, 5);
        s.consider(CELL);
        at(2026, 11, 2, 12, 5);
        s.consider(CELL);
        var review = s.recentReview();
        assertThat(review).hasSize(2);
        assertThat(review.get(0).outcome()).isEqualTo("UNCHANGED");
        assertThat(review.get(0).slot()).isEqualTo("MIDDAY");
        assertThat(review.get(1).outcome()).isEqualTo("BRIEFED");
        assertThat(review.get(1).body()).startsWith("Morning update: 68°F");
    }

    @Test
    void disabledDoesNothing() {
        DailyBriefScheduler s = scheduler(false);
        at(2026, 11, 2, 7, 5);
        s.tick();
        verify(cells, never()).cells();
    }
}
