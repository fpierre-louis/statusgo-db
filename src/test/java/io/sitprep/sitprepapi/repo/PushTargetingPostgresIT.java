package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertPushTargetingAudit;
import io.sitprep.sitprepapi.domain.GeoPoint;
import io.sitprep.sitprepapi.domain.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R19 on REAL Postgres (V111 + the household-home queries), over the replayed
 * Flyway schema with ddl-auto=validate. Runs only with ALERT_PG_IT=true against
 * the throwaway local sitprep_alerts_it. Never prod. Each test rolls back.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sitprep_alerts_it",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ALERT_PG_IT", matches = "true")
class PushTargetingPostgresIT {

    @Autowired private GroupRepo groups;
    @Autowired private AlertPushTargetingAuditRepo audits;
    @Autowired private TestEntityManager em;

    private Group group(String id, String type, double lat, double lng, String owner, List<String> members) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(id);
        g.setGroupType(type);
        g.setHomeLocation(new GeoPoint(lat, lng));
        g.setOwnerEmail(owner);
        g.setMemberEmails(new ArrayList<>(members));
        g.setAdminEmails(new ArrayList<>());
        return em.persistAndFlush(g);
    }

    @Test
    void householdsInTheBoxComeWithTheirMembers_orgsNever() {
        group("r19-home-in", "Household", 40.40, -111.85, "mom@x", List.of("kid@x"));
        group("r19-home-out", "Household", 41.50, -111.85, "far@x", List.of());
        group("r19-org-in", "Church", 40.40, -111.85, "pastor@x", List.of("member@x"));
        em.clear();

        List<Group> found = groups.findHouseholdsWithHomeInBounds(40.30, 40.50, -111.95, -111.75);
        assertThat(found).extracting(Group::getGroupId).containsExactly("r19-home-in");
        assertThat(found.get(0).getMemberEmails()).containsExactly("kid@x");

        assertThat(groups.findHouseholdHomeCoordinates())
                .anySatisfy(row -> assertThat(((Number) row[0]).doubleValue()).isEqualTo(40.40));
    }

    @Test
    void theAuditRowRoundTrips() {
        AlertPushTargetingAudit row = new AlertPushTargetingAudit();
        row.setCreatedAt(Instant.parse("2026-10-18T12:00:00Z"));
        row.setAlertRef("NWS-urn:oid:test");
        row.setBasis("outline+5km");
        row.setReachedHere(3);
        row.setReachedHome(1);
        row.setFallbackProximity(0);
        row.setProximityWould(5);
        row.setAddedVsProximity(1);
        row.setDroppedVsProximity(3);
        Long id = audits.saveAndFlush(row).getId();
        em.clear();
        assertThat(audits.findById(id)).get().extracting(AlertPushTargetingAudit::getDroppedVsProximity).isEqualTo(3);
    }
}
