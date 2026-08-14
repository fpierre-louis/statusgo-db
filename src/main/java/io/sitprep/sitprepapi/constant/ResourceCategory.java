package io.sitprep.sitprepapi.constant;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The canonical vocabulary for {@code resource_listing.category}.
 *
 * <p><b>Why this exists.</b> There were two vocabularies. The submit sheet
 * offered nine chips; {@code ResourceSeeder} wrote {@code hotline},
 * {@code recovery} and {@code medical} — and it writes through
 * {@code repo.save()} directly, not through {@link
 * io.sitprep.sitprepapi.service.ResourceListingService}, so nothing the
 * service did could constrain it. Meanwhile the service only lowercased
 * whatever free text arrived. Two writers, no shared definition, no
 * validation: the drift was not a mistake anyone made, it was the only
 * thing that could have happened.</p>
 *
 * <p><b>Both writers now read this set.</b> That is the point of the class —
 * not the validation it enables, but that there is one place to change. If
 * you add a category here, add its chip in {@code ResourceSubmitSheet.jsx}
 * and its icon in {@code ResourceBoardPage.jsx}; those three are the whole
 * surface.</p>
 *
 * <p><b>Values are stable; labels are not.</b> The frontend maps value to
 * label ({@code cooling-center} renders "Cooling center"), so copy can change
 * without touching data. Changing a VALUE here is a data migration — and note
 * {@code ResourceSeeder} is insert-only, so it will not rewrite rows that
 * already exist.</p>
 */
public final class ResourceCategory {

    /** Fallback for a submission that fits nothing else. Never rejected. */
    public static final String OTHER = "other";

    /**
     * Insertion-ordered so the set doubles as the canonical display order:
     * the immediate physical needs first, then the two contact-a-service
     * kinds, then the catch-all last.
     */
    private static final Set<String> VALUES = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.List.of(
                    "cooling-center",
                    "warming-center",
                    "shelter",
                    "food",
                    "water",
                    "medical",
                    "charging",
                    "supplies",
                    // Not places you go — services you contact. Seeded national
                    // listings (211, 988, FEMA) live here; the board has
                    // rendered icons for both since before they were selectable.
                    "hotline",
                    "recovery",
                    OTHER
            )));

    private ResourceCategory() {
    }

    /** The canonical set, in display order. Unmodifiable. */
    public static Set<String> values() {
        return VALUES;
    }

    /** True when {@code raw} is exactly a canonical value (already normalized). */
    public static boolean isValid(String raw) {
        return raw != null && VALUES.contains(raw);
    }

    /**
     * Trim + lowercase, then validate. Null or blank becomes {@link #OTHER} —
     * a submission that simply omits the field is not an error. Anything
     * present but unrecognized is returned as-is for the caller to reject;
     * this method does not throw, so the seeder can use the same
     * normalization without inheriting an HTTP concern.
     */
    public static String normalize(String raw) {
        if (raw == null) return OTHER;
        String t = raw.trim();
        if (t.isEmpty()) return OTHER;
        return t.toLowerCase(Locale.ROOT);
    }
}
