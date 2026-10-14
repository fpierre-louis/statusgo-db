package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Body of {@code PATCH /api/groups/{id}/settings} and
 * {@code PATCH /api/groups/{id}/household-details} (AD-01, B5 2026-10-14).
 *
 * <p>Why not the entity, and why not a plain record: the settings Save used to
 * PUT a whole group it had read when the page mounted, so a typo fix could end
 * (or restart) a check-in another admin ran, revert roster changes, and undo a
 * subgroup link made on the same page. A PATCH only means "these fields" if the
 * server can tell a key that was SENT from one that was left out, so this
 * keeps the set of keys present ({@link #has}) next to their values.</p>
 *
 * <ul>
 *   <li>A key that is absent is unchanged.</li>
 *   <li>A key sent as {@code null} or blank clears the field (text) or the
 *       coordinate.</li>
 *   <li>A key outside the allowlist is a <b>400 naming it</b>, never silently
 *       ignored: a client that sends {@code alert} or {@code memberEmails} here
 *       has a bug worth hearing about, and the old PUT's habit of quietly
 *       dropping them is how nobody noticed the whole-object save.</li>
 * </ul>
 */
public final class GroupSettingsRequest {

    public static final String DESCRIPTION = "description";
    public static final String PRIVACY = "privacy";
    public static final String ADDRESS = "address";
    public static final String LATITUDE = "latitude";
    public static final String LONGITUDE = "longitude";
    public static final String ZIP_CODE = "zipCode";
    public static final String BUSINESS_CATEGORY = "businessCategory";
    public static final String WEBSITE_URL = "websiteUrl";
    public static final String GROUP_TYPE = "groupType";
    public static final String GROUP_NAME = "groupName";

    /** What {@code PATCH /settings} accepts. The name has its own endpoint ({@code PATCH /name}). */
    public static final Set<String> SETTINGS_KEYS = Set.of(
            DESCRIPTION, PRIVACY, ADDRESS, LATITUDE, LONGITUDE, ZIP_CODE,
            BUSINESS_CATEGORY, WEBSITE_URL, GROUP_TYPE);

    /** What {@code PATCH /household-details} accepts: the one save CreateHouseholdGroup makes. */
    public static final Set<String> HOUSEHOLD_DETAIL_KEYS = Set.of(
            GROUP_NAME, DESCRIPTION, ADDRESS, LATITUDE, LONGITUDE, ZIP_CODE);

    private final Set<String> present;
    private final Map<String, JsonNode> values;

    private GroupSettingsRequest(Set<String> present, Map<String, JsonNode> values) {
        this.present = Collections.unmodifiableSet(present);
        this.values = values;
    }

    /**
     * Read a body against an allowlist. 400 when the body is not a JSON object,
     * is empty, or carries a key outside {@code allowed}.
     */
    public static GroupSettingsRequest parse(JsonNode body, Set<String> allowed) {
        if (body == null || body.isNull() || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send the fields to change as a JSON object");
        }
        Set<String> present = new LinkedHashSet<>();
        Map<String, JsonNode> values = new java.util.HashMap<>();
        Set<String> refused = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> it = body.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!allowed.contains(e.getKey())) {
                refused.add(e.getKey());
                continue;
            }
            present.add(e.getKey());
            values.put(e.getKey(), e.getValue());
        }
        if (!refused.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "These fields can't be changed here: " + String.join(", ", refused));
        }
        if (present.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to change");
        }
        return new GroupSettingsRequest(present, values);
    }

    public boolean has(String key) {
        return present.contains(key);
    }

    public Set<String> keys() {
        return present;
    }

    /** The text value, trimmed; null when sent as null or blank. 400 when it isn't text. */
    public String text(String key) {
        JsonNode n = values.get(key);
        if (n == null || n.isNull()) return null;
        if (!n.isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be text");
        }
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }

    /** The number, or null when sent as null. 400 when it isn't a number. */
    public Double number(String key) {
        JsonNode n = values.get(key);
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            String t = n.asText().trim();
            if (t.isEmpty()) return null;
            try {
                return Double.valueOf(t);
            } catch (NumberFormatException ignored) {
                // fall through to the 400
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a number");
    }
}
