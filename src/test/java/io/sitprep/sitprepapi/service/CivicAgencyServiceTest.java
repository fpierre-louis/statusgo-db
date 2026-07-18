package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.CivicStatus;
import io.sitprep.sitprepapi.domain.CivicCoverageGap;
import io.sitprep.sitprepapi.domain.CivicReportAgency;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.repo.CivicCoverageGapRepo;
import io.sitprep.sitprepapi.repo.CivicReportAgencyRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.PostRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Civic epic Slice 2 — multi-agency tagging + claim/release, exercised against
 * the H2 test schema (built from the entities via ddl-auto=create-drop; the
 * Postgres partial-unique one-claim index isn't present here, so second-claim
 * rejection is verified via the service guard — same arrangement V50 uses).
 *
 * A report at the city center sits inside the city's 5-mi radius AND in the
 * county's claimed zip, so the resolver covers it with BOTH agencies.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CivicAgencyServiceTest {

    @Autowired CivicAgencyService civic;
    @Autowired GroupRepo groupRepo;
    @Autowired PostRepo postRepo;
    @Autowired CivicReportAgencyRepo tagRepo;
    @Autowired CivicCoverageGapRepo gapRepo;

    private static final double LAT = 40.01, LNG = -111.00; // ~0.7 mi from city center
    private static final String ZIP = "84043";

    private Group agency(String id, String admin, Double lat, Double lng, Double radius, List<String> zips, boolean authorized) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id);
        g.setOwnerEmail(admin);
        g.setAgencyAuthorized(authorized);
        g.setJurisdictionLat(lat);
        g.setJurisdictionLng(lng);
        g.setJurisdictionRadiusMiles(radius);
        // Mutable copy — a @ElementCollection backed by an immutable List.of
        // throws UnsupportedOperation if the entity is re-managed/saved again.
        g.setJurisdictionZips(zips == null ? null : new ArrayList<>(zips));
        return groupRepo.save(g);
    }

    private Post report(double lat, double lng) {
        Post p = new Post();
        p.setKind("civic-report");
        p.setCivicCategory("pothole");
        p.setCivicStatus(CivicStatus.REPORTED.wire());
        p.setRequesterEmail("resident@example.com");
        p.setLatitude(lat);
        p.setLongitude(lng);
        return postRepo.save(p);
    }

    private void city() { agency("city", "city-admin@x.gov", 40.00, -111.00, 5.0, null, true); }
    private void county() { agency("county", "county-admin@x.gov", null, null, null, List.of(ZIP), true); }

    @Test
    void autoDerive_tagsAllCoveringAgencies() {
        city(); county();
        Post r = report(LAT, LNG);
        civic.applyCreateTags(r, ZIP);

        List<CivicReportAgency> active = tagRepo.findByPostIdAndActiveTrue(r.getId());
        assertThat(active).extracting(CivicReportAgency::getAgencyGroupId)
                .containsExactlyInAnyOrder("city", "county");
        assertThat(active).allMatch(t -> "auto".equals(t.getTagSource()));
        assertThat(postRepo.findById(r.getId()).orElseThrow().getTaggedAgencyGroupId()).isNotNull();
    }

    @Test
    void citizenDeselect_writesTombstone() {
        city(); county();
        Post r = report(LAT, LNG);
        r.setCivicAgencyIds(List.of("city")); // filer kept only the city
        civic.applyCreateTags(r, ZIP);

        assertThat(tagRepo.findByPostIdAndActiveTrue(r.getId()))
                .extracting(CivicReportAgency::getAgencyGroupId).containsExactly("city");
        // County survives as a tombstone (active=false), not deleted.
        CivicReportAgency county = tagRepo.findByPostIdAndAgencyGroupId(r.getId(), "county").orElseThrow();
        assertThat(county.isActive()).isFalse();
    }

    @Test
    void citizenAdd_authorizedOnly() {
        city();
        agency("extra", "extra-admin@x.gov", null, null, null, List.of("00000"), true); // authorized, not covering
        agency("rogue", "rogue@x.gov", null, null, null, null, false);                    // NOT authorized
        Post r = report(LAT, LNG);
        r.setCivicAgencyIds(List.of("city", "extra", "rogue"));
        civic.applyCreateTags(r, ZIP);

        List<CivicReportAgency> active = tagRepo.findByPostIdAndActiveTrue(r.getId());
        assertThat(active).extracting(CivicReportAgency::getAgencyGroupId)
                .containsExactlyInAnyOrder("city", "extra"); // rogue dropped (not authorized)
        assertThat(tagRepo.findByPostIdAndAgencyGroupId(r.getId(), "extra").orElseThrow().getTagSource())
                .isEqualTo("citizen_added");
    }

    @Test
    void orphan_recordsCoverageGap() {
        city(); county();
        Post r = report(43.00, -111.00); // ~200 mi north, uncovered
        civic.applyCreateTags(r, "99999"); // zip no agency claims

        assertThat(tagRepo.findByPostIdAndActiveTrue(r.getId())).isEmpty();
        CivicCoverageGap gap = gapRepo.findByZip("99999").orElseThrow();
        assertThat(gap.getReportCount()).isEqualTo(1);
        assertThat(gap.getLastCategory()).isEqualTo("pothole");
    }

    @Test
    void orphan_recordsGhostDemandWhenGhostGroupCoversZip() {
        city();
        Group ghost = agency("ghost-city", "n/a", null, null, null, List.of("88888"), false);
        ghost.setClaimState("GHOST");
        ghost.setGhostDemandSignal(0);
        groupRepo.save(ghost);

        Post r = report(43.00, -111.00);
        civic.applyCreateTags(r, "88888");

        assertThat(tagRepo.findByPostIdAndActiveTrue(r.getId())).isEmpty();
        assertThat(groupRepo.findById("ghost-city").orElseThrow().getGhostDemandSignal()).isEqualTo(1);
        assertThat(gapRepo.findByZip("88888")).isEmpty(); // went to the ghost ledger, not the gap table
    }

    @Test
    void claim_secondClaim409_thenRelease() {
        city(); county();
        Post r = report(LAT, LNG);
        civic.applyCreateTags(r, ZIP);

        civic.claim(r.getId(), "city", "city-admin@x.gov");
        assertThat(postRepo.findById(r.getId()).orElseThrow().getClaimingAgencyGroupId()).isEqualTo("city");

        assertThatThrownBy(() -> civic.claim(r.getId(), "county", "county-admin@x.gov"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already claimed");

        civic.release(r.getId(), "city", "city-admin@x.gov");
        assertThat(postRepo.findById(r.getId()).orElseThrow().getClaimingAgencyGroupId()).isNull();
        // Now claimable by the other tagged agency (decision 4).
        civic.claim(r.getId(), "county", "county-admin@x.gov");
        assertThat(postRepo.findById(r.getId()).orElseThrow().getClaimingAgencyGroupId()).isEqualTo("county");
    }

    @Test
    void acknowledgeOpenToAnyTag_scheduleRequiresClaim() {
        city(); county();
        Post r = report(LAT, LNG);
        civic.applyCreateTags(r, ZIP);
        Post fresh = postRepo.findById(r.getId()).orElseThrow();

        // ACKNOWLEDGE — any active-tagged agency admin, no claim needed (decision 3).
        civic.requireCanAdvanceCivic(fresh, CivicStatus.ACKNOWLEDGED, "county-admin@x.gov");
        assertThatThrownBy(() -> civic.requireCanAdvanceCivic(fresh, CivicStatus.ACKNOWLEDGED, "stranger@x.com"))
                .isInstanceOf(ResponseStatusException.class);

        // SCHEDULE without a claim → blocked.
        assertThatThrownBy(() -> civic.requireCanAdvanceCivic(fresh, CivicStatus.SCHEDULED, "city-admin@x.gov"))
                .isInstanceOf(ResponseStatusException.class);

        // After the city claims, only the city may schedule; the county may not.
        civic.claim(r.getId(), "city", "city-admin@x.gov");
        Post claimed = postRepo.findById(r.getId()).orElseThrow();
        civic.requireCanAdvanceCivic(claimed, CivicStatus.SCHEDULED, "city-admin@x.gov");
        assertThatThrownBy(() -> civic.requireCanAdvanceCivic(claimed, CivicStatus.SCHEDULED, "county-admin@x.gov"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
