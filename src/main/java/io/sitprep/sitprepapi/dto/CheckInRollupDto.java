package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;

public record CheckInRollupDto(
        String groupId,
        String groupName,
        boolean active,
        Instant startedAt,
        int total,
        int accounted,
        int safe,
        int help,
        int injured,
        int missing,
        List<Member> members,
        /** When the running check-in ends by itself (the decay window); null when none is running. */
        Instant alertExpiresAt
) {
    public record Member(
            String email,
            String firstName,
            String lastName,
            String profileImageUrl,
            String status,
            Instant statusUpdatedAt,
            boolean accounted
    ) {}
}
