package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.util.OpeningHours;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The map-ideal columns survive a real Hibernate round trip (H2, PostgreSQL
 * mode) — the JSONB hours mapping and the saved-place presence columns. Unit
 * tests with mocked repos cannot catch a mapping that compiles but does not
 * persist.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MapIdealPersistenceTest {

    @Autowired ResourceListingRepo resources;
    @Autowired UserSavedLocationRepo places;
    @Autowired EntityManager em;

    @Test
    void resourceHoursRoundTripAsJsonb() {
        Map<String, Object> hours = OpeningHours.parse(Map.of(
                "tz", "America/Denver",
                "weekly", Map.of("fri", List.of(List.of("18:00", "02:00"))),
                "note", "Cash only")).toJson();
        ResourceListing r = new ResourceListing();
        r.setTitle("Cooling center");
        r.setHoursJson(hours);
        Long id = resources.save(r).getId();
        em.flush();
        em.clear();

        Map<String, Object> back = resources.findById(id).orElseThrow().getHoursJson();
        assertThat(back).isEqualTo(hours);
        assertThat(OpeningHours.parse(back).toJson()).isEqualTo(hours);
    }

    @Test
    void presenceColumnsPersistAndTheOptInQueryFiltersOnThem() {
        UserSavedLocation shared = place("School", true);
        shared.setKind("school");
        shared.setRadiusM(300);
        places.save(shared);
        places.save(place("Home", false));
        em.flush();
        em.clear();

        List<UserSavedLocation> optedIn = places.findByOwnerEmailIgnoreCaseAndSharePresenceTrue("ann@persist.test");
        assertThat(optedIn).extracting(UserSavedLocation::getName).containsExactly("School");
        assertThat(optedIn.get(0).getKind()).isEqualTo("school");
        assertThat(optedIn.get(0).getRadiusM()).isEqualTo(300);
    }

    private static UserSavedLocation place(String name, boolean share) {
        UserSavedLocation p = new UserSavedLocation();
        p.setOwnerEmail("ann@persist.test");
        p.setName(name);
        p.setLatitude(40.39);
        p.setLongitude(-111.85);
        p.setSharePresence(share);
        return p;
    }
}
