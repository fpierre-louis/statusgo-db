package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;

import java.util.List;

/**
 * What a join or an invite redeem tells the caller: whether they are in, and
 * nothing about anyone else.
 *
 * <p>Until 2026-10-14 both {@code POST /groups/{id}/members/join} and
 * {@code POST /invites/{token}/redeem} returned the whole {@link Group}
 * entity. A private group (every household) puts a self-joiner in
 * {@code pendingMemberEmails}, so any signed-in account could call join on any
 * id and read back the roster, the requesters, the home address and its
 * coordinate, the join code and the Stripe ids (group page AUDIT JN-02).</p>
 *
 * <p>{@code groupName} and {@code groupType} ride along because the frontend
 * patches its group list from this response (MeContext {@code addGroupToMe});
 * both are on the public preview already. {@code memberCount} is the roster
 * size, never the roster.</p>
 *
 * @param status      {@code MEMBER} (in now, including owner/admin) or
 *                    {@code PENDING} (an admin has to approve)
 * @param viewerRole  {@code OWNER | ADMIN | MEMBER | PENDING}, the caller's
 *                    role after the call (the discover DTO's vocabulary)
 */
public record JoinOutcomeDto(
        String status,
        String groupId,
        String groupName,
        String groupType,
        String viewerRole,
        int memberCount
) {
    public static final String MEMBER = "MEMBER";
    public static final String PENDING = "PENDING";

    /** The outcome for {@code email} as {@code group} now stands. */
    public static JoinOutcomeDto of(Group group, String email) {
        GroupRole role = GroupRole.fromGroup(group, email);
        boolean in = role != GroupRole.NONE;
        String viewerRole = in ? role.name() : PENDING;
        List<String> members = group.getMemberEmails();
        return new JoinOutcomeDto(
                in ? MEMBER : PENDING,
                group.getGroupId(),
                group.getGroupName(),
                group.getGroupType(),
                viewerRole,
                members == null ? 0 : members.size());
    }
}
