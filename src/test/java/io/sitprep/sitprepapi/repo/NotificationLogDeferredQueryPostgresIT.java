package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three quiet-hours catch-up queries on REAL Postgres, over the real
 * Flyway schema through V100 (EXEC-N). {@code notification_log} carries a
 * jsonb column H2 cannot create, so the default H2 {@code test} profile can't
 * host this; it follows {@code AgencyJurisdictionServicePostgresIT}'s pattern.
 *
 * <p><b>Runs only when {@code NOTIFY_PG_IT=true}</b>, with
 * {@code spring.datasource.url} pointed at a throwaway local DB whose schema
 * was built by replaying {@code db/migration/*.sql} through psql. Skipped on
 * CI/Heroku. Each test rolls back.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@EnabledIfEnvironmentVariable(named = "NOTIFY_PG_IT", matches = "true")
class NotificationLogDeferredQueryPostgresIT {

    private static final Instant FROM = Instant.parse("2026-10-07T04:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-07T14:00:00Z");
    private static final Instant NIGHT = Instant.parse("2026-10-07T09:00:00Z");

    @Autowired
    private NotificationLogRepo repo;

    private NotificationLog row(String email, String type, Instant at, String reason) {
        NotificationLog n = new NotificationLog(email, type, null, "t", "b", null, "/x", null, at, false, "Lane B");
        n.setLane("B");
        n.setDeferredReason(reason);
        return repo.save(n);
    }

    @Test
    void countsOnlyUnreadUnarchivedQuietRowsInsideTheWindow() {
        row("Ana@X.com", "dm_message", NIGHT, "QUIET_HOURS");                       // counts (case-insensitive)
        row("ana@x.com", "check_in_request", NIGHT.plusSeconds(60), "QUIET_HOURS"); // counts
        row("ana@x.com", "dm_message", NIGHT, null).getId();                        // rate cap etc.: not marked
        NotificationLog read = row("ana@x.com", "dm_message", NIGHT, "QUIET_HOURS");
        read.setReadAt(TO);
        repo.save(read);
        NotificationLog archived = row("ana@x.com", "dm_message", NIGHT, "QUIET_HOURS");
        archived.setArchivedAt(TO);
        repo.save(archived);
        row("ana@x.com", "dm_message", FROM.minusSeconds(1), "QUIET_HOURS");        // previous night
        row("ana@x.com", "dm_message", TO, "QUIET_HOURS");                           // end is exclusive
        row("bo@x.com", "dm_message", NIGHT, "QUIET_HOURS");                         // someone else

        assertThat(repo.countDeferredUnread("ana@x.com", "QUIET_HOURS", FROM, TO)).isEqualTo(2);
    }

    @Test
    void recipientsAreDistinctLowerCasedAndKeysetPaged() {
        row("Bo@x.com", "dm_message", NIGHT, "QUIET_HOURS");
        row("bo@x.com", "dm_message", NIGHT, "QUIET_HOURS");
        row("ana@x.com", "dm_message", NIGHT, "QUIET_HOURS");
        row("cy@x.com", "dm_message", NIGHT, null);                                  // not marked
        NotificationLog read = row("di@x.com", "dm_message", NIGHT, "QUIET_HOURS");
        read.setReadAt(TO);
        repo.save(read);                                                             // read: not a candidate
        row("ed@x.com", "dm_message", FROM.minusSeconds(3600 * 30), "QUIET_HOURS"); // too old

        assertThat(repo.findDeferredUnreadRecipients("QUIET_HOURS", FROM.minusSeconds(3600),
                "", PageRequest.of(0, 1))).containsExactly("ana@x.com");
        assertThat(repo.findDeferredUnreadRecipients("QUIET_HOURS", FROM.minusSeconds(3600),
                "ana@x.com", PageRequest.of(0, 10))).containsExactly("bo@x.com");
    }

    @Test
    void theSummaryRowIsTheOncePerNightStamp() {
        row("ana@x.com", "quiet_hours_catch_up", TO.plusSeconds(60), null);

        assertThat(repo.existsTypeSince("ANA@x.com", "quiet_hours_catch_up", TO)).isTrue();
        assertThat(repo.existsTypeSince("ana@x.com", "quiet_hours_catch_up", TO.plusSeconds(3600))).isFalse();
        assertThat(repo.existsTypeSince("bo@x.com", "quiet_hours_catch_up", TO)).isFalse();
    }
}
