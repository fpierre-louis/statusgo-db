package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;

import java.util.List;

/**
 * What a viewer may do to OTHER members of a group — one answer shared by the
 * endpoints that enforce it and the member-view that tells the client what to
 * offer (map sheet audit 2026-09-29, Phase 2). Before this, the map offered a
 * status picker to everyone and only the server said no; with both reading
 * here, the UI can't offer what the API refuses.
 *
 * <ul>
 *   <li><b>setOthersStatus</b> — answer for someone else: owner or admin.</li>
 *   <li><b>nudge</b> — ping one person: any member of a household; owner or
 *       admin of any other group (owner ruling D-2, so a 60-member HOA can't
 *       nudge each other at will).</li>
 *   <li><b>askEveryone</b> — request a check-in from the whole group: any
 *       member of a household; owner or admin elsewhere.</li>
 *   <li><b>pingMissing</b> — nudge everyone who hasn't answered this alert:
 *       owner or admin.</li>
 * </ul>
 *
 * Nudge and ask-everyone also require the caller to be on the member roster,
 * as they always have.
 */
public final class MemberActionPolicy {

    private MemberActionPolicy() {}

    /** The four answers, in the shape the member-view carries them. */
    public record Capabilities(boolean setOthersStatus, boolean nudge, boolean askEveryone, boolean pingMissing) {}

    public static Capabilities of(Group group, String viewerEmail) {
        return new Capabilities(
                canSetOthersStatus(group, viewerEmail),
                canNudge(group, viewerEmail),
                canAskEveryone(group, viewerEmail),
                canPingMissing(group, viewerEmail));
    }

    public static boolean canSetOthersStatus(Group group, String email) {
        return GroupRole.fromGroup(group, email).isAtLeastAdmin();
    }

    public static boolean canNudge(Group group, String email) {
        return onRoster(group, email) && (isHousehold(group) || GroupRole.fromGroup(group, email).isAtLeastAdmin());
    }

    public static boolean canAskEveryone(Group group, String email) {
        return onRoster(group, email) && (isHousehold(group) || GroupRole.fromGroup(group, email).isAtLeastAdmin());
    }

    public static boolean canPingMissing(Group group, String email) {
        return GroupRole.fromGroup(group, email).isAtLeastAdmin();
    }

    public static boolean isHousehold(Group group) {
        return group != null && HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(group.getGroupType());
    }

    public static boolean onRoster(Group group, String email) {
        if (group == null || email == null || email.isBlank()) return false;
        List<String> members = group.getMemberEmails();
        if (members == null) return false;
        String e = email.trim();
        return members.stream().anyMatch(m -> m != null && m.trim().equalsIgnoreCase(e));
    }
}
