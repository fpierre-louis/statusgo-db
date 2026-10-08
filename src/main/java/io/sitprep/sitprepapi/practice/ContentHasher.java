package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

/**
 * The immutable identity of a Practice content version:
 * {@code sha256:<hex>} over canonical JSON of {@code {key, version, kind, content}}.
 *
 * <p>Canonical means object keys sorted at every depth, array order kept,
 * no whitespace — so reformatting a file or reordering its keys does not
 * change the hash, while changing any word a person reads does. The
 * {@code lifecycle} block is excluded on purpose (see {@link PracticeContent}).</p>
 */
public final class ContentHasher {

    private ContentHasher() {}

    public static final String PREFIX = "sha256:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Hash of a whole content file tree (its lifecycle is ignored). */
    public static String hashFile(JsonNode fileRoot) {
        ObjectNode identity = JsonNodeFactory.instance.objectNode();
        identity.set("key", fileRoot.path("key"));
        identity.set("version", fileRoot.path("version"));
        identity.set("kind", fileRoot.path("kind"));
        identity.set("content", fileRoot.path("content"));
        return PREFIX + sha256Hex(canonical(identity));
    }

    static String canonical(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sorted(node));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize practice content", e);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            it.forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) out.set(name, sorted(node.get(name)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) out.add(sorted(child));
            return out;
        }
        return node;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
