package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link PracticeContentControl} maps and round-trips on H2 (the V102 SQL itself is checked on scratch Postgres). */
@DataJpaTest
@ActiveProfiles("test")
class PracticeContentControlRepoTest {

    @Autowired
    private PracticeContentControlRepo repo;

    @Autowired
    private TestEntityManager em;

    @Test
    void everyColumnRoundTrips() {
        Instant at = Instant.parse("2026-10-08T15:00:00Z");
        PracticeContentControl c = new PracticeContentControl();
        c.setContentKey("comms-outage-family-reconnect");
        c.setDisabledAt(at);
        c.setDisabledReason("wording under review");
        c.setUpdatedBy("mod@example.com");
        c.setUpdatedAt(at);
        repo.save(c);
        em.flush();
        em.clear();

        PracticeContentControl back = repo.findById("comms-outage-family-reconnect").orElseThrow();
        assertThat(back.isDisabled()).isTrue();
        assertThat(back.getDisabledAt()).isEqualTo(at);
        assertThat(back.getDisabledReason()).isEqualTo("wording under review");
        assertThat(back.getUpdatedBy()).isEqualTo("mod@example.com");
        assertThat(back.getUpdatedAt()).isEqualTo(at);
    }
}
