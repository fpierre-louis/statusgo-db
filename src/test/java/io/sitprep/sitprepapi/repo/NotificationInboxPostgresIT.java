package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.notifications.NotificationInboxFilter;
import io.sitprep.sitprepapi.repo.NotificationLogInboxQuery.InboxCounts;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 C5's inbox reads on REAL Postgres (the JPQL in
 * {@link NotificationLogInboxQueryImpl} and the bulk reads in
 * {@link NotificationLogRepo}), over the replayed Flyway schema.
 *
 * <p><b>Runs only when {@code ALERT_PG_IT=true}</b>, against the throwaway
 * local {@code sitprep_alerts_it}. Never prod. Each test rolls back.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sitprep_alerts_it",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ALERT_PG_IT", matches = "true")
class NotificationInboxPostgresIT {

    private static final String ME = "me@x.com";
    private static final String OTHER = "other@x.com";
    private static final Instant T0 = Instant.parse("2026-10-14T12:00:00Z");

    @Autowired private NotificationLogRepo repo;
    @Autowired private TestEntityManager em;

    private NotificationLog row(String to, String type, String category, String referenceId, Instant ts) {
        NotificationLog n = new NotificationLog(to, type, null, "t", "b", referenceId, null, null, ts, true, null);
        n.setCategory(category);
        return em.persistAndFlush(n);
    }

    private NotificationLog alertRow(String to, String key, String revisionRef, String thread, Instant ts) {
        NotificationLog n = row(to, "hazard_alert", "NWS_SEVERE_EXTREME", key.replace(':', '-'), ts);
        n.setAlertKey(key);
        n.setAlertRevisionRef(revisionRef);
        n.setAlertThreadKey(thread);
        return em.persistAndFlush(n);
    }

    @Test
    void theKeysetNeverSkipsARowSharingTheBoundaryTimestamp() {
        NotificationLog older = row(ME, "follow", null, null, T0.minusSeconds(60));
        NotificationLog a = row(ME, "follow", null, null, T0);
        NotificationLog b = row(ME, "follow", null, null, T0);
        NotificationLog c = row(ME, "follow", null, null, T0);

        List<NotificationLog> first = repo.findInboxPage(ME, NotificationInboxFilter.ALL, null, null, null, 2);
        assertThat(first).extracting(NotificationLog::getId).containsExactly(c.getId(), b.getId());

        NotificationLog last = first.get(1);
        List<NotificationLog> next = repo.findInboxPage(ME, NotificationInboxFilter.ALL, null,
                last.getTimestamp(), last.getId(), 2);
        assertThat(next).extracting(NotificationLog::getId).containsExactly(a.getId(), older.getId());

        // Without beforeId the old strict cursor still works as it always did.
        assertThat(repo.findInboxPage(ME, NotificationInboxFilter.ALL, null, T0, null, 10))
                .extracting(NotificationLog::getId).containsExactly(older.getId());
    }

    @Test
    void filtersAndCountsAgree_alertsNeverIncludeLegacyCheckIns_needsResponseNeverIncludesAlerts() {
        NotificationLog hazard = alertRow(ME, "NWS:w1", "NWS:w1@aaaaaaaaaaaa", "VTEC:K.TO.W.1.2026", T0);
        NotificationLog agency = row(ME, "hazard_alert", "AGENCY_ALERT", "agency-alert:7", T0.plusSeconds(1));
        NotificationLog checkIn = row(ME, "alert", "GROUP_ALERT_ORG", "g1", T0.plusSeconds(2));
        NotificationLog request = row(ME, "check_in_request", null, "g1", T0.plusSeconds(3));
        NotificationLog reviewNoRef = row(ME, "checkin_reminder", null, null, T0.plusSeconds(4));
        NotificationLog reviewRef = row(ME, "checkin_reminder", null, "g1", T0.plusSeconds(5));
        NotificationLog dmByCategory = row(ME, null, "DIRECT_MESSAGE", null, T0.plusSeconds(6));
        NotificationLog readMention = row(ME, "post_mention", null, "p1", T0.plusSeconds(7));
        readMention.setReadAt(T0.plusSeconds(8));
        em.persistAndFlush(readMention);
        NotificationLog follow = row(ME, "follow", null, null, T0.plusSeconds(9));
        NotificationLog archived = row(ME, "check_in_request", null, "g2", T0.plusSeconds(10));
        archived.setArchivedAt(T0.plusSeconds(11));
        em.persistAndFlush(archived);
        row(OTHER, "check_in_request", null, "g1", T0.plusSeconds(12));

        assertThat(ids(NotificationInboxFilter.ALERTS))
                .containsExactlyInAnyOrder(hazard.getId(), agency.getId());
        assertThat(ids(NotificationInboxFilter.NEEDS_RESPONSE))
                .containsExactlyInAnyOrder(checkIn.getId(), request.getId(), reviewRef.getId(), dmByCategory.getId());
        assertThat(ids(NotificationInboxFilter.UNREAD)).hasSize(8).doesNotContain(readMention.getId());
        assertThat(ids(NotificationInboxFilter.ALL)).hasSize(9).doesNotContain(archived.getId());

        // The SQL predicate and the Java rule agree, row by row.
        for (NotificationLog r : List.of(hazard, agency, checkIn, request, reviewNoRef, reviewRef,
                dmByCategory, readMention, follow)) {
            assertThat(ids(NotificationInboxFilter.NEEDS_RESPONSE).contains(r.getId()))
                    .as("row %s (%s/%s)", r.getId(), r.getType(), r.getCategory())
                    .isEqualTo(NotificationInboxFilter.needsResponse(r));
        }

        InboxCounts counts = repo.countInbox(ME);
        assertThat(counts).isEqualTo(new InboxCounts(9, 8, 2, 2, 4));
    }

    @Test
    void aThreadReadMarksOnlyShownRevisionsUpToTheCutoff() {
        String thread = "VTEC:KPSR.TO.W.0042.2026";
        NotificationLog r1 = alertRow(ME, "NWS:w1", "NWS:w1@111111111111", thread, T0);
        NotificationLog r2 = alertRow(ME, "NWS:w2", "NWS:w2@222222222222", thread, T0.plusSeconds(60));
        NotificationLog notShown = alertRow(ME, "NWS:w3", "NWS:w3@333333333333", thread, T0.plusSeconds(90));
        NotificationLog afterCutoff = alertRow(ME, "NWS:w2", "NWS:w2@222222222222", thread, T0.plusSeconds(300));
        NotificationLog theirs = alertRow(OTHER, "NWS:w1", "NWS:w1@111111111111", thread, T0);

        int updated = repo.markAlertThreadRead(ME, thread, List.of("NWS:w1@111111111111", "NWS:w2@222222222222"),
                T0.plusSeconds(120), T0.plusSeconds(130));
        em.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(readAt(r1)).isNotNull();
        assertThat(readAt(r2)).isNotNull();
        assertThat(readAt(notShown)).as("never rendered").isNull();
        assertThat(readAt(afterCutoff)).as("arrived after the reader's server time").isNull();
        assertThat(readAt(theirs)).as("another recipient").isNull();

        assertThat(repo.findThreadRows(ME, thread, 50)).extracting(NotificationLog::getId)
                .containsExactly(afterCutoff.getId(), notShown.getId(), r2.getId(), r1.getId());
    }

    @Test
    void markAllReadThroughLeavesASameMillisecondLaterRowUnread() {
        NotificationLog a = row(ME, "follow", null, null, T0);
        NotificationLog seen = row(ME, "follow", null, null, T0.plusSeconds(1));
        NotificationLog sameMs = row(ME, "follow", null, null, T0.plusSeconds(1));
        NotificationLog later = row(ME, "follow", null, null, T0.plusSeconds(2));

        NotificationLog found = repo.findByIdForUser(seen.getId(), ME).orElseThrow();
        assertThat(repo.findByIdForUser(seen.getId(), OTHER)).isEmpty();
        int updated = repo.markAllReadThrough(ME, found.getTimestamp(), found.getId(), T0.plusSeconds(5));
        em.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(readAt(a)).isNotNull();
        assertThat(readAt(seen)).isNotNull();
        assertThat(readAt(sameMs)).isNull();
        assertThat(readAt(later)).isNull();
    }

    @Test
    void theThreadsLastDeliveryMatchesKeyOrThreadOrALegacyReference() {
        String thread = "VTEC:KPSR.TO.W.0042.2026";
        NotificationLog legacy = row(ME, "hazard_alert", null, "NWS-urn:oid:w0", T0.minusSeconds(600));
        NotificationLog w1 = alertRow(ME, "NWS:urn:oid:w1", "NWS:urn:oid:w1@111111111111", thread, T0);
        alertRow(OTHER, "NWS:urn:oid:w1", "NWS:urn:oid:w1@111111111111", thread, T0);
        NotificationLog unrelated = alertRow(ME, "NWS:urn:oid:x", "NWS:urn:oid:x@999999999999", "VTEC:OTHER", T0.plusSeconds(60));

        assertThat(repo.findLatestDeliveredInThread(thread, List.of(), List.of(), T0.minusSeconds(3600)))
                .map(NotificationLog::getAlertKey).contains("NWS:urn:oid:w1");
        assertThat(repo.findLatestDeliveredInThread(null, List.of("NWS:urn:oid:w0"), List.of("NWS-urn:oid:w0"),
                T0.minusSeconds(3600))).map(NotificationLog::getId).contains(legacy.getId());
        // The since bound excludes an older delivery.
        assertThat(repo.findLatestDeliveredInThread(null, List.of(), List.of("NWS-urn:oid:w0"), T0)).isEmpty();
        // A keyed row is never matched by its referenceId alone.
        assertThat(repo.findLatestDeliveredInThread(null, List.of(), List.of("NWS-urn:oid:x"), T0.minusSeconds(3600)))
                .isEmpty();

        assertThat(repo.findThreadRecipients(thread, List.of("NWS:urn:oid:w1"), List.of(), T0.minusSeconds(3600)))
                .containsExactlyInAnyOrder(ME, OTHER);
        assertThat(w1.getId()).isNotEqualTo(unrelated.getId());
    }

    private List<Long> ids(NotificationInboxFilter f) {
        List<Long> out = new ArrayList<>();
        for (NotificationLog n : repo.findInboxPage(ME, f, null, null, null, 100)) out.add(n.getId());
        return out;
    }

    private Instant readAt(NotificationLog n) {
        return em.find(NotificationLog.class, n.getId()).getReadAt();
    }

    @SuppressWarnings("unused")
    private static Set<String> set(String... v) {
        return Set.of(v);
    }
}
