package io.sitprep.sitprepapi.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.dto.HouseholdPlanDto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A fingerprint of the household plan content that PRINTS (printables P8).
 *
 * <p>The paper carries the household's name and address, who is covered,
 * meeting spots, destinations and starting points, the menu, contacts, and who
 * needs help. If any of that changes, a printed copy is out of date. Rather
 * than hook every service that writes a plan piece, the plan document is
 * fingerprinted at print time and compared on every read.</p>
 *
 * <p>Canonical, so only real content moves it:</p>
 * <ul>
 *   <li>ids, version counters and every timestamp ({@code *At},
 *       {@code *Date}, {@code created}/{@code updated}) are dropped: an
 *       unrelated save or a re-insert with a new id is not a change on paper;</li>
 *   <li>object keys are sorted and list elements are sorted by their canonical
 *       form, so a reorder (or a repository returning rows in another order)
 *       does not count;</li>
 *   <li>go bags (their own paper) and standing conditions (operational state,
 *       stamped "current when printed") are excluded.</li>
 * </ul>
 */
public final class PlanPrintFingerprint {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private PlanPrintFingerprint() {}

    public static String of(HouseholdPlanDto doc) {
        return of(doc, null);
    }

    /** With the application's mapper, so entities serialize as in the API response. */
    public static String of(HouseholdPlanDto doc, ObjectMapper appMapper) {
        if (doc == null) return null;
        ObjectMapper mapper = appMapper != null ? appMapper : MAPPER;
        Map<String, Object> printed = new TreeMap<>();
        printed.put("name", doc.name());
        printed.put("address", doc.address());
        printed.put("demographic", doc.demographic());
        printed.put("meetingPlaces", doc.meetingPlaces());
        printed.put("evacuationPlans", doc.evacuationPlans());
        printed.put("originLocations", doc.originLocations());
        printed.put("mealPlan", doc.mealPlan());
        printed.put("contactGroups", doc.contactGroups());
        printed.put("supportProfiles", doc.supportProfiles());
        printed.put("supportAssignments", doc.supportAssignments());
        JsonNode canonical = canonicalize(mapper.valueToTree(printed));
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint the plan document", e);
        }
    }

    static boolean ignoredKey(String key) {
        if (key == null) return true;
        String k = key.toLowerCase();
        return k.equals("id") || k.equals("version")
                || k.endsWith("at") && key.length() > 2 && Character.isUpperCase(key.charAt(key.length() - 2))
                || k.endsWith("date") || k.startsWith("created") || k.startsWith("updated")
                || k.equals("lastmodified");
    }

    static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<String> keys = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) keys.add(it.next());
            keys.sort(String::compareTo);
            for (String k : keys) {
                if (ignoredKey(k)) continue;
                JsonNode v = node.get(k);
                if (v == null || v.isNull()) continue; // absent and null print the same
                out.set(k, canonicalize(v));
            }
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(n -> items.add(canonicalize(n)));
            items.sort((a, b) -> a.toString().compareTo(b.toString()));
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            items.forEach(out::add);
            return out;
        }
        return node;
    }
}
