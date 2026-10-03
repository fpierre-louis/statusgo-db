package io.sitprep.sitprepapi.notifications;

/**
 * Who or what a notification is FROM — the attribution hierarchy in the audit
 * ("Attribution Hierarchy Findings"). The avatar answers this; the badge answers
 * what kind of event it is.
 */
public enum NotificationSourceType {
    /** NWS / USGS / FEMA / a verified agency. Rendered with the official seal. */
    OFFICIAL_ALERT("OFFICIAL_SEAL"),
    /** A geospatial hazard with no issuing authority attached. */
    HAZARD("HAZARD_MARK"),
    HOUSEHOLD("HOUSEHOLD"),
    GROUP("GROUP"),
    /** A person — profile photo, initials fallback. */
    USER("USER_PHOTO"),
    /** SitPrep itself: reminders, account notices. */
    SYSTEM("SITPREP_SEAL"),
    TOKEN("TOKEN_MEDALLION");

    private final String avatarKind;

    NotificationSourceType(String avatarKind) {
        this.avatarKind = avatarKind;
    }

    public String avatarKind() {
        return avatarKind;
    }
}
