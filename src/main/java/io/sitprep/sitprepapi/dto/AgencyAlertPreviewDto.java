package io.sitprep.sitprepapi.dto;

/**
 * What an agency alert would reach if it were sent now (group page AG-07).
 *
 * <p>A COUNT only, never a recipient list: the composer shows "Reaches about N
 * people" on its confirm step so an operator sees the reach before a send that
 * can't be unsent. Emails stay on the server.</p>
 *
 * @param recipientCount distinct people the send would snapshot right now
 * @param recencyDays    the last-seen window the count uses (people whose
 *                       location is older than this are not reached)
 */
public record AgencyAlertPreviewDto(int recipientCount, int recencyDays) {}
