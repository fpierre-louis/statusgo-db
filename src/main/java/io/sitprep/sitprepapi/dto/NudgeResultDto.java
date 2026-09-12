package io.sitprep.sitprepapi.dto;

/**
 * What a nudge actually did.
 *
 * <p>{@code silent} means the push went out with no sound because the recipient
 * is inside a concealment-sensitive situation (P0-B — see
 * {@code ConcealmentSafetyService}). The sender is told what the transport did,
 * so the UI can say "Reminder sent silently" rather than implying a buzz that
 * deliberately did not happen.
 *
 * <p>It never means delivered. RC-2 vocabulary stands: Sent / Couldn't send /
 * Delivery not confirmed — there is no Delivered.
 */
public record NudgeResultDto(boolean sent, boolean silent) {}
