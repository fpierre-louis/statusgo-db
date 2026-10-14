package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * {@code GET /api/me/work-orders?scope=managed} (audit TK-09): one triaged,
 * paged board across every group the caller owns or admins, replacing the
 * FE's per-group fan-out.
 *
 * @param items     this page, triaged: life-safety › urgent › everything else,
 *                  newest first inside each rank (the FE {@code triageRank} rule)
 * @param groups    name and type of every managed group, so rows can be labelled
 * @param offset    the page's first index into the triaged list
 * @param limit     page size used
 * @param total     rows triaged (within the window)
 * @param hasMore   another page exists
 * @param truncated the newest {@code window} rows were triaged and older rows
 *                  were left out; narrow by status to reach them
 */
public record ManagedWorkOrdersDto(
        List<PostDto> items,
        List<GroupRef> groups,
        int offset,
        int limit,
        int total,
        boolean hasMore,
        boolean truncated
) {
    public record GroupRef(String groupId, String name, String groupType) {}
}
