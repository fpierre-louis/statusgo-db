package io.sitprep.sitprepapi.constant;

import io.sitprep.sitprepapi.domain.Group;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A viewer's role within a group — the formalized Owner / Admin /
 * Member ladder (Phase 4 of docs/BUSINESS_MODEL.md). Resolved from the
 * group's {@code ownerEmail} / {@code adminEmails} / {@code memberEmails}.
 *
 * <p>Each role carries a fixed {@link GroupPermission} set; OWNER
 * implicitly holds every permission. This enum is the single source of
 * truth for group authorization — {@code GroupResource} checks against
 * it. The frontend mirror in {@code src/groups/shared/groupRoles.js}
 * is for UI gating only; the backend always re-checks.</p>
 */
public enum GroupRole {

    NONE,

    MEMBER,

    ADMIN(GroupPermission.MANAGE_MEMBERS,
            GroupPermission.MANAGE_TASKS,
            GroupPermission.MANAGE_ALERTS,
            GroupPermission.EDIT_GROUP,
            GroupPermission.VIEW_ADMIN_DASHBOARD),

    /** Owner holds every permission — see {@link #permissions()}. */
    OWNER;

    private final Set<GroupPermission> ownPermissions;

    GroupRole(GroupPermission... perms) {
        this.ownPermissions = perms.length == 0
                ? EnumSet.noneOf(GroupPermission.class)
                : EnumSet.copyOf(Arrays.asList(perms));
    }

    public Set<GroupPermission> permissions() {
        if (this == OWNER) return EnumSet.allOf(GroupPermission.class);
        return Collections.unmodifiableSet(ownPermissions);
    }

    public boolean has(GroupPermission permission) {
        return permission != null && permissions().contains(permission);
    }

    public boolean isAtLeastAdmin() {
        return this == ADMIN || this == OWNER;
    }

    /**
     * Resolve a viewer's role from the group roster. Owner beats admin
     * beats member; an email absent from every list is {@link #NONE}.
     */
    public static GroupRole fromGroup(Group group, String email) {
        if (group == null || email == null || email.isBlank()) return NONE;
        String e = email.trim();
        if (group.getOwnerEmail() != null
                && group.getOwnerEmail().trim().equalsIgnoreCase(e)) {
            return OWNER;
        }
        if (containsIgnoreCase(group.getAdminEmails(), e)) return ADMIN;
        if (containsIgnoreCase(group.getMemberEmails(), e)) return MEMBER;
        return NONE;
    }

    /** Lowercase wire form for DTOs (matches the FE mirror's role strings). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        if (list == null) return false;
        for (String s : list) {
            if (s != null && s.trim().equalsIgnoreCase(needle)) return true;
        }
        return false;
    }
}
