package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.domain.Group;

public class GroupUrlUtil {

    public static String getGroupTargetUrl(Group group) {
        if ("Household".equalsIgnoreCase(group.getGroupType())) {
            return "/household/h/4D-FwtX/household/" + group.getGroupId();
        } else {
            return "/Linked/lg/4D-FwtX/" + group.getGroupId();
        }
    }
}
