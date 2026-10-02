package io.sitprep.sitprepapi.service;

import io.sentry.Sentry;
import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers;
import io.sitprep.sitprepapi.domain.AlertModeState;
import io.sitprep.sitprepapi.dto.ConditionsReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when each cell gets a daily brief (EXEC-3B; gameplan §3.2, §8).
 *
 * <p><b>Owner rulings this encodes (2026-10-01):</b></p>
 * <ul>
 *   <li><b>Slots</b> 7:00, 12:00 and 18:00 in the cell's OWN time zone, which
 *       NWS assigns the point (ConditionsService.timezoneFor). There is no reliable per-user
 *       time zone, and the area's is the one that matters anyway.</li>
 *   <li><b>Adaptive cadence.</b> The morning brief always posts. Midday and
 *       evening post only when the condition or tier has changed since the
 *       day's last brief, or an event nudge is due. A fair day gets one post,
 *       so each brief means something changed.</li>
 *   <li><b>Alerts win.</b> No brief while the cell's alert mode is alert or
 *       crisis; the alert posts own the feed then.</li>
 * </ul>
 *
 * <p><b>Firing.</b> A slot fires on the first 15-minute tick inside the hour
 * after its local time (the HouseholdRitualScheduler pattern), once per cell,
 * local date and slot. A tick that cannot read the weather retries on the next
 * tick inside the window; after the window, the slot is skipped, never posted
 * late with a stale reading.</p>
 *
 * <p><b>Switches.</b> {@code briefs.enabled} (default false) gates the whole
 * job, on top of {@code app.scheduling.enabled}, which is off on laptops
 * because they run against the production database. {@code briefs.dry-run}
 * (default true) logs each brief instead of writing it; writing lands in 3C
 * with the daily_brief table. Until then this keeps its bookkeeping in memory,
 * which is enough for a dry run on a single dyno.</p>
 */
@Service
public class DailyBriefScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyBriefScheduler.class);

    static final int WINDOW_MINUTES = 60;
    static final int NO_REPEAT_DAYS = 14;

    private final BriefCellService cells;
    private final ConditionsService conditions;
    private final DailyNudgeCatalog catalog;
    private final DailyBriefComposer composer;
    private final AlertModeService alertMode;
    private final Clock clock;
    private final boolean enabled;
    private final boolean dryRun;

    /** Result of considering one cell on one tick; logged and asserted in tests. */
    public enum Outcome { NOT_DUE, ALREADY_FIRED, SUPPRESSED, NO_READING, UNCHANGED, NO_BRIEF, BRIEFED }

    /** What the day's last brief said, for the adaptive rule. */
    record Last(LocalDate date, String condition, String tier) {}

    record Used(LocalDate date, String nudgeId) {}

    private final Map<String, ZoneId> zones = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> fired = new ConcurrentHashMap<>();
    private final Map<String, Last> lastBrief = new ConcurrentHashMap<>();
    private final Map<String, Deque<Used>> history = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> neighborDay = new ConcurrentHashMap<>();

    @Autowired
    public DailyBriefScheduler(BriefCellService cells, ConditionsService conditions, DailyNudgeCatalog catalog,
                               DailyBriefComposer composer, AlertModeService alertMode,
                               @Value("${briefs.enabled:false}") boolean enabled,
                               @Value("${briefs.dry-run:true}") boolean dryRun) {
        this(cells, conditions, catalog, composer, alertMode, Clock.systemUTC(), enabled, dryRun);
    }

    DailyBriefScheduler(BriefCellService cells, ConditionsService conditions, DailyNudgeCatalog catalog,
                        DailyBriefComposer composer, AlertModeService alertMode, Clock clock,
                        boolean enabled, boolean dryRun) {
        this.cells = cells;
        this.conditions = conditions;
        this.catalog = catalog;
        this.composer = composer;
        this.alertMode = alertMode;
        this.clock = clock;
        this.enabled = enabled;
        this.dryRun = dryRun;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT9M")
    public void tick() {
        if (!enabled) return;
        try {
            int made = runOnce();
            if (made > 0) log.info("DailyBrief: {} brief(s) this tick (dryRun={})", made, dryRun);
        } catch (Exception e) {
            log.warn("DailyBrief tick failed: {}", e.toString());
            Sentry.captureException(e);
        }
    }

    /** One pass over every cell. Returns how many briefs were made. */
    int runOnce() {
        int made = 0;
        for (BriefCellService.Cell cell : cells.cells()) {
            try {
                if (consider(cell) == Outcome.BRIEFED) made++;
            } catch (Exception e) {
                // One bad cell must not stop the rest.
                log.warn("DailyBrief cell {} failed: {}", cell.key(), e.toString());
            }
        }
        prune();
        return made;
    }

    /** The slot whose window contains {@code local}, or null. DST-safe: windows are built in the zone. */
    static BriefSlot dueSlot(ZonedDateTime local) {
        for (BriefSlot slot : BriefSlot.values()) {
            ZonedDateTime start = ZonedDateTime.of(local.toLocalDate(),
                    java.time.LocalTime.of(slot.hour(), slot.minute()), local.getZone());
            if (!local.isBefore(start) && local.isBefore(start.plusMinutes(WINDOW_MINUTES))) return slot;
        }
        return null;
    }

    Outcome consider(BriefCellService.Cell cell) {
        ZoneId zone = zoneFor(cell);
        if (zone == null) return Outcome.NO_READING;
        ZonedDateTime local = clock.instant().atZone(zone);
        BriefSlot slot = dueSlot(local);
        if (slot == null) return Outcome.NOT_DUE;

        LocalDate date = local.toLocalDate();
        String firedKey = cell.key() + "|" + date + "|" + slot;
        if (fired.containsKey(firedKey)) return Outcome.ALREADY_FIRED;

        AlertModeState mode = alertMode.getForLatLng(cell.lat(), cell.lng());
        String state = mode == null ? null : mode.getState();
        if (AlertModeService.ALERT.equals(state) || AlertModeService.CRISIS.equals(state)) {
            fired.put(firedKey, date);
            log.info("DailyBrief: cell {} {} suppressed ({} mode)", cell.key(), slot, state);
            return Outcome.SUPPRESSED;
        }

        ConditionsReading r = conditions.readingFor(cell.lat(), cell.lng(), slot);
        if (r == null) return Outcome.NO_READING;   // retry on the next tick in the window

        Set<String> used = usedRecently(cell.key(), date);
        if (slot != BriefSlot.MORNING) {
            Last last = lastBrief.get(cell.key());
            boolean sameDay = last != null && last.date().equals(date);
            boolean changed = !sameDay || !last.condition().equals(r.condition()) || !last.tier().equals(r.tier());
            if (!changed && !catalog.eventDue(date, slot, used)) {
                fired.put(firedKey, date);
                return Outcome.UNCHANGED;
            }
        }

        boolean neighborToday = date.equals(neighborDay.get(cell.key()));
        DailyNudgeCatalog.Nudge nudge = catalog.pick(
                ConditionTiers.Condition.valueOf(r.condition()), slot, date, used, neighborToday);
        DailyBriefComposer.Brief brief = composer.compose(r, slot, nudge);
        if (brief == null) {
            // A required reading was missing. Retry next tick rather than post a partial brief.
            return Outcome.NO_BRIEF;
        }

        fired.put(firedKey, date);
        lastBrief.put(cell.key(), new Last(date, r.condition(), r.tier()));
        history.computeIfAbsent(cell.key(), k -> new ArrayDeque<>()).addLast(new Used(date, nudge.id()));
        if (nudge.neighbor()) neighborDay.put(cell.key(), date);

        if (dryRun) {
            log.info("DailyBrief[dry-run] cell={} {} {} {}/{} nudge={} -> {} | {}",
                    cell.key(), date, slot, r.condition(), r.tier(), nudge.id(), nudge.destination(),
                    brief.body().replace("\n\n", " // "));
        } else {
            // Writing the daily_brief row and the post lands in 3C. Until then a
            // misconfigured dry-run=false must not silently do nothing.
            log.warn("DailyBrief: briefs.dry-run=false but posting ships in 3C; logged only. cell={} {}",
                    cell.key(), slot);
        }
        return Outcome.BRIEFED;
    }

    private ZoneId zoneFor(BriefCellService.Cell cell) {
        ZoneId z = zones.get(cell.key());
        if (z != null) return z;
        // The NWS grid lookup only: no air-quality call just to place the slots.
        String tz = conditions.timezoneFor(cell.lat(), cell.lng());
        if (tz == null) return null;
        try {
            z = ZoneId.of(tz);
        } catch (Exception e) {
            return null;
        }
        zones.put(cell.key(), z);
        return z;
    }

    private Set<String> usedRecently(String cellKey, LocalDate date) {
        Set<String> out = new HashSet<>();
        Deque<Used> h = history.get(cellKey);
        if (h == null) return out;
        LocalDate since = date.minusDays(NO_REPEAT_DAYS);
        for (Used u : h) {
            if (u.date().isAfter(since)) out.add(u.nudgeId());
        }
        return out;
    }

    /** Drop bookkeeping older than the no-repeat window so memory stays bounded. */
    private void prune() {
        LocalDate today = clock.instant().atZone(ZoneId.of("UTC")).toLocalDate();
        fired.values().removeIf(d -> d.isBefore(today.minusDays(2)));
        history.values().forEach(h -> h.removeIf(u -> u.date().isBefore(today.minusDays(NO_REPEAT_DAYS + 1))));
    }
}
