package io.sitprep.sitprepapi.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum RSEventVisibility {
    DISCOVERABLE_PUBLIC,
    GROUP_ONLY,
    INVITE_ONLY;

    @JsonCreator
    public static RSEventVisibility fromJson(Object raw) {
        if (raw == null) return null;
        final String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);

        // Accept UI/legacy values
        switch (v) {
            case "public":
            case "discoverable_public":
            case "discoverablepublic":
            case "public_discoverable":
            case "true": // sometimes UI mistakenly passes boolean-ish
                return DISCOVERABLE_PUBLIC;

            case "group":
            case "group_only":
            case "grouponly":
            case "private": // some UIs equate private to group-only
                return GROUP_ONLY;

            case "invite":
            case "invite_only":
            case "inviteonly":
            case "invitation":
                return INVITE_ONLY;

            default:
                // Also accept exact enum names (case-insensitive)
                try {
                    return RSEventVisibility.valueOf(v.toUpperCase(Locale.ROOT));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Unknown visibility: " + raw);
                }
        }
    }

    @JsonValue
    public String toJson() {
        // return a consistent value if you want the API to always respond the same way
        return name();
    }
}