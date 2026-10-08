package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static io.sitprep.sitprepapi.practice.PracticeFixtures.M;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.approve;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.node;
import static io.sitprep.sitprepapi.practice.PracticeFixtures.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/** The content hash is the immutable identity a run pins — what it must and must not react to. */
class ContentHasherTest {

    @Test
    void prefixedSha256() {
        assertThat(ContentHasher.hashFile(scenario("k", 1))).matches("^sha256:[0-9a-f]{64}$");
    }

    @Test
    void keyOrderAndWhitespaceDoNotChangeTheHash() throws Exception {
        ObjectNode a = scenario("k", 1);
        // Same tree, re-serialized with fields in reverse order and pretty-printed.
        JsonNode reordered = M.readTree(reverse(a).toPrettyString());
        assertThat(ContentHasher.hashFile(reordered)).isEqualTo(ContentHasher.hashFile(a));
    }

    @Test
    void publishingOrRetiringDoesNotChangeTheHash() {
        String draft = ContentHasher.hashFile(scenario("k", 1));
        assertThat(ContentHasher.hashFile(approve(scenario("k", 1), PublishState.PUBLISHED))).isEqualTo(draft);
        assertThat(ContentHasher.hashFile(approve(scenario("k", 1), PublishState.RETIRED))).isEqualTo(draft);
    }

    @Test
    void anyVisibleWordChangesTheHash() {
        String before = ContentHasher.hashFile(scenario("k", 1));
        ObjectNode edited = scenario("k", 1);
        node(edited, 0).put("prompt", "What do you try first ?");
        assertThat(ContentHasher.hashFile(edited)).isNotEqualTo(before);
    }

    @Test
    void identityFieldsChangeTheHash() {
        String v1 = ContentHasher.hashFile(scenario("k", 1));
        assertThat(ContentHasher.hashFile(scenario("k", 2))).isNotEqualTo(v1);
        assertThat(ContentHasher.hashFile(scenario("other", 1))).isNotEqualTo(v1);
    }

    @Test
    void arrayOrderIsMeaningful() {
        String before = ContentHasher.hashFile(scenario("k", 1));
        ObjectNode swapped = scenario("k", 1);
        var choices = (com.fasterxml.jackson.databind.node.ArrayNode) node(swapped, 0).get("choices");
        JsonNode first = choices.remove(0);
        choices.add(first);
        assertThat(ContentHasher.hashFile(swapped)).isNotEqualTo(before);
    }

    private static JsonNode reverse(JsonNode n) {
        if (n.isObject()) {
            ObjectNode out = M.createObjectNode();
            java.util.List<String> names = new java.util.ArrayList<>();
            n.fieldNames().forEachRemaining(names::add);
            java.util.Collections.reverse(names);
            for (String name : names) out.set(name, reverse(n.get(name)));
            return out;
        }
        if (n.isArray()) {
            var out = M.createArrayNode();
            n.forEach(c -> out.add(reverse(c)));
            return out;
        }
        return n;
    }
}
