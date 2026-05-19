package io.sitprep.sitprepapi.constant;

/**
 * Discrete things a member can be allowed to do inside a group.
 *
 * <p>Phase 4 of docs/BUSINESS_MODEL.md — formalized Owner / Admin /
 * Member RBAC. Each {@link GroupRole} carries a fixed permission set;
 * both the backend authorization helpers and the frontend UI gating
 * read from that one matrix.</p>
 */
public enum GroupPermission {
    /** Approve / reject / remove members; add or remove admins. */
    MANAGE_MEMBERS,
    /** Issue, assign, and claim group work orders. */
    MANAGE_TASKS,
    /** Activate / end group check-ins and alerts. */
    MANAGE_ALERTS,
    /** Edit the group's name, description, and logo. */
    EDIT_GROUP,
    /** Open the org admin dashboard. */
    VIEW_ADMIN_DASHBOARD,
    /** Change the group's organization plan tier. Owner only. */
    MANAGE_PLAN,
    /** Delete the group. Owner only. */
    DELETE_GROUP,
    /** Transfer group ownership to another member. Owner only. */
    TRANSFER_OWNERSHIP
}
