package io.sitprep.sitprepapi.dto;

import java.time.Instant;

public record HouseholdManualMemberDto(
        String id,
        String householdId,
        String name,
        String relationship,
        Integer age,
        /**
         * True when the supervising admin has marked this manual member as
         * 18 or older. Defaults to false (minor) — see HouseholdManualMember
         * entity Javadoc for the rationale. Drives the "Adult / Minor" pill
         * in the FE household roster + gates per-group map visibility for
         * non-household groups.
         */
        Boolean isAdult,
        /**
         * The band this person is counted in on the plan (V100): ADULT | TEEN
         * | KID | INFANT. Never null — rows predating the column derive it.
         */
        String band,
        String photoUrl,
        Instant createdAt,
        Instant updatedAt
) {}
