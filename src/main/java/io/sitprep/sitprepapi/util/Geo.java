package io.sitprep.sitprepapi.util;

/**
 * Small coordinate helpers for the String -> Double migration (V26 /
 * docs/MAP_REBUILD_PLAN.md).
 */
public final class Geo {

    private Geo() {}

    /**
     * Null-safe {@code Double -> String} for the DTO wire fields that stay
     * String during this phase (MeDto, GroupMemberViewDto.GroupInfo,
     * CommunityDiscoverDto). The entity + columns are now {@code double
     * precision}; converting at the DTO boundary preserves the exact JSON
     * contract, so no FE change is required until the FE phase flips those
     * DTOs to Double in lockstep with removing the client-side parsing.
     */
    public static String str(Double d) {
        return d == null ? null : d.toString();
    }
}
