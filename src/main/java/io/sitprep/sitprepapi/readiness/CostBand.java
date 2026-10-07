package io.sitprep.sitprepapi.readiness;

/** What a step costs. Labels are exact client copy. */
public enum CostBand {
    FREE("Free"),
    USE_WHAT_YOU_HAVE("Use what you have"),
    UNDER_10("Usually under $10"),
    OPTIONAL_PURCHASE("Optional purchase");

    private final String label;

    CostBand(String label) { this.label = label; }

    public String label() { return label; }
}
