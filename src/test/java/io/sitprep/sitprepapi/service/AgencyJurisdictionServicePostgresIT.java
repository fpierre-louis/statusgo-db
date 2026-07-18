package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * REAL-Postgres validation of {@link AgencyJurisdictionService#agenciesCovering}
 * (owner-requested; pure-Mockito units are necessary but not sufficient). Runs
 * against a throwaway local Postgres DB whose schema is the real Flyway schema
 * (replayed by the runner script), so {@code jurisdictionZips} membership goes
 * through the actual {@code group_jurisdiction_zips} join table (VARCHAR(12)) on
 * a real Postgres engine — the exact surface H2/PostgreSQL-mode could diverge on.
 *
 * <p><b>Runs only when {@code JURISDICTION_PG_IT=true} is in the environment</b>
 * (see {@code application-it-pg.yml}); on CI/Heroku it is skipped, so it can
 * never break the normal build. It seeds fixtures inside the per-test
 * transaction ({@code @DataJpaTest} rolls back), never mutating anything durable.
 *
 * <p>The resolver is constructed by hand over the autowired (real) {@link
 * GroupRepo} plus a real {@link AgencyAuthorizationService} (its two ctor deps
 * are untouched by {@code hasGeo}) — so every repo query in this test executes
 * against Postgres, not a mock.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@EnabledIfEnvironmentVariable(named = "JURISDICTION_PG_IT", matches = "true")
class AgencyJurisdictionServicePostgresIT {

    @Autowired GroupRepo groupRepo;
    @Autowired TestEntityManager em;

    private AgencyJurisdictionService svc;

    @BeforeEach
    void init() {
        svc = new AgencyJurisdictionService(groupRepo, new AgencyAuthorizationService(null, null));
    }

    // Agency center used across radius tests.
    private static final double C_LAT = 40.00, C_LNG = -111.00;
    // Due-north offsets (Haversine, EARTH_RADIUS 6371.0088): 1° lat ≈ 69.09 mi.
    private static final double LAT_0_69MI  = 40.01;   // ~0.69 mi  (inside a 5 mi circle)
    private static final double LAT_34MI    = 40.50;   // ~34.5 mi  (clearly inside 50)
    private static final double LAT_JUST_IN = 40.72;   // ~49.75 mi (just inside the 50 mi cap)
    private static final double LAT_JUST_OUT= 40.73;   // ~50.44 mi (just outside the 50 mi cap)
    private static final double LAT_55MI    = 40.80;   // ~55.3 mi  (clearly outside 50)
    private static final double LAT_69MI    = 41.00;   // ~69.1 mi  (far)

    private Group agency(String id, String name, boolean authorized,
                         Double lat, Double lng, Double radiusMi, List<String> zips) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(name);           // set for deterministic ORDER BY groupName
        g.setAgencyAuthorized(authorized);
        g.setJurisdictionLat(lat);
        g.setJurisdictionLng(lng);
        g.setJurisdictionRadiusMiles(radiusMi);
        g.setJurisdictionZips(zips);
        return groupRepo.save(g);
    }

    /** Flush inserts and detach so the resolver's repo queries re-read from
     *  Postgres (EAGER jurisdictionZips reload = real join-table round trip). */
    private void sync() {
        em.flush();
        em.clear();
    }

    private static List<String> ids(List<Group> gs) {
        return gs.stream().map(Group::getGroupId).toList();
    }

    // ---- ZIP membership against the real group_jurisdiction_zips join table ----

    @Test
    void zipMembership_singleZip_matchesRealJoinTable() {
        agency("county", "county", true, null, null, null, List.of("84043")); // zip-only, no geo
        sync();
        assertThat(ids(svc.agenciesCovering(null, null, "84043"))).containsExactly("county");
        assertThat(svc.agenciesCovering(null, null, "00000")).isEmpty();
    }

    @Test
    void zipMembership_multiZip_matchesAnyMemberOnly() {
        agency("water", "water", true, null, null, null, List.of("84003", "84043", "84045"));
        sync();
        assertThat(ids(svc.agenciesCovering(null, null, "84045"))).containsExactly("water");
        assertThat(ids(svc.agenciesCovering(null, null, "84003"))).containsExactly("water");
        assertThat(svc.agenciesCovering(null, null, "84010")).isEmpty(); // not in the set
    }

    @Test
    void zipMembership_trimmedQuery_matchesExactStoredZip() {
        agency("county", "county", true, null, null, null, List.of("84043"));
        sync();
        // resolver trims; real-Postgres exact string match after trim
        assertThat(ids(svc.agenciesCovering(null, null, "  84043  "))).containsExactly("county");
    }

    @Test
    void zipMembership_nullAndEmptyZips_roundTripAndDoNotMatch() {
        agency("nullzips", "a-nullzips", true, null, null, null, null);        // null collection
        agency("emptyzips", "b-emptyzips", true, null, null, null, List.of()); // empty collection
        // both must persist/reload cleanly on Postgres and match nothing by zip
        assertThatCode(this::sync).doesNotThrowAnyException();
        assertThat(svc.agenciesCovering(null, null, "84043")).isEmpty();
    }

    // ---- 50-mile radius cap boundary (Haversine in Java over real rows) ----

    @Test
    void radius_justInsideCap_matches() {
        agency("cityCap", "cityCap", true, C_LAT, C_LNG, 50.0, null);
        sync();
        assertThat(ids(svc.agenciesCovering(LAT_JUST_IN, C_LNG, null))).containsExactly("cityCap");
    }

    @Test
    void radius_justOutsideCap_doesNotMatch() {
        agency("cityCap", "cityCap", true, C_LAT, C_LNG, 50.0, null);
        sync();
        assertThat(svc.agenciesCovering(LAT_JUST_OUT, C_LNG, null)).isEmpty();
    }

    @Test
    void radius_overFiftyMileCap_neverMatches_evenAtCenter() {
        // radius 51 > MAX_RADIUS_MILES(50) → hasGeo() rejects → uncoverable
        agency("tooBig", "tooBig", true, C_LAT, C_LNG, 51.0, null);
        sync();
        assertThat(svc.agenciesCovering(C_LAT, C_LNG, null)).isEmpty();
    }

    // ---- Inclusive union (radius OR zip) ----

    @Test
    void union_radiusOnly_zipOnly_both_neither() {
        agency("cityRadius", "cityRadius", true, C_LAT, C_LNG, 5.0, null);          // radius
        agency("countyZip", "countyZip", true, null, null, null, List.of("84043")); // zip
        agency("bothMatch", "bothMatch", true, C_LAT, C_LNG, 5.0, List.of("84043"));// both sides
        sync();

        assertThat(ids(svc.agenciesCovering(LAT_0_69MI, C_LNG, null)))
                .containsExactlyInAnyOrder("cityRadius", "bothMatch");            // radius-only inputs
        assertThat(ids(svc.agenciesCovering(null, null, "84043")))
                .containsExactlyInAnyOrder("countyZip", "bothMatch");            // zip-only inputs
        // an agency matched by BOTH sides appears exactly once (intra-agency dedup)
        assertThat(ids(svc.agenciesCovering(LAT_0_69MI, C_LNG, "84043")))
                .filteredOn("bothMatch"::equals).hasSize(1);
        // neither input usable
        assertThat(svc.agenciesCovering(null, null, "   ")).isEmpty();
    }

    // ---- Overlap preserved across DISTINCT agencies (civic decision 8) ----

    @Test
    void overlap_cityRadiusAndCountyZip_returnsBothNotDeduped() {
        agency("city", "city", true, C_LAT, C_LNG, 5.0, null);               // radius match
        agency("county", "county", true, null, null, null, List.of("84043")); // zip match
        sync();
        // point inside the city circle AND in the county's zip set → BOTH
        assertThat(ids(svc.agenciesCovering(LAT_0_69MI, C_LNG, "84043")))
                .containsExactly("county", "city"); // zip side ordered first, then radius
    }

    // ---- Over-tagging guard ----

    @Test
    void overTagging_agencyThatDoesNotCoverLocationIsNotReturned() {
        agency("farCity", "farCity", true, C_LAT, C_LNG, 5.0, null);          // 5 mi circle
        agency("otherZip", "otherZip", true, null, null, null, List.of("99999")); // unrelated zip
        sync();
        // point ~69 mi away with a zip neither agency claims → tagged to NOBODY
        assertThat(svc.agenciesCovering(LAT_69MI, C_LNG, "84043")).isEmpty();
    }

    @Test
    void clearlyInsideVsClearlyOutside() {
        agency("cityCap", "cityCap", true, C_LAT, C_LNG, 50.0, null);
        sync();
        assertThat(ids(svc.agenciesCovering(LAT_34MI, C_LNG, null))).containsExactly("cityCap");
        assertThat(svc.agenciesCovering(LAT_55MI, C_LNG, null)).isEmpty();
    }

    // ---- Authorization filter (both sides only ever return authorized) ----

    @Test
    void unauthorizedGroupWithCoveringGeoAndZip_isExcluded() {
        // agencyAuthorized=false, yet covers the point by BOTH radius and zip
        agency("stray", "stray", false, C_LAT, C_LNG, 5.0, List.of("84043"));
        sync();
        assertThat(svc.agenciesCovering(LAT_0_69MI, C_LNG, "84043")).isEmpty();
    }

    // ---- Null / missing input combinations (no throw, sensible sets) ----

    @Test
    void missingInputCombinations_doNotThrowAndResolveSensibly() {
        agency("city", "city", true, C_LAT, C_LNG, 5.0, null);
        agency("county", "county", true, null, null, null, List.of("84043"));
        sync();

        assertThat(svc.agenciesCovering(null, null, null)).isEmpty();                 // nothing usable
        assertThat(ids(svc.agenciesCovering(LAT_0_69MI, null, "84043")))              // half-null geo
                .containsExactly("county");                                            // → zip side only
        assertThat(ids(svc.agenciesCovering(LAT_0_69MI, C_LNG, null)))                // no zip
                .containsExactly("city");                                              // → radius side only
    }

    // ---- Determinism ----

    @Test
    void ordering_isDeterministicAcrossRuns() {
        agency("city", "city", true, C_LAT, C_LNG, 5.0, null);
        agency("county", "county", true, null, null, null, List.of("84043"));
        sync();
        List<String> a = ids(svc.agenciesCovering(LAT_0_69MI, C_LNG, "84043"));
        List<String> b = ids(svc.agenciesCovering(LAT_0_69MI, C_LNG, "84043"));
        assertThat(a).isEqualTo(b).containsExactly("county", "city");
    }
}
