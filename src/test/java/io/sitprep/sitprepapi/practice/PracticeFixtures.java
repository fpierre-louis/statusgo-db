package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds Practice content files as JSON trees so each test can break exactly
 * one thing in an otherwise valid file. Copy here is fixture copy, not
 * reviewed guidance.
 */
final class PracticeFixtures {

    private PracticeFixtures() {}

    static final ObjectMapper M = new ObjectMapper();

    /** A valid two-node DRAFT scenario: start → (check | wait) → outcomes. */
    static ObjectNode scenario(String key, int version) {
        ObjectNode root = M.createObjectNode();
        root.put("schema", 1);
        root.put("key", key);
        root.put("version", version);
        root.put("kind", "ADULT_SCENARIO");

        ObjectNode lifecycle = root.putObject("lifecycle");
        lifecycle.put("publishState", "DRAFT");
        lifecycle.putObject("safetyReview").put("status", "PENDING");

        ObjectNode content = root.putObject("content");
        content.put("title", "Fixture reconnect");
        content.put("summary", "Practice reaching your household when calls are not getting through.");
        content.put("objective", "Text a short status first.");
        content.put("estimatedMinutes", 5);
        content.putArray("sources").addObject()
                .put("label", "Ready.gov — Make a Plan").put("url", "https://www.ready.gov/plan");

        ObjectNode tags = content.putObject("tags");
        tags.putObject("communicated_clearly").put("text", "You sent a short, clear status.");
        tags.putObject("needs_contact_review")
                .put("text", "Worth checking your out-of-area contact is current.")
                .put("action", "OPEN_EMERGENCY_CONTACTS");

        ObjectNode scenario = content.putObject("scenario");
        scenario.put("startNode", "start");
        ArrayNode nodes = scenario.putArray("nodes");
        ObjectNode start = nodes.addObject();
        start.put("key", "start");
        start.put("title", "Calls are failing");
        start.put("body", "Phone calls keep dropping. Your household is spread across town.");
        start.put("prompt", "What do you try first?");
        ArrayNode startChoices = start.putArray("choices");
        choice(startChoices, "text", "Send a short text", "Texts often get through when calls do not.",
                "second", "communicated_clearly");
        choice(startChoices, "call", "Keep calling", "Repeated calls add to network load.",
                "second", "needs_contact_review");

        ObjectNode second = nodes.addObject();
        second.put("key", "second");
        second.put("body", "Someone replies an hour later.");
        second.put("prompt", "What next?");
        ArrayNode secondChoices = second.putArray("choices");
        choice(secondChoices, "plan", "Follow the household plan", "Your plan already answers this.", "reconnected");
        choice(secondChoices, "improvise", "Make a new plan", "A plan made now is harder to share.", "partial");

        ArrayNode outcomes = scenario.putArray("outcomes");
        outcomes.addObject().put("key", "reconnected").put("title", "Everyone reconnected")
                .put("body", "Your household found each other.").put("defaultAction", "OPEN_EMERGENCY_CONTACTS");
        outcomes.addObject().put("key", "partial").put("title", "Mostly reconnected")
                .put("body", "You found each other, with some back and forth.").put("defaultAction", "OPEN_EVACUATION_PLAN");
        return root;
    }

    /** A valid DRAFT family activity (body schema is Phase D; any object passes Phase A). */
    static ObjectNode family(String key, int version) {
        ObjectNode root = M.createObjectNode();
        root.put("schema", 1);
        root.put("key", key);
        root.put("version", version);
        root.put("kind", "FAMILY_ACTIVITY");
        ObjectNode lifecycle = root.putObject("lifecycle");
        lifecycle.put("publishState", "DRAFT");
        lifecycle.putObject("safetyReview").put("status", "PENDING");
        ObjectNode content = root.putObject("content");
        content.put("title", "Fixture kit sort");
        content.put("summary", "Sort kit items together.");
        content.put("estimatedMinutes", 10);
        content.put("facilitation", "Read each item aloud and decide together where it goes.");
        content.putArray("ageGuidance").add("Family / all ages");
        content.putArray("sources").addObject()
                .put("label", "Ready.gov — Build a Kit").put("url", "https://www.ready.gov/kit");
        content.putObject("tags").putObject("identified_core_kit_items").put("text", "You found the basics.");
        content.putObject("activity").putArray("items").addObject().put("label", "Water");
        return root;
    }

    static void choice(ArrayNode choices, String key, String label, String feedback, String next, String... tags) {
        ObjectNode c = choices.addObject();
        c.put("key", key);
        c.put("label", label);
        c.put("feedback", feedback);
        c.put("next", next);
        ArrayNode t = c.putArray("tags");
        for (String tag : tags) t.add(tag);
    }

    /** Marks a tree as approved + in {@code state}, pinning the review to its current hash. */
    static ObjectNode approve(ObjectNode root, PublishState state) {
        ObjectNode lifecycle = (ObjectNode) root.get("lifecycle");
        lifecycle.put("publishState", state.name());
        ObjectNode review = lifecycle.putObject("safetyReview");
        review.put("status", "APPROVED");
        review.put("reviewedAt", "2026-10-08");
        review.put("reviewedBy", "reviewer@example.com");
        review.put("reviewedContentHash", ContentHasher.hashFile(root));
        if (state == PublishState.PUBLISHED || state == PublishState.RETIRED) lifecycle.put("publishedAt", "2026-10-08");
        if (state == PublishState.RETIRED) lifecycle.put("retiredAt", "2026-10-09");
        return root;
    }

    static Version version(ObjectNode root) {
        String path = "practice/content/" + root.path("key").asText() + "/v" + root.path("version").asInt() + ".json";
        return versionAt(root, path);
    }

    static Version versionAt(ObjectNode root, String path) {
        try {
            byte[] bytes = M.writeValueAsBytes(root);
            return PracticeContentParser.parse(new ByteArrayInputStream(bytes), path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectNode content(ObjectNode root) {
        return (ObjectNode) root.get("content");
    }

    static ObjectNode node(ObjectNode root, int i) {
        return (ObjectNode) content(root).get("scenario").get("nodes").get(i);
    }

    static ObjectNode choiceAt(ObjectNode root, int node, int choice) {
        return (ObjectNode) node(root, node).get("choices").get(choice);
    }
}
