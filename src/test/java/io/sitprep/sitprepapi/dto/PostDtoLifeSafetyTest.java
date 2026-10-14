package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.sitprep.sitprepapi.domain.Post;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit TK-11 (lane B6): {@code lifeSafety} is a top-level property on the
 * wire, true for a flagged or do-not-enter work order and for a project whose
 * hottest open child is life-safety, false otherwise.
 */
class PostDtoLifeSafetyTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static Post task(Map<String, Object> wd) {
        Post p = new Post();
        p.setId(1L);
        p.setKind("task");
        p.setTitle("t");
        p.setRequesterEmail("a@x.com");
        p.setStatus(Post.PostStatus.OPEN);
        p.setPriority(Post.PostPriority.MEDIUM);
        p.setCreatedAt(Instant.parse("2026-10-13T10:00:00Z"));
        p.setWorkDetails(wd);
        return p;
    }

    private JsonNode wire(PostDto dto) throws Exception {
        return json.readTree(json.writeValueAsString(dto));
    }

    @Test
    void flaggedTaskIsLifeSafetyOnTheWire() throws Exception {
        JsonNode n = wire(PostDto.fromEntity(task(new HashMap<>(Map.of("occupantTrapped", true)))));
        assertThat(n.get("lifeSafety").asBoolean()).isTrue();
    }

    @Test
    void doNotEnterSiteIsLifeSafety_plainTaskIsNot() throws Exception {
        assertThat(wire(PostDto.fromEntity(task(new HashMap<>(Map.of("siteSafeToEnter", "unsafeDoNotEnter")))))
                .get("lifeSafety").asBoolean()).isTrue();
        assertThat(wire(PostDto.fromEntity(task(null))).get("lifeSafety").asBoolean()).isFalse();
    }

    @Test
    void projectReadsItsRollup() {
        Post p = task(null);
        p.setKind("project");
        PostDto hot = PostDto.fromEntity(p).withProjectRollup(new PostDto.ProjectRollup(
                2, 1, 0, 0, 1, 0, "1/2 done", Post.PostPriority.URGENT, true, 9L));
        PostDto calm = PostDto.fromEntity(p).withProjectRollup(new PostDto.ProjectRollup(
                2, 1, 0, 0, 1, 0, "1/2 done", Post.PostPriority.LOW, false, 9L));
        assertThat(hot.lifeSafety()).isTrue();
        assertThat(calm.lifeSafety()).isFalse();
    }
}
