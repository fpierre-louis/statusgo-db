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
        List<Member> members
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
