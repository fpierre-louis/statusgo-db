package io.sitprep.sitprepapi.practice;

import java.util.Arrays;
import java.util.Optional;

/**
 * The roles a Together session rotates through (redesign FR-8). The server owns which are
 * adult-only: content only names a role, it can never mark one adult-only or child-safe.
 * Nothing records who held a role.
 */
public enum TogetherRole {
    READER("Reader", false),
    DECISION_MAKER("Decision maker", false),
    CHECKER("Checker", false),
    PET_BUDDY("Pet buddy", false),
    HEAD_COUNTER("Head counter", false),
    UTILITIES("Utilities", true),
    MEDICAL("Medical", true),
    DRIVER("Driver", true);

    private final String label;
    private final boolean adultOnly;

    TogetherRole(String label, boolean adultOnly) {
        this.label = label;
        this.adultOnly = adultOnly;
    }

    public String label() { return label; }

    public boolean adultOnly() { return adultOnly; }

    public static Optional<TogetherRole> parse(String key) {
        return key == null ? Optional.empty()
                : Arrays.stream(values()).filter(r -> r.name().equals(key)).findFirst();
    }

    /** Non-role turn targets: the grown-up, everyone, or whoever wants it. */
    public static final java.util.Set<String> SPECIAL = java.util.Set.of("ADULT", "ALL", "ANY");

    /** The label shown for a turn's role key ("Head counter", "Adult", "Everyone", "Anyone"). */
    public static String labelFor(String roleKey) {
        if ("ADULT".equals(roleKey)) return "Adult";
        if ("ALL".equals(roleKey)) return "Everyone";
        if ("ANY".equals(roleKey)) return "Anyone";
        return parse(roleKey).map(TogetherRole::label).orElse(roleKey);
    }
}
