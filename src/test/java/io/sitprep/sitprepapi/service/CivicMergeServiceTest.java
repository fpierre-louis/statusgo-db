package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.CivicStatus;
import io.sitprep.sitprepapi.domain.CivicReportAgency;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostConfirm;
import io.sitprep.sitprepapi.dto.CivicQueueDto;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.CivicReportAgencyRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostConfirmRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Civic epic Slice 3 — merge / unmerge duplicate civic reports, exercised
 * against the H2 test schema (built from the entities via ddl-auto=create-drop).
 * The Postgres FK (ON DELETE SET NULL) + CHECK (no self-merge) are V54-only and
 * not present here, so those invariants are enforced/tested via the service
 * guards (self-id filtered → 400; flatten-on-merge; cross-agency-claim 409) —
 * the same arrangement V50/V53 use for Postgres-only constraints. The DB CHECK
 * and FK are verified separately via the local-Postgres apply.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CivicMergeServiceTest {

    @Autowired CivicAgencyService civic;
    @Autowired PostService posts;
    @Autowired GroupRepo groupRepo;
    @Autowired PostRepo postRepo;
    @Autowired CivicReportAgencyRepo tagRepo;
    @Autowired PostConfirmRepo confirmRepo;

    private static final double LAT = 40.01, LNG = -111.00;
    private static final String CITY = "city", CITY_ADMIN = "city-admin@x.gov";
    private static final String COUNTY = "county", COUNTY_ADMIN = "county-admin@x.gov";
    private static final String OTHER = "other", OTHER_ADMIN = "other-admin@x.gov";
    private static final String RESIDENT = "resident@example.com";

    private Group agency(String id, String admin) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id.toUpperCase());
        g.setOwnerEmail(admin);
        g.setAgencyAuthorized(true);
        return groupRepo.save(g);
    }

    private Post report(String category) {
        Post p = new Post();
        p.setKind("civic-report");
        p.setCivicCategory(category);
        p.setCivicStatus(CivicStatus.REPORTED.wire());
        p.setRequesterEmail(RESIDENT);
        p.setLatitude(LAT);
        p.setLongitude(LNG);
        return postRepo.save(p);
    }

    private void tag(Long postId, String agencyId, boolean active) {
        CivicReportAgency t = new CivicReportAgency();
        t.setPostId(postId);
        t.setAgencyGroupId(agencyId);
        t.setTagSource("auto");
        t.setActive(active);
        tagRepo.save(t);
    }

    /** A report tagged to + claimed by the agency (its canonical, in-queue). */
    private Post claimed(String agencyId, String admin, String category) {
        Post c = report(category);
        tag(c.getId(), agencyId, true);
        civic.claim(c.getId(), agencyId, admin);
        return postRepo.findById(c.getId()).orElseThrow();
    }

    private Post workOrder(Long sourcePostId) {
        Post w = new Post();
        w.setKind("task");
        w.setRequesterEmail("agency@x.gov");
        w.setSourcePostId(sourcePostId);
        return postRepo.save(w);
    }

    private void confirm(Long postId, String email) {
        PostConfirm c = new PostConfirm();
        c.setPostId(postId);
        c.setUserEmail(email);
        confirmRepo.save(c);
    }

    private Post reload(Long id) { return postRepo.findById(id).orElseThrow(); }

    // ─────────────────────────────────────────────────────────────────────

    @Test
    void merge_linksDuplicates_notDeleted_canonicalUntouched_droppedFromQueue() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole"); tag(d1.getId(), CITY, true);
        Post d2 = report("pothole"); tag(d2.getId(), CITY, true);

        CivicAgencyService.MergeResult r =
                civic.merge(canon.getId(), List.of(d1.getId(), d2.getId()), CITY, CITY_ADMIN);

        assertThat(r.mergedDuplicateCount()).isEqualTo(2);
        assertThat(reload(d1.getId()).getMergedIntoPostId()).isEqualTo(canon.getId());
        assertThat(reload(d2.getId()).getMergedIntoPostId()).isEqualTo(canon.getId());
        // Not deleted — the rows still exist (citizen data preserved).
        assertThat(postRepo.findById(d1.getId())).isPresent();
        // Canonical unchanged: still canonical, status still REPORTED.
        Post c = reload(canon.getId());
        assertThat(c.getMergedIntoPostId()).isNull();
        assertThat(c.getCivicStatus()).isEqualTo(CivicStatus.REPORTED.wire());

        // Queue: merged rows drop out; canonical stays w/ mergedDuplicateCount.
        CivicQueueDto q = posts.listCivicReportsForAgency(CITY, null);
        List<Long> ids = q.reports().stream().map(CivicQueueDto.CivicReportSummary::id).toList();
        assertThat(ids).containsExactly(canon.getId());
        assertThat(ids).doesNotContain(d1.getId(), d2.getId());
        CivicQueueDto.CivicReportSummary summary = q.reports().get(0);
        assertThat(summary.mergedDuplicateCount()).isEqualTo(2);
        assertThat(summary.mergedDuplicateIds()).containsExactlyInAnyOrder(d1.getId(), d2.getId());
        assertThat(q.counts().total()).isEqualTo(1); // only the canonical counts
    }

    @Test
    void readThrough_duplicateCardMirrorsCanonicalStatus_andExposesTapThrough() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole"); tag(d1.getId(), CITY, true);
        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);

        // Canonical advances → the duplicate's OWN status stays frozen, but its
        // card mirrors the survivor's status (read-through) + offers tap-through.
        Post c = reload(canon.getId());
        c.setCivicStatus(CivicStatus.ACKNOWLEDGED.wire());
        postRepo.save(c);

        PostDto dupDto = posts.findDtoById(d1.getId(), RESIDENT).orElseThrow();
        assertThat(dupDto.community().civicStatus()).isEqualTo(CivicStatus.REPORTED.wire()); // frozen
        assertThat(dupDto.community().mergedIntoPostId()).isEqualTo(canon.getId());          // tap-through
        assertThat(dupDto.community().canonicalStatus()).isEqualTo(CivicStatus.ACKNOWLEDGED.wire()); // display
    }

    @Test
    void confirms_aggregateOntoCanonical_readThrough() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole"); tag(d1.getId(), CITY, true);
        confirm(canon.getId(), "a@x.com");            // 1 on the canonical
        confirm(d1.getId(), "b@x.com");               // 2 on the duplicate
        confirm(d1.getId(), "c@x.com");
        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);

        PostDto canonDto = posts.findDtoById(canon.getId(), RESIDENT).orElseThrow();
        assertThat(canonDto.community().confirmsCount()).isEqualTo(3); // 1 + 2 aggregated
    }

    @Test
    void flatten_mergeIntoMerged_409() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole"); tag(d1.getId(), CITY, true);
        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);
        // d1 is now merged — merging INTO it must 409.
        Post d2 = report("pothole");
        assertThatThrownBy(() -> civic.merge(d1.getId(), List.of(d2.getId()), CITY, CITY_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void flatten_grandDuplicatesRepointToNewCanonical() {
        agency(CITY, CITY_ADMIN);
        // d1 is itself a canonical-with-a-duplicate (grand), claimed by city.
        Post d1 = claimed(CITY, CITY_ADMIN, "pothole");
        Post grand = report("pothole"); tag(grand.getId(), CITY, true);
        civic.merge(d1.getId(), List.of(grand.getId()), CITY, CITY_ADMIN);
        assertThat(reload(grand.getId()).getMergedIntoPostId()).isEqualTo(d1.getId());

        // Now merge d1 into a NEW canonical → grand re-points DIRECTLY to it.
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);
        assertThat(reload(d1.getId()).getMergedIntoPostId()).isEqualTo(canon.getId());
        assertThat(reload(grand.getId()).getMergedIntoPostId()).isEqualTo(canon.getId()); // flattened, not chained
    }

    @Test
    void selfMerge_filtered_400() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        assertThatThrownBy(() -> civic.merge(canon.getId(), List.of(canon.getId()), CITY, CITY_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void merge_workOrdersRepoint_tagsUnion_claimNotTransferred_crossCategoryOk() {
        agency(CITY, CITY_ADMIN);
        agency(COUNTY, COUNTY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        // Cross-category duplicate (decision 8 — no restriction), tagged to COUNTY
        // (not on the canonical) and carrying a work order.
        Post d1 = report("streetlight");
        tag(d1.getId(), COUNTY, true);
        Post wo = workOrder(d1.getId());

        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);

        // 4a — work order re-points to the canonical.
        assertThat(reload(wo.getId()).getSourcePostId()).isEqualTo(canon.getId());
        // 4b — county tag union'd onto the canonical as 'merged', NOT claimed.
        CivicReportAgency countyOnCanon =
                tagRepo.findByPostIdAndAgencyGroupId(canon.getId(), COUNTY).orElseThrow();
        assertThat(countyOnCanon.isActive()).isTrue();
        assertThat(countyOnCanon.getTagSource()).isEqualTo("merged");
        assertThat(countyOnCanon.isClaimed()).isFalse();
        // The canonical's own claim (city) still stands.
        assertThat(reload(canon.getId()).getClaimingAgencyGroupId()).isEqualTo(CITY);
    }

    @Test
    void guard_nonClaimingAgency_403() {
        agency(CITY, CITY_ADMIN);
        agency(OTHER, OTHER_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole"); // claimed by CITY
        Post d1 = report("pothole");
        assertThatThrownBy(() -> civic.merge(canon.getId(), List.of(d1.getId()), OTHER, OTHER_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void guard_unclaimedCanonical_409() {
        agency(CITY, CITY_ADMIN);
        Post canon = report("pothole"); tag(canon.getId(), CITY, true); // tagged, NOT claimed
        Post d1 = report("pothole");
        assertThatThrownBy(() -> civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void guard_duplicateClaimedByAnotherAgency_409() {
        agency(CITY, CITY_ADMIN);
        agency(OTHER, OTHER_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = claimed(OTHER, OTHER_ADMIN, "pothole"); // claimed by ANOTHER agency
        assertThatThrownBy(() -> civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        // d1 is untouched (still standalone, still claimed by OTHER).
        assertThat(reload(d1.getId()).getMergedIntoPostId()).isNull();
    }

    @Test
    void unmerge_restoresStandalone_workOrderStaysWithFormerCanonical() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole"); tag(d1.getId(), CITY, true);
        Post wo = workOrder(d1.getId());
        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);
        assertThat(reload(wo.getId()).getSourcePostId()).isEqualTo(canon.getId());

        CivicAgencyService.UnmergeResult u = civic.unmerge(d1.getId(), CITY, CITY_ADMIN);
        assertThat(u.formerCanonicalId()).isEqualTo(canon.getId());
        // Restored to standalone.
        assertThat(reload(d1.getId()).getMergedIntoPostId()).isNull();
        // Decision 9 LOCKED — the work order STAYS with the former canonical.
        assertThat(reload(wo.getId()).getSourcePostId()).isEqualTo(canon.getId());
        // Back in the queue as its own row.
        CivicQueueDto q = posts.listCivicReportsForAgency(CITY, null);
        assertThat(q.reports().stream().map(CivicQueueDto.CivicReportSummary::id).toList())
                .contains(d1.getId());
    }

    @Test
    void unmerge_notMerged_409() {
        agency(CITY, CITY_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole");
        assertThatThrownBy(() -> civic.unmerge(d1.getId(), CITY, CITY_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unmerge_nonClaimingAgency_403() {
        agency(CITY, CITY_ADMIN);
        agency(OTHER, OTHER_ADMIN);
        Post canon = claimed(CITY, CITY_ADMIN, "pothole");
        Post d1 = report("pothole"); tag(d1.getId(), CITY, true);
        civic.merge(canon.getId(), List.of(d1.getId()), CITY, CITY_ADMIN);
        assertThatThrownBy(() -> civic.unmerge(d1.getId(), OTHER, OTHER_ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void existingReports_unaffected_whenNothingMerged() {
        agency(CITY, CITY_ADMIN);
        Post a = claimed(CITY, CITY_ADMIN, "pothole");
        Post b = report("pothole"); tag(b.getId(), CITY, true);
        // No merge performed — both are live, both in the queue, no merge fields.
        assertThat(reload(a.getId()).getMergedIntoPostId()).isNull();
        assertThat(reload(b.getId()).getMergedIntoPostId()).isNull();
        CivicQueueDto q = posts.listCivicReportsForAgency(CITY, null);
        assertThat(q.reports().stream().map(CivicQueueDto.CivicReportSummary::id).toList())
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(q.reports()).allSatisfy(s -> {
            assertThat(s.mergedDuplicateCount()).isZero();
            assertThat(s.mergedIntoPostId()).isNull();
        });
    }
}
