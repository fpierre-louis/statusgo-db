package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeAvailabilityService.Entry;
import io.sitprep.sitprepapi.practice.PracticeContent.OfferedBy;
import io.sitprep.sitprepapi.practice.PracticeContent.Routine;
import io.sitprep.sitprepapi.practice.PracticeContent.RoutineSlot;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only helpers over the ROUTINE content kind (EXEC-R1). Practice only ever READS routines:
 * it looks one up through the same availability gate every other kind uses, renders the preview
 * line with fallbacks, and says which routine a scenario or activity offers. The household's saved
 * copy, and every plan lookup, live outside this package ({@code io.sitprep.sitprepapi.routine}).
 */
public final class PracticeRoutines {

    private PracticeRoutines() {}

    /** {@code {slot}} placeholder in a thenTemplate. */
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");

    /** What the debrief and the activity detail carry: enough to offer the builder, no plan data. */
    public record RoutineOfferDto(String routineKey, String title, String preview) {}

    /** The servable routine for {@code routineKey}, or empty (unknown, not a routine, disabled, or not published and no preview gate). */
    public static Optional<Entry> servable(PracticeAvailabilityService availability, String routineKey) {
        if (routineKey == null) return Optional.empty();
        PracticeAvailabilityService.StartCheck check = availability.checkStart(routineKey);
        if (!check.allowed()) return Optional.empty();
        Version v = check.entry().version();
        if (v.kind() != PracticeKind.ROUTINE || v.body().routine() == null) return Optional.empty();
        return Optional.of(check.entry());
    }

    /** The offer for the routine whose {@code offeredBy} names this scenario (or activity), when servable to this viewer. */
    public static RoutineOfferDto offerFor(PracticeAvailabilityService availability, boolean scenario, String contentKey) {
        if (contentKey == null) return null;
        for (Entry e : availability.startable(PracticeKind.ROUTINE)) {
            Routine r = e.version().body().routine();
            if (r == null || r.offeredBy() == null) continue;
            OfferedBy by = r.offeredBy();
            List<String> keys = scenario ? by.scenarios() : by.activities();
            if (keys != null && keys.contains(contentKey)) {
                return new RoutineOfferDto(e.version().key(), r.title(), preview(r));
            }
        }
        return null;
    }

    /** The thenTemplate with every slot at its fallback. */
    public static String preview(Routine r) {
        return render(r, slot -> null);
    }

    /** Render {@code thenTemplate}; a slot with no value (or a blank one) shows its fallback. */
    public static String render(Routine r, Function<String, String> valueOf) {
        Map<String, RoutineSlot> byKey = new java.util.HashMap<>();
        if (r.slots() != null) for (RoutineSlot s : r.slots()) if (s != null) byKey.put(s.key(), s);
        Matcher m = PLACEHOLDER.matcher(r.thenTemplate());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = valueOf.apply(m.group(1));
            RoutineSlot s = byKey.get(m.group(1));
            String use = v != null && !v.isBlank() ? v : s == null ? "" : s.fallback();
            use = sentenceCase(use == null ? "" : use, m.start() == 0, s);
            m.appendReplacement(out, Matcher.quoteReplacement(use));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(The|A|An) ");

    /**
     * The value as it reads in the sentence (the stored slot value stays as typed):
     * at the start of the line its first letter is capitalised ("A grown-up wakes
     * everyone…"); mid-sentence, a place or contact name loses the capital on a
     * leading article ("…go to the basement.", not "…go to The basement."). Proper
     * names are untouched. The FE builder preview applies the same rule
     * (RoutineBuilder.jsx sentenceCase) so the preview and the saved line agree.
     */
    static String sentenceCase(String value, boolean atStart, RoutineSlot slot) {
        if (value.isEmpty()) return value;
        if (atStart) return Character.toUpperCase(value.charAt(0)) + value.substring(1);
        String source = slot == null || slot.source() == null ? "" : String.valueOf(slot.source());
        if (source.equals("PLACE") || source.equals("CONTACT")) {
            Matcher a = LEADING_ARTICLE.matcher(value);
            if (a.find()) return a.group(1).toLowerCase(java.util.Locale.ROOT) + " " + value.substring(a.end());
        }
        return value;
    }
}
