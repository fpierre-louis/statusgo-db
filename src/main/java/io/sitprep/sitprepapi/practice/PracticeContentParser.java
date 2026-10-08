package io.sitprep.sitprepapi.practice;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.practice.PracticeContent.ContentFile;
import io.sitprep.sitprepapi.practice.PracticeContent.Version;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads one content file. Strict: an unknown property is an error, so a typo
 * in a content file ("feeback") fails loudly instead of silently dropping copy.
 */
public final class PracticeContentParser {

    private PracticeContentParser() {}

    private static final ObjectMapper STRICT = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static Version parse(InputStream in, String resourcePath) throws IOException {
        JsonNode root = STRICT.readTree(in);
        if (root == null || !root.isObject()) {
            throw new IOException(resourcePath + ": not a JSON object");
        }
        ContentFile file = STRICT.treeToValue(root, ContentFile.class);
        return new Version(file, ContentHasher.hashFile(root), resourcePath);
    }
}
