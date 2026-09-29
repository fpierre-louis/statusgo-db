package io.sitprep.sitprepapi.util;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A plan list's "save all" as an UPSERT, not delete-and-replace.
 *
 * <p>The meeting-place, evacuation-plan and origin {@code /bulk} saves used to
 * delete every row and insert the list again, so every row got a NEW id. A live
 * deployment stores only the ids of the meeting place and shelter it selected
 * and reads them back by id — so editing the plan during a deployment made its
 * places vanish from its own map and dashboard (plan-locations audit
 * 2026-09-29, finding A).</p>
 *
 * <p>Now an incoming row whose id belongs to one of the EXISTING rows updates
 * that row in place; any other id is cleared (so a client can never write into
 * a row outside this household — including a temporary client id); existing
 * rows the list no longer contains are returned for deletion.</p>
 */
public final class PlanRowReconciler {

    private PlanRowReconciler() {}

    /**
     * @return the existing rows to delete (the ones the incoming list dropped)
     */
    public static <T> List<T> reconcile(List<T> existing, List<T> incoming,
                                        Function<T, Long> id, BiConsumer<T, Long> setId) {
        Set<Long> owned = existing.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (T row : incoming) {
            Long rowId = id.apply(row);
            if (rowId != null && !owned.contains(rowId)) setId.accept(row, null);
        }
        Set<Long> kept = incoming.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
        return existing.stream().filter(e -> !kept.contains(id.apply(e))).toList();
    }
}
