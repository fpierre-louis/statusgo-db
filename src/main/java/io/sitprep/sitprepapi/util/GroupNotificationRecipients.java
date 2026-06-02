package io.sitprep.sitprepapi.util;

import io.sitprep.sitprepapi.domain.Group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Recipient helpers for role-targeted group notifications.
 */
public final class GroupNotificationRecipients {

    private GroupNotificationRecipients() {}

    /**
     * Owner first, then admins, de-duped case-insensitively while preserving
     * the stored email casing for repository lookups.
     */
    public static List<String> adminOwnerEmails(Group group) {
        if (group == null) return List.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        add(out, group.getOwnerEmail());
        if (group.getAdminEmails() != null) {
            for (String email : group.getAdminEmails()) {
                add(out, email);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(LinkedHashMap<String, String> out, String email) {
        if (email == null || email.isBlank()) return;
        String trimmed = email.trim();
        out.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
    }
}
