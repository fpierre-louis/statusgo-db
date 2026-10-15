package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.domain.Group;

/**
 * The app route a notification about a group points at. Organizations open on
 * {@code /groups/{id}}, the canonical group URL (group page D16 / SH-12,
 * 2026-10-14); {@code /Linked/lg/4D-FwtX/{id}} is now only a client-side
 * redirect kept for old rows and links.
 */
public class GroupUrlUtil {

    public static String getGroupTargetUrl(Group group) {
        if ("Household".equalsIgnoreCase(group.getGroupType())) {
            return "/household/h/4D-FwtX/household/" + group.getGroupId();
        } else {
            return "/groups/" + group.getGroupId();
        }
    }
}
