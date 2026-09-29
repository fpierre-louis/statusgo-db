package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Composer V2 C9 (a–e) — the post rules, through the REAL create / patch path
 * (H2, test profile). The reverse-geocode is mocked to null (no network), and
 * StorageService says every image belongs to the author, so the C0b attach
 * guard passes and these tests see only the C9 rules.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PostRulesC9Test {

    private static final String AUTHOR = "author@example.com";

    @Autowired PostService postService;
    @Autowired PostRepo postRepo;
    @Autowired io.sitprep.sitprepapi.repo.UserInfoRepo userInfoRepo;
    @MockBean NominatimGeocodeService geocode;
    @MockBean StorageService storage;

    @BeforeEach
    void setUp() {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
        when(storage.ownerOf(anyString()))
                .thenReturn(new StorageService.ObjectOwner(true, StorageService.uploaderTag(AUTHOR)));
    }

    private static Post post(String kind) {
        Post p = new Post();
        p.setKind(kind);
        return p;
    }

    private Post stored(PostDto dto) {
        return postRepo.findById(dto.id()).orElseThrow();
    }

    // ── C9a · the description rule ─────────────────────────────────────────

    @Test
    void aPhotoOnlyPostOrTipSaves() {
        for (String kind : List.of("post", "tip")) {
            Post p = post(kind);
            p.setImageKeys(new ArrayList<>(List.of("post/a.jpg")));
            PostDto dto = assertDoesNotThrow(() -> postService.create(p, AUTHOR));
            assertThat(stored(dto).getDescription()).isNull();
        }
    }

    @Test
    void aPostWithNeitherTextNorPhotoIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> postService.create(post("post"), AUTHOR));
        Post ask = post("ask");
        ask.setTitle("Generator for a night");
        // An ask's body was never required; the title is.
        assertDoesNotThrow(() -> postService.create(ask, AUTHOR));
    }

    @Test
    void aTextlessCivicReportSaves() {
        Post r = post("civic-report");
        r.setCivicCategory("pothole");
        r.setLatitude(40.39);
        r.setLongitude(-111.85);
        PostDto dto = assertDoesNotThrow(() -> postService.create(r, AUTHOR));
        assertThat(stored(dto).getCivicCategory()).isEqualTo("pothole");
    }

    @Test
    void aHazardWithNoNoteAndNoPhotoStillSaves() {
        // The peer session's hazard reports go through this create path
        // (HazardService.createPost): title = the category label, the note is
        // optional. The C9a rule must not touch them.
        Post h = post("hazard");
        h.setTitle("Flooding");
        h.setLatitude(40.39);
        h.setLongitude(-111.85);
        assertDoesNotThrow(() -> postService.create(h, AUTHOR));
    }

    // ── C9b · sign ─────────────────────────────────────────────────────────

    @Test
    void signIsACivicCategory() {
        Post r = post("civic-report");
        r.setCivicCategory("sign");
        r.setDescription("Stop sign knocked flat");
        r.setLatitude(40.39);
        r.setLongitude(-111.85);
        assertThat(stored(postService.create(r, AUTHOR)).getCivicCategory()).isEqualTo("sign");
    }

    // ── C9d · payment methods ──────────────────────────────────────────────

    @Test
    void paymentMethodsKeepOnlyTheSevenAndNothingOnAFreeListing() {
        assertEquals("{\"venmo\":\"@me\",\"applePay\":true}",
                PostService.normalizePaymentMethods("{\"venmo\":\" @me \",\"applePay\":true,\"googlePay\":false,\"zelle\":\"  \"}", false));
        assertThat(PostService.normalizePaymentMethods("{}", false)).isNull();
        assertThat(PostService.normalizePaymentMethods("{\"venmo\":\"@me\"}", true)).isNull();
        assertThrows(IllegalArgumentException.class,
                () -> PostService.normalizePaymentMethods("{\"bitcoin\":\"x\"}", false));
        assertThrows(IllegalArgumentException.class,
                () -> PostService.normalizePaymentMethods("{\"venmo\":\"" + "a".repeat(65) + "\"}", false));
    }

    @Test
    void aFreeListingIsStoredWithoutHandles() {
        Post l = post("marketplace");
        l.setTitle("Couch");
        l.setFree(true);
        l.setPaymentMethodsJson("{\"venmo\":\"@me\"}");
        Post s = stored(postService.create(l, AUTHOR));
        assertThat(s.isFree()).isTrue();
        assertThat(s.getPaymentMethodsJson()).isNull();
    }

    // ── C9c · PATCH coverage ───────────────────────────────────────────────

    private PostDto listing() {
        Post l = post("marketplace");
        l.setTitle("Camp stove");
        l.setPrice(new BigDecimal("20"));
        l.setPaymentMethodsJson("{\"venmo\":\"@me\"}");
        return postService.create(l, AUTHOR);
    }

    @Test
    void theAuthorCanRepriceAndChangeHowBuyersPay() {
        PostDto dto = listing();
        Post patch = new Post();
        patch.setPrice(new BigDecimal("30"));
        patch.setPaymentMethodsJson("{\"cashApp\":\"$me\"}");
        postService.patch(dto.id(), patch, AUTHOR);
        Post s = stored(dto);
        assertThat(s.getPrice()).isEqualByComparingTo("30");
        assertThat(s.getPaymentMethodsJson()).isEqualTo("{\"cashApp\":\"$me\"}");

        Post free = new Post();
        free.setFree(true);
        postService.patch(dto.id(), free, AUTHOR);
        s = stored(dto);
        assertThat(s.isFree()).isTrue();
        assertThat(s.getPrice()).isNull();
        assertThat(s.getPaymentMethodsJson()).isNull();
    }

    @Test
    void aNonAuthorEditorCannotReprice() {
        PostDto dto = listing();
        Post patch = new Post();
        patch.setPrice(new BigDecimal("1"));
        postService.patch(dto.id(), patch, "someone-else@example.com");
        assertThat(stored(dto).getPrice()).isEqualByComparingTo("20");
    }

    @Test
    void anEditKeepsTheImageCapAndCoordinateCheck() {
        Post p = post("post");
        p.setDescription("Hello");
        PostDto dto = postService.create(p, AUTHOR);

        Post six = new Post();
        six.setImageKeys(new ArrayList<>(List.of("post/1.jpg", "post/2.jpg", "post/3.jpg", "post/4.jpg", "post/5.jpg", "post/6.jpg")));
        assertThrows(IllegalArgumentException.class, () -> postService.patch(dto.id(), six, AUTHOR));

        Post badLat = new Post();
        badLat.setLatitude(123.0);
        badLat.setLongitude(-111.0);
        assertThrows(IllegalArgumentException.class, () -> postService.patch(dto.id(), badLat, AUTHOR));
    }

    @Test
    void anEditCannotEmptyAPhotolessPostsText() {
        Post p = post("post");
        p.setDescription("Hello");
        PostDto dto = postService.create(p, AUTHOR);
        Post blank = new Post();
        blank.setDescription("");
        assertThrows(IllegalArgumentException.class, () -> postService.patch(dto.id(), blank, AUTHOR));
    }

    @Test
    void anEditCannotBlankATitleKind() {
        Post ask = post("ask");
        ask.setTitle("Generator");
        PostDto dto = postService.create(ask, AUTHOR);
        Post blank = new Post();
        blank.setTitle(" ");
        assertThrows(IllegalArgumentException.class, () -> postService.patch(dto.id(), blank, AUTHOR));
    }

    @Test
    void aCivicReportKeepsItsPointButItsCategoryCanBeCorrected() {
        Post r = post("civic-report");
        r.setCivicCategory("pothole");
        r.setLatitude(40.39);
        r.setLongitude(-111.85);
        PostDto dto = postService.create(r, AUTHOR);

        Post patch = new Post();
        patch.setLatitude(41.0);
        patch.setLongitude(-112.0);
        patch.setCivicCategory("sign");
        postService.patch(dto.id(), patch, AUTHOR);
        Post s = stored(dto);
        assertThat(s.getLatitude()).isEqualTo(40.39);
        assertThat(s.getCivicCategory()).isEqualTo("sign");
    }

    // ── C9e · neighbour rate limit ─────────────────────────────────────────

    @Test
    void sitPrepsDispatcherIsNeverRateLimitedAndItsPostsSaySitPrep() {
        String sitprep = io.sitprep.sitprepapi.constant.SystemAccounts.SITPREP_EMAIL;
        if (userInfoRepo.findByUserEmail(sitprep).isEmpty()) {
            io.sitprep.sitprepapi.domain.UserInfo u = new io.sitprep.sitprepapi.domain.UserInfo();
            u.setUserEmail(sitprep);
            u.setUserFirstName("SitPrep");
            userInfoRepo.save(u);
        }
        PostDto last = null;
        for (int i = 0; i <= PostService.NEIGHBOUR_POSTS_PER_HOUR; i++) {
            Post p = post("alert-update");
            p.setTitle("Flash flood warning " + i);
            p.setDescription("Move to higher ground.");
            last = postService.create(p, sitprep);
        }
        assertThat(last.authorType()).isEqualTo("sitprep");
    }

    @Test
    void theEleventhPostInAnHourIs429ButAHazardIsNotCounted() {
        for (int i = 0; i < PostService.NEIGHBOUR_POSTS_PER_HOUR; i++) {
            Post p = post("post");
            p.setDescription("Post " + i);
            postService.create(p, AUTHOR);
        }
        Post eleventh = post("post");
        eleventh.setDescription("One too many");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> postService.create(eleventh, AUTHOR));
        assertEquals(429, e.getStatusCode().value());

        Post h = post("hazard");
        h.setTitle("Flooding");
        h.setLatitude(40.39);
        h.setLongitude(-111.85);
        assertDoesNotThrow(() -> postService.create(h, AUTHOR));
    }
}
