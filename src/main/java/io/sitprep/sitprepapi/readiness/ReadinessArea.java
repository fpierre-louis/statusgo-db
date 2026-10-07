package io.sitprep.sitprepapi.readiness;

/**
 * The seven "Ready for More" areas. Render order = declaration order
 * (CONTRACT.md §1). Titles and one-line descriptions are client copy.
 */
public enum ReadinessArea {
    DOCUMENTS("Documents", "Copies of the papers you'd need, in one place you can grab."),
    OUTAGE("Outage Ready", "Small steps that make a power outage easier to ride out."),
    SUPPLIES("Supplies", "A few things on hand at home, built up over time."),
    PEOPLE("People", "The people you'd reach, and who would reach you."),
    EVACUATION("Evacuation", "More than one way out, worked out before you need it."),
    PRACTICE("Practice", "Short run-throughs so the plan feels familiar."),
    LOCAL_RISKS("Local Risks", "Steps for the hazards most common where you live.");

    private final String title;
    private final String description;

    ReadinessArea(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String title() { return title; }
    public String description() { return description; }
}
