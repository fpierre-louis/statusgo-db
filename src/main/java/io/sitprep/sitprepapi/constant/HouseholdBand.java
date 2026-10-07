package io.sitprep.sitprepapi.constant;

import java.util.Locale;

/**
 * The age band a person is counted in on a household plan — the four people
 * columns of {@code Demographic} (adults · teens · kids · infants).
 *
 * <p><b>One rule, used everywhere a band is derived</b> (the V100 backfill, the
 * manual-member write path, the composition read):</p>
 * <ul>
 *   <li>an ACCOUNT is {@link #ADULT} — the ToS 18+ attestation at signup is the
 *       app's legal age gate — unless it claimed a manual spot whose band was
 *       not ADULT, in which case {@code household_member_band} holds that band;</li>
 *   <li>a MANUAL member uses its stored band; with none stored,
 *       {@link #derive(Boolean, Integer)}: {@code isAdult} → ADULT, else
 *       {@code 0 < age < 2} → INFANT, {@code age >= 13} → TEEN, else KID
 *       (an age-less minor is a kid — the same answer the FE gave).</li>
 * </ul>
 */
public enum HouseholdBand {
    ADULT, TEEN, KID, INFANT;

    /** Deterministic band from the legacy fields. Never null. */
    public static HouseholdBand derive(Boolean isAdult, Integer age) {
        if (Boolean.TRUE.equals(isAdult)) return ADULT;
        if (age != null && age > 0 && age < 2) return INFANT;
        if (age != null && age >= 13) return TEEN;
        return KID;
    }

    /**
     * Lenient parse of a wire value: the enum names plus the FE's lower-case
     * figure keys ({@code adult | teen | kid | child | infant}). Null/blank →
     * null; anything else → 400 at the caller.
     */
    public static HouseholdBand parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "ADULT", "ADULTS" -> ADULT;
            case "TEEN", "TEENS", "TEENAGER" -> TEEN;
            case "KID", "KIDS", "CHILD", "CHILDREN" -> KID;
            case "INFANT", "INFANTS", "BABY" -> INFANT;
            default -> throw new IllegalArgumentException("Unknown band: " + raw);
        };
    }
}
