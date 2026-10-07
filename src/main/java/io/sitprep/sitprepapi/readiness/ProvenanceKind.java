package io.sitprep.sitprepapi.readiness;

/**
 * Where a step comes from. A URL is attached only for gov sources, and only
 * URLs the FE content system already cites.
 */
public enum ProvenanceKind {
    HOUSEHOLD_PLAN("Based on your household plan"),
    LOCAL_RISK("Based on your local risk profile"),
    SITPREP("From SitPrep guidance"),
    READY_GOV("Ready.gov guidance"),
    CDC("CDC guidance");

    private final String label;

    ProvenanceKind(String label) { this.label = label; }

    public String label() { return label; }
}
