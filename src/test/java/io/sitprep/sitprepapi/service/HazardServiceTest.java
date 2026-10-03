package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HazardReport;
import io.sitprep.sitprepapi.domain.HazardVote;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HazardDto;
import io.sitprep.sitprepapi.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hazard reports HR1 — docs/epics/hazard-reports/EXEC-HR1-backend.md (frontend repo). */
class HazardServiceTest {

    static final Instant NOW = Instant.parse("2026-09-28T18:00:00Z");
    static final double LAT = 40.3916, LNG = -111.8508;

    HazardReportRepo hazards;
    HazardVoteRepo votes;
    PostRepo posts;
    PostService postService;
    UserInfoRepo users;
    GroupRepo groups;
    AgencyAuthorizationService agencyAuth;
    HazardService service;
    Map<Long, Post> postStore;
    List<HazardVote> voteStore;
    List<Post> createdPosts = new ArrayList<>();
    Long nextPostId;

    @BeforeEach
    void setUp() {
        hazards = mock(HazardReportRepo.class);
        votes = mock(HazardVoteRepo.class);
        posts = mock(PostRepo.class);
        postService = mock(PostService.class);
        users = mock(UserInfoRepo.class);
        groups = mock(GroupRepo.class);
        agencyAuth = mock(AgencyAuthorizationService.class);
        service = new HazardService(hazards, votes, posts, postService, users, groups, agencyAuth,
                Clock.fixed(NOW, ZoneOffset.UTC),
                org.mockito.Mockito.mock(io.sitprep.sitprepapi.gamification.TokenEventPublisher.class)) {
            @Override
            Long createPost(Post incoming, String me) {
                createdPosts.add(incoming);
                if (nextPostId == null) throw new AssertionError("no post expected");
                post(nextPostId, incoming.getLatitude(), incoming.getLongitude()).setDescription(incoming.getDescription());
                return nextPostId;
            }
        };
        postStore = new HashMap<>();
        voteStore = new ArrayList<>();
        when(users.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(posts.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(postStore.get((Long) i.getArgument(0))));
        when(posts.findAllById(any())).thenAnswer(i -> {
            List<Post> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) if (postStore.containsKey(id)) out.add(postStore.get(id));
            return out;
        });
        when(votes.findByTaskIdIn(any())).thenAnswer(i -> {
            Collection<?> ids = i.getArgument(0);
            return voteStore.stream().filter(v -> ids.contains(v.getTaskId())).toList();
        });
        when(votes.findByTaskIdAndUserEmail(anyLong(), anyString())).thenAnswer(i -> voteStore.stream()
                .filter(v -> v.getTaskId().equals(i.getArgument(0)) && v.getUserEmail().equals(i.getArgument(1))).findFirst());
        when(votes.save(any(HazardVote.class))).thenAnswer(i -> {
            HazardVote v = i.getArgument(0);
            if (!voteStore.contains(v)) voteStore.add(v);
            return v;
        });
        when(hazards.save(any(HazardReport.class))).thenAnswer(i -> i.getArgument(0));
        when(hazards.findByReporterSince(anyString(), any())).thenReturn(List.of());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Post post(long id, double lat, double lng) {
        Post p = new Post();
        p.setId(id);
        p.setLatitude(lat);
        p.setLongitude(lng);
        postStore.put(id, p);
        return p;
    }

    private HazardReport hazard(long id, String category, Duration age, Duration left) {
        HazardReport h = new HazardReport();
        h.setTaskId(id);
        h.setCategory(category);
        h.setRadiusM(150);
        h.setReportedAt(NOW.minus(age));
        h.setExpiresAt(NOW.plus(left));
        when(hazards.findById(id)).thenReturn(Optional.of(h));
        return h;
    }

    private HazardVote vote(long id, String who, String v, Duration ago) {
        HazardVote x = new HazardVote();
        x.setTaskId(id);
        x.setUserEmail(who);
        x.setVote(v);
        x.setVotedAt(NOW.minus(ago));
        voteStore.add(x);
        return x;
    }

    private HazardService.ReportRequest req(String cat, List<String> images, double reporterLat) {
        return new HazardService.ReportRequest(cat, LAT, LNG, "Water over Center St", images, reporterLat, LNG, null);
    }

    private void createReturns(long id) {
        nextPostId = id;
    }

    private static void assertStatus(Runnable r, HttpStatus s) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(s));
    }

    // ── report ──────────────────────────────────────────────────────────────

    @Test
    void aReportIsAHazardPostWithItsCategoryAsTitleAndTheReportersStillVote() {
        createReturns(42L);
        HazardDto dto = service.report(req("flood", null, LAT), "a@x.com");

        assertThat(createdPosts).hasSize(1);
        assertThat(createdPosts.get(0).getKind()).isEqualTo("hazard");
        assertThat(createdPosts.get(0).getTitle()).isEqualTo("Flooding / water over road");
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.state()).isEqualTo("reported");      // one person is not enough (H-2)
        assertThat(dto.confirmations()).isEqualTo(1);
        assertThat(dto.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(dto.blocksRoutes()).isTrue();
    }

    @Test
    void oneReportWithAPhotoIsConfirmed() {
        createReturns(43L);
        assertThat(service.report(req("road_closed", List.of("img/1.jpg"), LAT), "a@x.com").state()).isEqualTo("confirmed");
    }

    @Test
    void tooFarNoFixUnknownCategoryAndGuestsAreRefused() {
        createReturns(44L);
        assertStatus(() -> service.report(req("fire", null, LAT + 0.05), "a@x.com"), HttpStatus.UNPROCESSABLE_ENTITY); // ~5.6 km
        assertStatus(() -> service.report(new HazardService.ReportRequest("fire", LAT, LNG, null, null, null, null, null), "a@x.com"),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertStatus(() -> service.report(req("volcano", null, LAT), "a@x.com"), HttpStatus.BAD_REQUEST);
        UserInfo guest = new UserInfo();
        guest.setGuestAccount(true);
        when(users.findByUserEmailIgnoreCase("g@x.com")).thenReturn(Optional.of(guest));
        assertStatus(() -> service.report(req("fire", null, LAT), "g@x.com"), HttpStatus.FORBIDDEN);
        assertThat(createdPosts).isEmpty();
    }

    @Test
    void theSixthReportInAnHourIsRefused() {
        createReturns(45L);
        List<HazardReport> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            HazardReport h = hazard(100 + i, "crash", Duration.ofMinutes(10 + i), Duration.ofHours(1));
            post(100 + i, LAT + 0.02 * (i + 1), LNG); // far apart: not duplicates
            five.add(h);
        }
        when(hazards.findByReporterSince(eq("a@x.com"), any())).thenReturn(five);
        assertStatus(() -> service.report(req("debris", null, LAT), "a@x.com"), HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void reportingTheSameThingNearbyAgainIsAStillVoteNotADuplicate() {
        HazardReport h = hazard(50L, "flood", Duration.ofMinutes(30), Duration.ofHours(11));
        post(50L, LAT + 0.001, LNG); // ~110 m
        vote(50L, "a@x.com", HazardVote.STILL, Duration.ofMinutes(30));
        when(hazards.findByReporterSince(eq("a@x.com"), any())).thenReturn(List.of(h));
        HazardDto dto = service.report(req("flood", null, LAT), "a@x.com");
        assertThat(dto.id()).isEqualTo(50L);
        assertThat(createdPosts).isEmpty();
    }

    // ── state ───────────────────────────────────────────────────────────────

    @Test
    void threeDistinctPeopleInTheHourConfirm_twoDoNot() {
        HazardReport h = hazard(60L, "flood", Duration.ofMinutes(40), Duration.ofHours(10));
        vote(60L, "a@x.com", HazardVote.STILL, Duration.ofMinutes(40));
        vote(60L, "b@x.com", HazardVote.STILL, Duration.ofMinutes(20));
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("reported");
        vote(60L, "c@x.com", HazardVote.STILL, Duration.ofMinutes(5));
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("confirmed");
    }

    @Test
    void oldConfirmationsDoNotCount() {
        HazardReport h = hazard(61L, "flood", Duration.ofHours(3), Duration.ofHours(9));
        vote(61L, "a@x.com", HazardVote.STILL, Duration.ofMinutes(90));
        vote(61L, "b@x.com", HazardVote.STILL, Duration.ofMinutes(80));
        vote(61L, "c@x.com", HazardVote.STILL, Duration.ofMinutes(10));
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("reported");
    }

    @Test
    void moreGoneThanStillClears_butNeverAnOfficialReport() {
        HazardReport h = hazard(62L, "crash", Duration.ofMinutes(50), Duration.ofMinutes(70));
        vote(62L, "a@x.com", HazardVote.STILL, Duration.ofMinutes(50));
        vote(62L, "b@x.com", HazardVote.GONE, Duration.ofMinutes(10));
        vote(62L, "c@x.com", HazardVote.GONE, Duration.ofMinutes(5));
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("cleared");
        h.setOfficialAt(NOW.minus(Duration.ofMinutes(30)));
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("official");
    }

    @Test
    void expiredAndAgencyClearedEnd() {
        HazardReport h = hazard(63L, "crash", Duration.ofHours(3), Duration.ofMinutes(-1));
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("expired");
        h.setExpiresAt(NOW.plus(Duration.ofHours(1)));
        h.setClearedAt(NOW);
        assertThat(HazardService.stateOf(h, voteStore, NOW)).isEqualTo("cleared");
    }

    // ── vote ────────────────────────────────────────────────────────────────

    @Test
    void stillExtendsTheLifeButNeverPastThreeLifetimes() {
        HazardReport h = hazard(70L, "crash", Duration.ofMinutes(30), Duration.ofMinutes(90)); // 2 h lifetime
        post(70L, LAT, LNG);
        service.vote(70L, "still", "b@x.com");
        assertThat(h.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        HazardReport old = hazard(71L, "crash", Duration.ofHours(5), Duration.ofMinutes(30));
        post(71L, LAT, LNG);
        service.vote(71L, "still", "c@x.com");
        assertThat(old.getExpiresAt()).isEqualTo(old.getReportedAt().plus(Duration.ofHours(6))); // capped: 3 × 2 h
    }

    @Test
    void aPersonsNewVoteReplacesTheirOld() {
        hazard(72L, "flood", Duration.ofMinutes(10), Duration.ofHours(11));
        post(72L, LAT, LNG);
        service.vote(72L, "still", "b@x.com");
        HazardDto dto = service.vote(72L, "gone", "b@x.com");
        assertThat(voteStore.stream().filter(v -> v.getUserEmail().equals("b@x.com")).count()).isEqualTo(1);
        assertThat(dto.viewerVote()).isEqualTo("gone");
    }

    @Test
    void votingOnAnEndedReportIs409_andABadVoteIs400() {
        HazardReport h = hazard(73L, "flood", Duration.ofMinutes(10), Duration.ofHours(1));
        post(73L, LAT, LNG);
        assertStatus(() -> service.vote(73L, "maybe", "b@x.com"), HttpStatus.BAD_REQUEST);
        h.setClearedAt(NOW);
        assertStatus(() -> service.vote(73L, "still", "b@x.com"), HttpStatus.CONFLICT);
    }

    // ── agency ──────────────────────────────────────────────────────────────

    @Test
    void anAgencyMarksOfficialThroughTheAreaAlertGate() {
        hazard(80L, "fire", Duration.ofMinutes(10), Duration.ofHours(11));
        post(80L, LAT, LNG);
        Group agency = new Group();
        agency.setGroupId("g-county");
        when(groups.findByGroupId("g-county")).thenReturn(Optional.of(agency));
        assertThat(service.markOfficial(80L, "g-county", "chief@county.gov").state()).isEqualTo("official");
        verify(agencyAuth).requireAgencyPostingAllowed(agency, "chief@county.gov");
    }

    @Test
    void someoneWhoFailsTheGateCannotClear() {
        hazard(81L, "fire", Duration.ofMinutes(10), Duration.ofHours(11));
        post(81L, LAT, LNG);
        Group agency = new Group();
        agency.setGroupId("g-county");
        when(groups.findByGroupId("g-county")).thenReturn(Optional.of(agency));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(agencyAuth)
                .requireAgencyPostingAllowed(any(), eq("rando@x.com"));
        assertStatus(() -> service.clear(81L, "g-county", "rando@x.com"), HttpStatus.FORBIDDEN);
    }

    // ── read ────────────────────────────────────────────────────────────────

    @Test
    void theBoxHasOnlyActiveReportsInsideIt() {
        HazardReport inside = hazard(90L, "flood", Duration.ofMinutes(10), Duration.ofHours(11));
        HazardReport outside = hazard(91L, "flood", Duration.ofMinutes(10), Duration.ofHours(11));
        HazardReport gone = hazard(92L, "crash", Duration.ofMinutes(10), Duration.ofHours(1));
        post(90L, LAT, LNG);
        post(91L, LAT + 1, LNG);
        post(92L, LAT, LNG);
        vote(92L, "b@x.com", HazardVote.GONE, Duration.ofMinutes(5));
        vote(92L, "c@x.com", HazardVote.GONE, Duration.ofMinutes(4));
        when(hazards.findByClearedAtIsNullAndExpiresAtAfter(NOW)).thenReturn(List.of(inside, outside, gone));
        List<HazardDto> box = service.inBox(LAT - 0.1, LNG - 0.1, LAT + 0.1, LNG + 0.1, null);
        assertThat(box).extracting(HazardDto::id).containsExactly(90L);
    }

    @Test
    void theDtoCarriesNoReporter() {
        for (RecordComponent c : HazardDto.class.getRecordComponents()) {
            assertThat(c.getName().toLowerCase()).doesNotContain("email").doesNotContain("reporter")
                    .doesNotContain("author").doesNotContain("requester").doesNotContain("user");
        }
    }
}
