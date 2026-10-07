package io.sitprep.sitprepapi.readiness;

/** Coordination a step needs — distinct from cost. */
public enum EffortBand {
    ON_YOUR_OWN("On your own"),
    WITH_HOUSEHOLD("With your household");

    private final String label;

    EffortBand(String label) { this.label = label; }

    public String label() { return label; }
}
