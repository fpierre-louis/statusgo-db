package io.sitprep.sitprepapi.dto;

import java.util.List;

/**
 * Response of the subgroup link / unlink endpoints
 * ({@code PUT|DELETE /api/groups/{parentGroupId}/subgroups/{childGroupId}}).
 *
 * <p>Both sides of the link as stored after the write, and nothing else — no
 * rosters. The caller is an admin of both groups, but the client only needs the
 * two id lists to redraw, so that is all it gets.</p>
 *
 * @param linked              true after a link, false after an unlink
 * @param parentSubGroupIDs   the parent's {@code subGroupIDs} after the write
 * @param childParentGroupIDs the child's {@code parentGroupIDs} after the write
 */
public record SubGroupLinkDto(
        String parentGroupId,
        String childGroupId,
        boolean linked,
        List<String> parentSubGroupIDs,
        List<String> childParentGroupIDs
) {}
