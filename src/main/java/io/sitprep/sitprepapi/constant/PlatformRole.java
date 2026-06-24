package io.sitprep.sitprepapi.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum PlatformRole {
    NONE,

    CONSULTANT(
            PlatformPermission.VIEW_CONSOLE,
            PlatformPermission.REVIEW_AGENCY_REQUESTS,
            PlatformPermission.PROVISION_AGENCY,
            PlatformPermission.VIEW_METRICS),

    ADMIN(
            PlatformPermission.VIEW_CONSOLE,
            PlatformPermission.REVIEW_AGENCY_REQUESTS,
            PlatformPermission.PROVISION_AGENCY,
            PlatformPermission.VIEW_METRICS,
            PlatformPermission.GRANT_AUTHORITY_STAMP,
            PlatformPermission.MODERATE_REPORTS,
            PlatformPermission.MANAGE_PUBLISHERS),

    SUPER_ADMIN;

    private final Set<PlatformPermission> defaults;

    PlatformRole(PlatformPermission... permissions) {
        this.defaults = permissions.length == 0
                ? EnumSet.noneOf(PlatformPermission.class)
                : EnumSet.copyOf(Arrays.asList(permissions));
    }

    public Set<PlatformPermission> defaults() {
        if (this == SUPER_ADMIN) return EnumSet.allOf(PlatformPermission.class);
        return Collections.unmodifiableSet(defaults);
    }

    public boolean has(PlatformPermission permission) {
        return permission != null && defaults().contains(permission);
    }

    public boolean isAtLeast(PlatformRole other) {
        return other != null && ordinal() >= other.ordinal();
    }
}
