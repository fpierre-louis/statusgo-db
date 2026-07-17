package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit spec for the Phase-1 jurisdiction resolver (owner decision D1 "both,
 * with roles"). Pure Mockito — no Spring context, no DB. Uses a REAL
 * {@link AgencyAuthorizationService} so the resolver's radius gating goes
 * through the same {@code hasGeo} (lat/lng valid + radius in (0, 50]) the alert
 * path uses; its two constructor deps are never touched by {@code hasGeo}, so
 * they can be null here.
 */
@ExtendWith(MockitoExtension.class)
class AgencyJurisdictionServiceTest {

    @Mock GroupRepo groupRepo;

    private final AgencyAuthorizationService agencyAuth = new AgencyAuthorizationService(null, null);

    private AgencyJurisdictionService svc() {
        return new AgencyJurisdictionService(groupRepo, agencyAuth);
    }

    // ~0.69 mi north of the city center below — inside a 5 mi circle.
    private static final double LAT = 40.01;
    private static final double LNG = -111.00;

    private static Group agency(String id, Double lat, Double lng, Double radiusMi, List<String> zips) {
        Group g = new Group();
        g.setGroupId(id);
        g.setAgencyAuthorized(true);
        g.setJurisdictionLat(lat);
        g.setJurisdictionLng(lng);
        g.setJurisdictionRadiusMiles(radiusMi);
        g.setJurisdictionZips(zips);
        return g;
    }

    @Test
    void radiusMatch_returnsAgencyWithinCircle() {
        Group city = agency("city", 40.00, -111.00, 5.0, null);
        when(groupRepo.findAuthorizedAgencies()).thenReturn(List.of(city));

        assertThat(svc().agenciesCovering(LAT, LNG, null))
                .extracting(Group::getGroupId).containsExactly("city");
    }

    @Test
    void outsideRadius_noZip_returnsEmpty() {
        Group city = agency("city", 40.00, -111.00, 5.0, null);
        when(groupRepo.findAuthorizedAgencies()).thenReturn(List.of(city));

        // ~69 mi north — well outside the 5 mi circle, and no zip supplied.
        assertThat(svc().agenciesCovering(41.00, -111.00, null)).isEmpty();
    }

    @Test
    void zipMatch_returnsZipAgencyEvenWithoutGeo() {
        Group county = agency("county", null, null, null, List.of("84043")); // no geo -> zip only
        when(groupRepo.findByJurisdictionZip("84043")).thenReturn(List.of(county));
        when(groupRepo.findAuthorizedAgencies()).thenReturn(List.of(county));

        assertThat(svc().agenciesCovering(LAT, LNG, "84043"))
                .extracting(Group::getGroupId).containsExactly("county");
    }

    @Test
    void overlap_cityRadiusAndCountyZip_returnsBothDeduped() {
        Group city = agency("city", 40.00, -111.00, 5.0, null);              // radius match
        Group county = agency("county", null, null, null, List.of("84043")); // zip match, no geo
        when(groupRepo.findByJurisdictionZip("84043")).thenReturn(List.of(county));
        when(groupRepo.findAuthorizedAgencies()).thenReturn(List.of(city, county));

        // Overlap is intended (civic decision 8); zip side ordered first.
        assertThat(svc().agenciesCovering(LAT, LNG, "84043"))
                .extracting(Group::getGroupId).containsExactly("county", "city");
    }

    @Test
    void matchedByBothRadiusAndZip_appearsOnce() {
        Group both = agency("both", 40.00, -111.00, 5.0, List.of("84043"));
        when(groupRepo.findByJurisdictionZip("84043")).thenReturn(List.of(both));
        when(groupRepo.findAuthorizedAgencies()).thenReturn(List.of(both));

        assertThat(svc().agenciesCovering(LAT, LNG, "84043"))
                .extracting(Group::getGroupId).containsExactly("both");
    }

    @Test
    void unauthorizedAgencyFromZipQuery_isFilteredOut() {
        Group stray = agency("stray", null, null, null, List.of("84043"));
        stray.setAgencyAuthorized(false);
        when(groupRepo.findByJurisdictionZip("84043")).thenReturn(List.of(stray));

        // null lat/lng -> radius side skipped -> findAuthorizedAgencies never consulted.
        assertThat(svc().agenciesCovering(null, null, "84043")).isEmpty();
    }

    @Test
    void noUsableInputs_returnsEmpty() {
        // blank zip normalizes to none; null coords skip the radius side.
        assertThat(svc().agenciesCovering(null, null, "   ")).isEmpty();
    }
}
