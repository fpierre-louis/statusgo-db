package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.PostRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * HR6 (V93) — a hazard report reads "Reported by a neighbor": no other viewer
 * receives the reporter's identity, the reporter still sees their own, and
 * Show my name opts in.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HazardAnonymityTest {

    @Autowired PostService postService;
    @Autowired HazardService hazardService;
    @Autowired UserInfoRepo users;
    @Autowired PostRepo posts;
    @Autowired ObjectMapper objectMapper;
    @MockBean NominatimGeocodeService geocode;

    private String reporter;

    @BeforeEach
    void setUp() {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
        reporter = "hr6-reporter-" + System.nanoTime() + "@example.com";
        UserInfo u = new UserInfo();
        u.setUserEmail(reporter);
        u.setUserFirstName("Rhea");
        u.setUserLastName("Porter");
        users.save(u);
    }

    private Long report(Boolean showName) {
        return hazardService.report(new HazardService.ReportRequest(
                "flood", 40.39, -111.85, "Water over the road", List.of(), 40.3901, -111.8501, showName),
                reporter).id();
    }

    private String json(PostDto d) throws Exception {
        return objectMapper.writeValueAsString(d);
    }

    @Test
    void byDefaultNoOtherViewerLearnsWhoReported() throws Exception {
        Long id = report(null);
        assertThat(posts.findById(id).orElseThrow().getRequesterEmail()).isEqualTo(reporter); // kept for limits

        PostDto read = postService.findDtoById(id, "neighbor@example.com").orElseThrow();
        String wire = json(read);
        assertThat(read.requesterEmail()).isNull();
        assertThat(read.requesterFirstName()).isNull();
        assertThat(wire).doesNotContain(reporter).doesNotContain("Rhea").doesNotContain("Porter");
        assertThat(objectMapper.readTree(wire).path("community").path("authorHidden").asBoolean()).isTrue();

        // A signed-out read and the reporter's public profile list say no more.
        assertThat(json(postService.findDtoById(id, null).orElseThrow())).doesNotContain(reporter);
        assertThat(postService.listPublicProfilePosts(reporter, "neighbor@example.com", 20))
                .noneMatch(d -> id.equals(d.id()));
        assertThat(postService.listRequestedBy(reporter, "neighbor@example.com"))
                .noneMatch(d -> id.equals(d.id()));
        // The crawler preview of a shared link says "a neighbor".
        assertThat(postService.findPublicSharePreview(id).orElseThrow().description())
                .startsWith("From a neighbor").doesNotContain("Rhea");
    }

    @Test
    void theReporterStillSeesThemselves() throws Exception {
        Long id = report(false);
        PostDto mine = postService.findDtoById(id, reporter).orElseThrow();
        assertThat(mine.requesterEmail()).isEqualTo(reporter);
        assertThat(mine.requesterFirstName()).isEqualTo("Rhea");
        JsonNode c = objectMapper.readTree(json(mine)).path("community");
        assertThat(c.path("authorHidden").asBoolean()).isTrue(); // so the card can say only they see it
    }

    @Test
    void showMyNameOptsIn() throws Exception {
        Long id = report(true);
        PostDto read = postService.findDtoById(id, "neighbor@example.com").orElseThrow();
        assertThat(read.requesterEmail()).isEqualTo(reporter);
        assertThat(read.requesterFirstName()).isEqualTo("Rhea");
        assertThat(objectMapper.readTree(json(read)).path("community").path("authorHidden").asBoolean()).isFalse();
    }

    @Test
    void anOrdinaryPostCannotHideItsAuthor() {
        Post p = new Post();
        p.setKind("post");
        p.setDescription("Hello");
        PostDto created = postService.create(p, reporter);
        assertThat(posts.findById(created.id()).orElseThrow().isAuthorHidden()).isFalse();
    }
}
