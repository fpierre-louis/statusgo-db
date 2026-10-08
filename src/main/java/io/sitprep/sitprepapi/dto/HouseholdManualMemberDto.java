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
        Instant updatedAt,
        /**
         * The status an owner or admin set FOR this person (V103), or null
         * when unset or lapsed — a SAFE shows for 24h (or for the running
         * check-in), HELP and INJURED until changed. Appended: positional.
         */
        ManualStatus status
) {

    /**
     * @param value     SAFE | HELP | INJURED
     * @param color     derived on the server, the same palette as an account's status
     * @param updatedAt when it was set
     * @param setByName first name of the admin who set it; null if unresolvable
     * @param showUntil when it lapses; <b>null means never</b> (HELP / INJURED)
     */
    public record ManualStatus(
            String value,
            String color,
            Instant updatedAt,
            String setByName,
            Instant showUntil
    ) {}
}
