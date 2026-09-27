package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.MapConfirmation;
import io.sitprep.sitprepapi.domain.ResourceListing;
import io.sitprep.sitprepapi.domain.UserSavedLocation;
import io.sitprep.sitprepapi.service.MapConfirmationService;
import io.sitprep.sitprepapi.util.OpeningHours;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    @Autowired MapConfirmationRepo confirmations;
    @Autowired MapConfirmationService confirmationService;

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

    @Test
    void confirmationSummariesCountDistinctPeopleInTheLastSevenDays() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        confirm("node/1", "a@x.com", now.minus(1, ChronoUnit.HOURS));
        confirm("node/1", "b@x.com", now.minus(1, ChronoUnit.MINUTES));
        confirm("node/1", "c@x.com", now.minus(8, ChronoUnit.DAYS)); // outside the window
        confirm("node/2", "a@x.com", now.minus(10, ChronoUnit.SECONDS));
        em.flush();

        var byId = confirmationService.summaries("osm", List.of("node/1", "node/2", "node/3"), now);
        assertThat(byId).containsOnlyKeys("node/1", "node/2");
        assertThat(byId.get("node/1").count()).isEqualTo(2);
        assertThat(byId.get("node/1").lastAt()).isEqualTo(now.minus(1, ChronoUnit.MINUTES));
        assertThat(byId.get("node/2").count()).isEqualTo(1);
    }

    @Test
    void aPersonIsOneRowAndTheCooldownHoldsAgainstTheRealTable() {
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var first = confirmationService.confirm("osm", "way/9", "Ann@X.com", t0);
        var tooSoon = confirmationService.confirm("osm", "way/9", "ann@x.com", t0.plus(1, ChronoUnit.MINUTES));
        var later = confirmationService.confirm("osm", "way/9", "ann@x.com", t0.plus(11, ChronoUnit.MINUTES));

        assertThat(first.accepted()).isTrue();
        assertThat(first.count()).isEqualTo(1);
        assertThat(tooSoon.accepted()).isFalse();
        assertThat(later.accepted()).isTrue();
        assertThat(later.count()).as("still one person").isEqualTo(1);
        assertThat(confirmations.findAll().stream().filter(c -> "way/9".equals(c.getTargetId())).count())
                .isEqualTo(1);
    }

    private void confirm(String osmId, String email, Instant at) {
        MapConfirmation c = new MapConfirmation();
        c.setTargetType("osm");
        c.setTargetId(osmId);
        c.setUserEmail(email);
        c.setConfirmedAt(at);
        confirmations.save(c);
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
