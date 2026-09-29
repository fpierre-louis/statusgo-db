package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.AskSearchHitDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * Composer V2 C9h — located tips with topics, found by topic or text
 * regardless of location (owner Q5, AUDIT-C0.5 §Q5).
 *
 * <p>The H2 database is shared with tests that commit, so every assertion is
 * about rows this test made (ids, or a per-run word), never a total.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PostTipTopicsC9hTest {

    @Autowired PostService postService;
    @Autowired AskService askService;
    @Autowired PostRepo postRepo;
    @Autowired BlockService blockService;
    @MockBean NominatimGeocodeService geocode;

    /** A word no other test writes, so text search sees only this run's rows. */
    private String word;

    @BeforeEach
    void setUp() {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
        word = "zq" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String email(String who) {
        return who + "-" + UUID.randomUUID() + "@example.com";
    }

    private PostDto tip(String author, String text, Double lat, Double lng, String... topics) {
        Post p = new Post();
        p.setKind("tip");
        p.setDescription(text);
        p.setLatitude(lat);
        p.setLongitude(lng);
        if (topics.length > 0) p.setHazardTags(Set.of(topics));
        return postService.create(p, author);
    }

    private Set<String> storedTopics(Long id) {
        return postRepo.findById(id).orElseThrow().getHazardTags();
    }

    // ── Topics on write ────────────────────────────────────────────────────

    @Test
    void aTipKeepsKnownTopicsAndDropsUnknownAndOther() {
        Post p = new Post();
        p.setKind("tip");
        p.setDescription("Sandbags at the rec center");
        p.setHazardTags(new java.util.LinkedHashSet<>(List.of("Flood", "extreme_heat", "bogus", "other")));
        PostDto dto = postService.create(p, email("a"));
        assertThat(storedTopics(dto.id())).containsExactlyInAnyOrder("flood", "heat");
        assertThat(dto.community().hazardTags()).containsExactlyInAnyOrder("flood", "heat");
    }

    @Test
    void topicsOnAnyOtherKindAreIgnored() {
        Post p = new Post();
        p.setKind("post");
        p.setDescription("Just a post");
        p.setHazardTags(Set.of("flood"));
        PostDto dto = postService.create(p, email("a"));
        assertThat(storedTopics(dto.id())).isEmpty();
    }

    @Test
    void moreThanThreeTopicsIsA400() {
        assertThrows(IllegalArgumentException.class,
                () -> tip(email("a"), "Too many", null, null, "flood", "heat", "smoke", "tornado"));
    }

    @Test
    void aPatchThatSaysNothingAboutTopicsKeepsThemAndOneThatDoesReplacesThem() {
        String author = email("a");
        PostDto dto = tip(author, "Know your shutoff valve", null, null, "flood", "earthquake");

        Post textOnly = new Post();
        textOnly.setDescription("Know where your water shutoff valve is");
        postService.patch(dto.id(), textOnly, author);
        assertThat(storedTopics(dto.id())).containsExactlyInAnyOrder("flood", "earthquake");

        Post retag = new Post();
        retag.setHazardTags(Set.of("smoke"));
        postService.patch(dto.id(), retag, author);
        assertThat(storedTopics(dto.id())).containsExactly("smoke");

        Post clear = new Post();
        clear.setHazardTags(Set.of());
        postService.patch(dto.id(), clear, author);
        assertThat(storedTopics(dto.id())).isEmpty();
    }

    @Test
    void onlyTheAuthorCanRetagATip() {
        String author = email("a");
        PostDto dto = tip(author, "Keep a radio", null, null, "hurricane");
        Post retag = new Post();
        retag.setHazardTags(Set.of("smoke"));
        try {
            postService.patch(dto.id(), retag, email("stranger"));
        } catch (RuntimeException ignored) {
            // A stranger's PATCH may be refused outright; either way no retag.
        }
        assertThat(storedTopics(dto.id())).containsExactly("hurricane");
    }

    // ── Reach: the feed is radius-bound, search is not ─────────────────────

    @Test
    void aLocatedTipIsRadiusBoundInTheFeedWhileAnUnlocatedOneIsCommunityWide() {
        PostDto located = tip(email("a"), "Salt Lake tip " + word, 40.76, -111.89);
        PostDto anywhere = tip(email("b"), "Anywhere tip " + word, null, null);

        // A viewer in Miami, 10 km radius.
        List<Long> feed = postService.discoverCommunity(25.76, -80.19, 10,
                        EnumSet.of(Post.PostStatus.OPEN, Post.PostStatus.CLAIMED), email("viewer"), 0, 50)
                .stream().map(PostDto::id).toList();
        assertThat(feed).doesNotContain(located.id());
        assertThat(feed).contains(anywhere.id());
    }

    @Test
    void topicSearchIgnoresTheRadius() {
        PostDto slc = tip(email("a"), "Move valuables upstairs", 40.76, -111.89, "flood");
        PostDto miami = tip(email("b"), "Clear the storm drain", 25.76, -80.19, "flood");
        PostDto other = tip(email("c"), "Mask up", 25.76, -80.19, "smoke");

        List<Long> hits = postService.searchCommunityTips("flood", null, email("viewer"), 0, 50)
                .stream().map(PostDto::id).toList();
        assertThat(hits).contains(slc.id(), miami.id());
        assertThat(hits).doesNotContain(other.id());
    }

    @Test
    void textSearchMatchesTheBodyOrTheTopicWord() {
        PostDto byText = tip(email("a"), "Fill the tub " + word, 40.76, -111.89);
        PostDto byTopic = tip(email("b"), "Unplug the basement freezer " + word, 25.76, -80.19, "flood");
        PostDto untagged = tip(email("c"), "Unrelated " + word, null, null);

        List<Long> hits = postService.searchCommunityTips(null, "fill THE tub", email("v"), 0, 50)
                .stream().map(PostDto::id).toList();
        assertThat(hits).contains(byText.id()).doesNotContain(byTopic.id(), untagged.id());

        // "flood" appears in neither body, but one tip is tagged flood.
        List<Long> floodHits = postService.searchCommunityTips(null, "Flood", email("v"), 0, 50)
                .stream().map(PostDto::id).toList();
        assertThat(floodHits).contains(byTopic.id()).doesNotContain(byText.id(), untagged.id());
    }

    @Test
    void likeWildcardsInTheQueryAreLiteral() {
        PostDto plain = tip(email("a"), "Nothing special " + word, null, null);
        List<Long> hits = postService.searchCommunityTips(null, "%", email("v"), 0, 50)
                .stream().map(PostDto::id).toList();
        assertThat(hits).doesNotContain(plain.id());
    }

    @Test
    void onlyGrouplessTipsAreSearched() {
        Post post = new Post();
        post.setKind("post");
        post.setDescription("A post not a tip " + word);
        PostDto notATip = postService.create(post, email("a"));
        PostDto aTip = tip(email("b"), "A real tip " + word, null, null);

        List<Long> hits = postService.searchCommunityTips(null, word, email("v"), 0, 50)
                .stream().map(PostDto::id).toList();
        assertThat(hits).contains(aTip.id()).doesNotContain(notATip.id());
    }

    @Test
    void aBlockedAuthorIsExcluded() {
        String author = email("author");
        String viewer = email("viewer");
        PostDto t = tip(author, "Blocked author tip " + word, null, null, "wildfire");
        blockService.block(viewer, author);

        assertThat(postService.searchCommunityTips("wildfire", word, viewer, 0, 50))
                .extracting(PostDto::id).doesNotContain(t.id());
        assertThat(postService.searchCommunityTips("wildfire", word, email("someone"), 0, 50))
                .extracting(PostDto::id).contains(t.id());
    }

    @Test
    void anUnknownTopicIsA400() {
        assertThrows(IllegalArgumentException.class,
                () -> postService.searchCommunityTips("volcano", null, email("v"), 0, 50));
        assertThrows(IllegalArgumentException.class,
                () -> postService.searchCommunityTips("other", null, email("v"), 0, 50));
    }

    @Test
    void aResultKeepsItsPlaceTag() {
        PostDto t = tip(email("a"), "Placed tip " + word, 40.76, -111.89, "earthquake");
        Post row = postRepo.findById(t.id()).orElseThrow();
        row.setPlaceLabel("Sugar House");
        postRepo.save(row);

        PostDto hit = postService.searchCommunityTips("earthquake", word, email("v"), 0, 50).stream()
                .filter(d -> d.id().equals(t.id())).findFirst().orElseThrow();
        assertThat(hit.placeLabel()).isEqualTo("Sugar House");
    }

    // ── Ask search: one box over one content universe ──────────────────────

    @Test
    void askSearchIncludesCommunityTipsForASignedInViewerOnly() {
        PostDto t = tip(email("a"), "Ask-visible tip " + word, 40.76, -111.89, "blizzard");
        Post row = postRepo.findById(t.id()).orElseThrow();
        row.setPlaceLabel("Sugar House");
        postRepo.save(row);

        List<AskSearchHitDto> signedIn = askService.search(word, email("viewer"), Set.of("blizzard"));
        AskSearchHitDto hit = signedIn.stream()
                .filter(h -> "community-tip".equals(h.getKind()) && h.getKey().equals(String.valueOf(t.id())))
                .findFirst().orElseThrow();
        assertThat(hit.getHref()).isEqualTo("/community/posts/" + t.id());
        assertThat(hit.getPlaceLabel()).isEqualTo("Sugar House");
        assertThat(hit.getTitle()).isEqualTo("Ask-visible tip " + word);
        assertThat(hit.isHazardMatched()).isTrue();

        List<AskSearchHitDto> anonymous = askService.search(word, null, Set.of());
        assertThat(anonymous).noneMatch(h -> "community-tip".equals(h.getKind()));
    }
}
