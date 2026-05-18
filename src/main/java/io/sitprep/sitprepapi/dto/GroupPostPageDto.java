package io.sitprep.sitprepapi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Cursor-paginated wire shape for {@code GET /api/group-posts/group/{id}/page}.
 *
 * <p>Designed for chat-style scroll: the FE renders {@link #pinned}
 * always-on-top, then {@link #items} below in newest-first order, and
 * triggers a follow-up fetch keyed on {@link #nextBefore} when the
 * user pulls down to load earlier history. {@code hasMore} is the
 * single boolean the FE needs to render the "Load earlier" affordance.</p>
 *
 * <p>The pinned set is separated from the page items so that admins
 * who pinned posts months ago still surface them on every page-1
 * fetch — pinned visibility doesn't degrade with scroll depth. Pin
 * cardinality is bounded (admins typically pin 0–3 per group), so
 * always returning the full pinned list per page is cheap.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupPostPageDto {

    /**
     * All pinned posts for the group, ordered by {@code pinnedAt DESC}
     * (most-recently-pinned first). Stays the same shape across pages;
     * FE caches and renders independently of {@link #items}.
     */
    private List<GroupPostDto> pinned;

    /**
     * Unpinned posts in this page, ordered by id DESC (= timestamp
     * DESC for monotonic auto-increment IDs). Length bounded by the
     * caller's {@code limit} param.
     */
    private List<GroupPostDto> items;

    /**
     * Cursor for the next page — pass back as the {@code before}
     * query param to fetch the next chunk. Null when {@link #hasMore}
     * is false (last page reached).
     */
    private Long nextBefore;

    /**
     * True iff a follow-up fetch with {@link #nextBefore} would return
     * more items. False when this page exhausts the unpinned history.
     */
    private boolean hasMore;
}
