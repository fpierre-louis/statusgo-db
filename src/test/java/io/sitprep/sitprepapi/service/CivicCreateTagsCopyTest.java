package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.CivicReportAgency;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.dto.PostDto;
import io.sitprep.sitprepapi.repo.CivicReportAgencyRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

/**
 * Gap-1 regression — the filer's confirmed civicAgencyIds must survive the real
 * {@code create()} path. create() builds a fresh {@code Post t = new Post()} and
 * copies fields from {@code incoming}; if civicAgencyIds isn't copied,
 * applyCreateTags reads null and auto-tags ALL covering agencies, silently
 * ignoring the deselect.
 *
 * <p>The earlier CivicAgencyServiceTest set the field on the SAME object it
 * passed to applyCreateTags, so it never exercised the copy. This test goes
 * through {@code PostService.create(incoming, ...)} with a SEPARATE incoming
 * payload — it fails unless create() carries civicAgencyIds onto {@code t}.</p>
 *
 * <p>The reverse-geocode is mocked to null so no network is hit; the resolver's
 * radius side still covers the point (both agencies have geo).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CivicCreateTagsCopyTest {

    @Autowired PostService postService;
    @Autowired GroupRepo groupRepo;
    @Autowired CivicReportAgencyRepo tagRepo;

    // Kill the Nominatim network call in create()'s community-geo enrichment.
    @MockBean NominatimGeocodeService geocode;

    private static final double LAT = 40.01, LNG = -111.00;

    private void authorizedAgency(String id) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id);
        g.setOwnerEmail(id + "-admin@x.gov");
        g.setAgencyAuthorized(true);
        g.setJurisdictionLat(40.00);
        g.setJurisdictionLng(-111.00);
        g.setJurisdictionRadiusMiles(5.0); // covers LAT/LNG (~0.7 mi away)
        groupRepo.save(g);
    }

    @Test
    void deselect_persistsOnlyChosenTag_throughRealCreatePath() {
        when(geocode.reverse(anyDouble(), anyDouble())).thenReturn(null);
        authorizedAgency("city");
        authorizedAgency("county");

        // A SEPARATE incoming payload — the way the real request binds it.
        Post incoming = new Post();
        incoming.setKind("civic-report");
        incoming.setCivicCategory("pothole");
        incoming.setDescription("Deep pothole");
        incoming.setLatitude(LAT);
        incoming.setLongitude(LNG);
        incoming.setCivicAgencyIds(new ArrayList<>(List.of("city"))); // filer deselected "county"

        PostDto created = postService.create(incoming, "resident@example.com");
        Long postId = created.id();

        List<CivicReportAgency> active = tagRepo.findByPostIdAndActiveTrue(postId);
        // The whole point: ONLY "city" is active — NOT both. Fails without the copy.
        assertThat(active).extracting(CivicReportAgency::getAgencyGroupId)
                .containsExactly("city");
        // "county" was auto-derived (it covers the point) but deselected → tombstone.
        CivicReportAgency county = tagRepo.findByPostIdAndAgencyGroupId(postId, "county").orElseThrow();
        assertThat(county.isActive()).isFalse();
        assertThat(county.getTagSource()).isEqualTo("auto");
    }
}
