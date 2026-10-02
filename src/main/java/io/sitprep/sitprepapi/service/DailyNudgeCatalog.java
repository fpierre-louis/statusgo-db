package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.BriefSlot;
import io.sitprep.sitprepapi.constant.ConditionTiers.Condition;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every nudge a daily brief can carry (EXEC-3B; gameplan §3.6 and §5).
 *
 * <p><b>Catalog only.</b> A nudge is never composed on the fly. Each one is an
 * offer ("if you have two minutes..."), never "you should", and each links to
 * a destination that exists in the app today: a drill in
 * {@code challenges.js} (title and minutes copied from it), a guide, a
 * playbook, or the Hazards page.</p>
 *
 * <p><b>Generic by rule (T-27).</b> Nothing here may refer to a household's own
 * readiness or count neighbours. A brief is a community post.</p>
 *
 * <p>Advice is limited to NWS, EPA, CDC and Red Cross guidance, or the linked
 * drill's own source.</p>
 */
@Component
public class DailyNudgeCatalog {

    /**
     * A nudge. {@code condition}/{@code slot} null means "any" (the fair-weather
     * rotation and the events). {@code neighbor} marks a neighbour-themed offer,
     * capped at one per cell per day so the platform never sounds like it is
     * telling people how to use the feed.
     */
    public record Nudge(String id, Condition condition, BriefSlot slot, String text,
                        String destination, String label, Integer minutes, boolean neighbor) {}

    private static String drill(String id) { return "/practice/drill/" + id; }

    // ── The post matrix (gameplan §5), one row per slot × condition ────────
    static final List<Nudge> MATRIX = List.of(
            new Nudge("M-FAIR", Condition.FAIR, BriefSlot.MORNING,
                    "Clear skies this morning. If you have two minutes, walk past your family's outdoor meeting spot and make sure everyone would know where it is.",
                    drill("meeting-spots"), "Pick two meeting spots", 15, false),
            new Nudge("D-FAIR", Condition.FAIR, BriefSlot.MIDDAY,
                    "Easy afternoon for a quick go-bag check. A flashlight that still turns on is worth more than a full bag you haven't opened.",
                    drill("go-bag"), "Build your go-bag", 60, false),
            new Nudge("E-FAIR", Condition.FAIR, BriefSlot.EVENING,
                    "A quiet evening. If there's a neighbor you keep meaning to say hello to, tonight's an easy night for it.",
                    drill("meet-a-neighbor"), "Meet one neighbor", 15, true),
            new Nudge("M-RAIN", Condition.RAIN, BriefSlot.MORNING,
                    "Rain on the way. A good morning to top up phones and a power bank, in case the lights flicker later.",
                    drill("poweroutage-blackout-test"), "Try a ten-minute blackout", 10, false),
            new Nudge("D-RAIN", Condition.RAIN, BriefSlot.MIDDAY,
                    "Wet afternoon. If your usual route crosses a low road or underpass, it helps to know a second way around before you need it.",
                    drill("flood-underpass-scan"), "Spot your flood no-go zones", 20, false),
            new Nudge("E-RAIN", Condition.RAIN, BriefSlot.EVENING,
                    "Wet night ahead. If your usual route crosses a low road or underpass, it helps to know a second way around before you need it.",
                    drill("flood-underpass-scan"), "Spot your flood no-go zones", 20, false),
            new Nudge("M-STORM", Condition.STORM, BriefSlot.MORNING,
                    "Storms possible today. If you hear thunder, head indoors and give it 30 minutes after the last rumble before going back out.",
                    "/hazards?focus=weather", "Weather near you", null, false),
            new Nudge("D-STORM", Condition.STORM, BriefSlot.MIDDAY,
                    "If you hear thunder, head indoors and give it 30 minutes after the last rumble before going back out. Worth sharing with anyone heading out this afternoon.",
                    "/hazards?focus=weather", "Weather near you", null, false),
            new Nudge("E-STORM", Condition.STORM, BriefSlot.EVENING,
                    "Storms around tonight. A flashlight by the bed and a charged phone make an outage a lot easier to sleep through.",
                    drill("flashlights"), "Test every flashlight", 10, false),
            new Nudge("M-WIND", Condition.WIND, BriefSlot.MORNING,
                    "Gusty day ahead. A quick lap of the yard for anything that could blow loose helps, and if an older neighbor might want a hand securing theirs, a short text goes a long way.",
                    drill("meet-a-neighbor"), "Meet one neighbor", 15, true),
            new Nudge("D-WIND", Condition.WIND, BriefSlot.MIDDAY,
                    "Strong gusts can bring down branches and power lines. If you see a line down, stay well back and report it to your utility.",
                    "/playbooks/power-outage", "Power outage playbook", null, false),
            new Nudge("E-WIND", Condition.WIND, BriefSlot.EVENING,
                    "Windy night. A flashlight by the bed and a charged phone make an outage a lot easier to sleep through.",
                    drill("flashlights"), "Test every flashlight", 10, false),
            new Nudge("M-HEAT", Condition.HEAT, BriefSlot.MORNING,
                    "Hot one coming. Morning is the time for outdoor errands, and an extra bottle of water in the car is an easy win.",
                    drill("water-3day"), "Stock three days of water", 30, false),
            new Nudge("D-HEAT", Condition.HEAT, BriefSlot.MIDDAY,
                    "Peak heat right now. If someone nearby is older or lives alone, this afternoon is a good time to check they're keeping cool.",
                    drill("meet-a-neighbor"), "Meet one neighbor", 15, true),
            new Nudge("E-HEAT", Condition.HEAT, BriefSlot.EVENING,
                    "Warm night ahead. Kids and pets should never wait in a parked car, even for a minute, and the evening is when it's easiest to forget.",
                    "/hazards?focus=weather", "Weather near you", null, false),
            new Nudge("M-COLD", Condition.COLD, BriefSlot.MORNING,
                    "Bitter start. If you're driving, a blanket and a phone charger in the car are worth the minute it takes.",
                    drill("blizzard-car-kit-mini-pack"), "Pack the winter car stash", 20, false),
            new Nudge("D-COLD", Condition.COLD, BriefSlot.MIDDAY,
                    "Cold enough for pipes to freeze tonight. Letting a faucet on an outside wall drip overnight is an easy fix.",
                    "/blizzard-prep", "Winter storm guide", null, false),
            new Nudge("E-COLD", Condition.COLD, BriefSlot.EVENING,
                    "Very cold night. Picking one warm room for the household helps, and so does checking on anyone nearby without reliable heat.",
                    drill("blizzard-warm-room-sprint"), "Build your warm-room kit", 20, true),
            new Nudge("M-AIR", Condition.AIR, BriefSlot.MORNING,
                    "Hazy air this morning. If anyone at home has asthma or heart trouble, it's a good day to keep windows closed and move exercise indoors.",
                    "/hazards?focus=air", "Air quality near you", null, false),
            new Nudge("D-AIR", Condition.AIR, BriefSlot.MIDDAY,
                    "Air quality is poor right now. Setting the AC to recirculate and keeping outdoor time short both help.",
                    "/hazards?focus=air", "Air quality near you", null, false),
            new Nudge("E-AIR", Condition.AIR, BriefSlot.EVENING,
                    "Smoke tends to settle overnight. A good night to check the N95 masks are where you think they are.",
                    drill("wildfire-smoke-go-hunt"), "Run a smoke-and-go hunt", 5, false)
    );

    // ── Fair-weather rotation: real drills, any slot ───────────────────────
    // The matrix has ONE fair nudge per slot, so a run of fair mornings would
    // repeat it daily. These rotate in behind it under the 14-day rule.
    static final List<Nudge> FAIR_ROTATION = List.of(
            new Nudge("R-FLASHLIGHTS", Condition.FAIR, null,
                    "A calm day is a good time to click every flashlight in the house on once. Dead batteries are easier to swap now than in the dark.",
                    drill("flashlights"), "Test every flashlight", 10, false),
            new Nudge("R-SMOKE-ALARMS", Condition.FAIR, null,
                    "Five minutes, one button: press test on each smoke alarm, and swap the battery in any that stay quiet.",
                    drill("smoke-alarms"), "Test your smoke alarms", 5, false),
            new Nudge("R-OUT-OF-TOWN", Condition.FAIR, null,
                    "When local phone lines are busy, a contact far away is often easier to reach. Today's an easy day to pick who that is.",
                    drill("out-of-town"), "Name your out-of-town contact", 10, false),
            new Nudge("R-SHUTOFFS", Condition.FAIR, null,
                    "Worth knowing before you need it: where the water and gas shutoffs are, and whether the tool to turn them is nearby.",
                    drill("utility-shutoffs"), "Find your shutoff valves", 20, false),
            new Nudge("R-FIRST-AID", Condition.FAIR, null,
                    "A good day to open the first-aid kit and see what's expired or used up.",
                    drill("first-aid"), "Refresh your first-aid kit", 20, false),
            new Nudge("R-WATER", Condition.FAIR, null,
                    "One gallon per person per day, for three days. A calm day is a good one to see how close your shelves are.",
                    drill("water-3day"), "Stock three days of water", 30, false),
            new Nudge("R-CONTACT-TREE", Condition.FAIR, null,
                    "Sending one real test message to your household list shows who actually answers. Fifteen minutes, and any gaps show up fast.",
                    drill("contact-tree"), "Test your contact tree", 15, false),
            new Nudge("R-MEDICAL", Condition.FAIR, null,
                    "Writing allergies, prescriptions and devices down in one place helps anyone who has to help you. A calm day is the day to do it.",
                    drill("medical-needs"), "Write down medical needs", 20, false),
            new Nudge("R-ROLES", Condition.FAIR, null,
                    "One emergency job each (pets, the go-bag, the head count) makes the first minutes calmer. Worth saying out loud at dinner.",
                    drill("emergency-roles"), "Assign emergency jobs", 15, false),
            new Nudge("R-DOCUMENTS", Condition.FAIR, null,
                    "Photographing IDs and insurance papers takes about half an hour, and means a copy lives somewhere other than the drawer.",
                    drill("key-documents"), "Photograph your key documents", 35, false),
            new Nudge("R-EXIT-DRILL", Condition.FAIR, null,
                    "Two ways out of each room and everyone at the meeting spot in under two minutes. Kids tend to enjoy leading this one.",
                    drill("exit-drill"), "Run a two-minute exit drill", 15, false),
            new Nudge("R-EVAC-ROUTE", Condition.FAIR, null,
                    "If you're driving somewhere this week, take the long way past your evacuation route, and a second one that avoids the same bridge or canyon.",
                    drill("evac-route"), "Drive your evacuation route", 55, false)
    );

    // ── Event days (mirrors FE drillCalendar.js) ──────────────────────────
    static final Nudge SHAKEOUT = new Nudge("EV-SHAKEOUT", Condition.FAIR, BriefSlot.MORNING,
            "Today's the Great ShakeOut, at 10:15 AM. Drop, Cover and Hold On takes about a minute to practise.",
            drill("earthquake-drop-cover-relay"), "Do a drop-cover-hold relay", 10, false);
    static final Nudge FIRE_WEEK = new Nudge("EV-FIREWEEK", Condition.FAIR, BriefSlot.EVENING,
            "It's Fire Prevention Week. Walking two ways out of each bedroom takes five minutes, and kids remember it best when they lead.",
            drill("exit-drill"), "Run a two-minute exit drill", 15, false);
    static final Nudge PREP_MONTH = new Nudge("EV-PREPMONTH", Condition.FAIR, BriefSlot.MORNING,
            "September is National Preparedness Month. Picking two meeting spots, one outside the house and one outside the neighbourhood, takes about fifteen minutes.",
            drill("meeting-spots"), "Pick two meeting spots", 15, false);

    /** Every nudge, for validation. */
    public List<Nudge> all() {
        List<Nudge> out = new ArrayList<>(MATRIX);
        out.addAll(FAIR_ROTATION);
        out.addAll(List.of(SHAKEOUT, FIRE_WEEK, PREP_MONTH));
        return out;
    }

    /** The event nudge active for {@code slot} on {@code date} (the cell's local date), or null. */
    public Nudge activeEvent(LocalDate date, BriefSlot slot) {
        if (date.getMonth() == Month.OCTOBER) {
            LocalDate shakeout = date.withDayOfMonth(1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.THURSDAY));
            if (date.equals(shakeout) && slot == BriefSlot.MORNING) return SHAKEOUT;
            // Fire Prevention Week: the Sunday-to-Saturday week containing Oct 9.
            LocalDate oct9 = date.withDayOfMonth(9);
            LocalDate start = oct9.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
            if (!date.isBefore(start) && !date.isAfter(start.plusDays(6)) && slot == BriefSlot.EVENING) {
                return FIRE_WEEK;
            }
        }
        if (date.getMonth() == Month.SEPTEMBER && slot == BriefSlot.MORNING) return PREP_MONTH;
        return null;
    }

    /** True when an event nudge for this slot is active and not used in the last 14 days. */
    public boolean eventDue(LocalDate date, BriefSlot slot, Set<String> usedRecently) {
        Nudge ev = activeEvent(date, slot);
        return ev != null && !usedRecently.contains(ev.id());
    }

    /**
     * Pick the nudge for a brief.
     *
     * <ol>
     *   <li>Fair weather on an event slot: the event, unless used in 14 days.</li>
     *   <li>The matrix row for this condition and slot, then (fair weather only)
     *       the rotation, skipping anything used in the last 14 days and any
     *       neighbour-themed offer once one has run today.</li>
     *   <li>Nothing left: the matrix row again. A repeated nudge is better than
     *       an invented one ("no nudge is better than a manufactured one").</li>
     * </ol>
     */
    public Nudge pick(Condition condition, BriefSlot slot, LocalDate date,
                      Set<String> usedRecently, boolean neighborUsedToday) {
        if (condition == Condition.FAIR) {
            Nudge ev = activeEvent(date, slot);
            if (ev != null && !usedRecently.contains(ev.id())) return ev;
        }
        Nudge row = matrixRow(condition, slot);
        List<Nudge> candidates = new ArrayList<>();
        if (row != null) candidates.add(row);
        if (condition == Condition.FAIR) candidates.addAll(FAIR_ROTATION);
        for (Nudge n : candidates) {
            if (usedRecently.contains(n.id())) continue;
            if (n.neighbor() && neighborUsedToday) continue;
            return n;
        }
        return row;
    }

    public Nudge matrixRow(Condition condition, BriefSlot slot) {
        for (Nudge n : MATRIX) {
            if (n.condition() == condition && n.slot() == slot) return n;
        }
        return null;
    }
}
