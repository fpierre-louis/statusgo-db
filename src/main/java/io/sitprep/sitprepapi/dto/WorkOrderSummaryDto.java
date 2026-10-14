package io.sitprep.sitprepapi.dto;

/**
 * {@code GET /api/groups/{groupId}/work-order-summary} (audit TK-11): a
 * group's work-order counts, computed server-side so the admin dashboard
 * stops counting a client list (which miscounted with projects).
 *
 * <p>Real tasks only: a project's children count, the project container
 * does not. {@code done} folds DONE + CLOSED. {@code open} is the OPEN
 * status; {@code active} is every non-terminal row (OPEN, CLAIMED,
 * IN_PROGRESS, DRAFT, LIABILITY_PENDING, VERIFICATION_PENDING).
 * {@code lifeSafetyOpen} / {@code urgentOpen} count active rows only.</p>
 */
public record WorkOrderSummaryDto(
        String groupId,
        int total,
        int active,
        int open,
        int claimed,
        int inProgress,
        int done,
        int cancelled,
        int archived,
        int lifeSafetyOpen,
        int urgentOpen
) {}
