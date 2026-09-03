package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.PlanActivation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The expiry sweep's candidate query, against a real ORM instead of a mock.
 *
 * <p>{@code PlanActivationExpiryTest} exercises the service with a fake repo
 * whose filter is the JPQL restated in Java — which proves the service and
 * proves nothing about the query. Three predicates decide whether a household
 * is told its plan timed out, told twice, or never told, so they are worth
 * asserting against something that can actually disagree with me.</p>
 *
 * <p>H2 in PostgreSQL mode with Hibernate-built DDL, per the {@code test}
 * profile. That is a real gap from prod for constraint-level behaviour (trap
 * T-4) — but this is a three-clause WHERE, which H2 evaluates the same way.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class PlanActivationExpiryQueryTest {

    @Autowired
    private PlanActivationRepo repo;

    private PlanActivation save(String owner, Instant expiresAt, Instant endedAt, Instant handledAt) {
        PlanActivation a = new PlanActivation();
        a.setOwnerEmail(owner);
        a.setActivatedAt(expiresAt.minus(72, ChronoUnit.HOURS));
        a.setExpiresAt(expiresAt);
        a.setEndedAt(endedAt);
        a.setExpiryHandledAt(handledAt);
        return repo.save(a);
    }

    @Test
    void picksUpOnlyRowsThatTimedOutAndHaveNotBeenHandled() {
        Instant now = Instant.now();
        PlanActivation due      = save("due@x.com",     now.minus(1, ChronoUnit.HOURS), null, null);
        PlanActivation running  = save("running@x.com", now.plus(70, ChronoUnit.HOURS), null, null);
        PlanActivation handled  = save("handled@x.com", now.minus(9, ChronoUnit.HOURS), null, now.minus(9, ChronoUnit.HOURS));
        PlanActivation humanEnd = save("ended@x.com",   now.minus(5, ChronoUnit.HOURS), now.minus(6, ChronoUnit.HOURS), null);

        List<String> found = repo.findExpiredNotHandled(now, PageRequest.of(0, 50))
                .stream().map(PlanActivation::getId).toList();

        assertTrue(found.contains(due.getId()), "an unhandled timeout is what the sweep exists for");
        assertFalse(found.contains(running.getId()), "a running activation has not timed out");
        assertFalse(found.contains(handled.getId()),
                "the handled mark is what stops the hourly job repeating itself");
        assertFalse(found.contains(humanEnd.getId()),
                "a person already ended this and it already broadcast — announcing "
                        + "again when the clock agrees would put two endings in the "
                        + "household's history for one event");
        assertEquals(1, found.size());
    }

    @Test
    void drainsOldestFirstAndHonoursThePageSize() {
        Instant now = Instant.now();
        PlanActivation oldest = save("a@x.com", now.minus(30, ChronoUnit.HOURS), null, null);
        PlanActivation middle = save("b@x.com", now.minus(20, ChronoUnit.HOURS), null, null);
        save("c@x.com", now.minus(10, ChronoUnit.HOURS), null, null);

        List<PlanActivation> page = repo.findExpiredNotHandled(now, PageRequest.of(0, 2));

        assertEquals(2, page.size(), "a backlog drains across ticks rather than in one burst");
        assertEquals(List.of(oldest.getId(), middle.getId()),
                page.stream().map(PlanActivation::getId).toList(),
                "oldest first, so a backlog drains in the order it accumulated");
    }

    @Test
    void aRowExpiringExactlyNowIsDue() {
        // `<=`, not `<`. The boundary decides whether the tick that lands on the
        // second has to wait a full hour for the next one.
        Instant now = Instant.now();
        PlanActivation edge = save("edge@x.com", now, null, null);

        assertEquals(List.of(edge.getId()),
                repo.findExpiredNotHandled(now, PageRequest.of(0, 50))
                        .stream().map(PlanActivation::getId).toList());
    }
}
