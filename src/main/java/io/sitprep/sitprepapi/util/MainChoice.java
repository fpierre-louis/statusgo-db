package io.sitprep.sitprepapi.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * "Main" is ONE choice per household (evacuation plan clarity, 2026-10-20).
 *
 * <p>Meeting spots and destinations each carry a {@code deploy} flag, shown as
 * "Main": the place the household sees first and the one a plan starts with.
 * Nothing used to keep it single. The wizard never set it, and the map's "Make
 * primary meeting place" set it without clearing the others, so a household
 * could hold two Mains and {@code pickPrimary} silently took whichever came
 * first. Every write path now runs its rows through here.</p>
 */
public final class MainChoice {

    private MainChoice() {}

    /**
     * Leave at most one row flagged. The survivor is {@code keepId} when that row
     * is flagged, otherwise the first flagged row in list order. Rows that may not
     * hold Main at all ({@code eligible} false) are always cleared.
     *
     * @return the rows whose flag this call changed (to save)
     */
    public static <T> List<T> keepOne(List<T> rows,
                                      Function<T, Long> id,
                                      Predicate<T> isMain,
                                      BiConsumer<T, Boolean> setMain,
                                      Predicate<T> eligible,
                                      Long keepId) {
        List<T> changed = new ArrayList<>();
        if (rows == null || rows.isEmpty()) return changed;

        T survivor = null;
        if (keepId != null) {
            for (T r : rows) {
                if (Objects.equals(id.apply(r), keepId) && isMain.test(r) && eligible.test(r)) {
                    survivor = r;
                    break;
                }
            }
        }
        if (survivor == null) {
            for (T r : rows) {
                if (isMain.test(r) && eligible.test(r)) {
                    survivor = r;
                    break;
                }
            }
        }
        for (T r : rows) {
            if (r != survivor && isMain.test(r)) {
                setMain.accept(r, false);
                changed.add(r);
            }
        }
        return changed;
    }
}
