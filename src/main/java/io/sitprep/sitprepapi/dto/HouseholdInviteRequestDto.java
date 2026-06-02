package io.sitprep.sitprepapi.dto;

import java.time.Instant;

/**
 * Wire shape for a household invite request. Includes the requester and
 * candidate names so the admin's InviteApprovalSheet renders without a
 * second round trip.
 */
public record HouseholdInviteRequestDto(
        String id,
        String householdId,
        String status,
        String requesterEmail,
        String requesterFirstName,
        String requesterLastName,
        String requesterProfileImageUrl,
        String candidateEmail,
        String candidateFirstName,
        String candidateLastName,
        String candidateProfileImageUrl,
        Instant createdAt,
        Instant resolvedAt,
        String resolverEmail
) {}
