package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.AlertPost;
import io.sitprep.sitprepapi.domain.Post;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 C2 — {@link AlertPostRepo#findReadableThreadPosts}: the feed card's
 * {@code communityThreads} come from ONE query, and it lists only what the post
 * endpoint would show any viewer.
 *
 * <p>On REAL Postgres over the replayed Flyway schema: {@code task} carries a
 * jsonb column H2 cannot create, so the {@code test} profile cannot host it
 * (the same reason as {@code NotificationLogDeferredQueryPostgresIT}). Runs only
 * when {@code ALERT_PG_IT=true}, against the throwaway local DB
 * {@code sitprep_alerts_it} (the same one {@link AlertRecordPostgresIT} uses);
 * skipped on CI/Heroku. Each test rolls back. Without the override it read the
 * it-pg profile's {@code sitprep_juris_it} and failed to boot (build-10 backlog).</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/sitprep_alerts_it",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@EnabledIfEnvironmentVariable(named = "ALERT_PG_IT", matches = "true")
class AlertPostThreadQueryPostgresIT {

    @Autowired private AlertPostRepo repo;
    @Autowired private TestEntityManager em;

    private Post post(String kind, String groupId, String requester) {
        Post p = new Post();
        p.setKind(kind);
        p.setGroupId(groupId);
        p.setRequesterEmail(requester);
        p.setTitle("t");
        p.setDescription("d");
        p.setStatus(Post.PostStatus.OPEN);
        p.setPriority(Post.PostPriority.MEDIUM);
        p.setCreatedAt(Instant.parse("2026-10-13T12:00:00Z"));
        p.setUpdatedAt(Instant.parse("2026-10-13T12:00:00Z"));
        return em.persistAndFlush(p);
    }

    private void link(String alertId, String geocell, Post p, Instant resolvedAt) {
        AlertPost ap = new AlertPost();
        ap.setAlertId(alertId);
        ap.setGeocellId(geocell);
        ap.setPostId(p.getId());
        ap.setResolvedAt(resolvedAt);
        em.persistAndFlush(ap);
    }

    @Test
    void listsReadableDispatchedPostsAcrossGeocellsAndResolution() {
        String sys = SystemAccounts.SITPREP_EMAIL;
        Post live = post("alert-update", null, sys);
        Post resolved = post("alert-update", null, sys.toUpperCase());
        Post groupScoped = post("alert-update", "grp-1", sys);
        Post otherKind = post("post", null, sys);
        Post neighbour = post("alert-update", null, "someone@example.com");
        Post merged = post("alert-update", null, sys);
        merged.setMergedIntoPostId(live.getId());
        em.persistAndFlush(merged);
        Post unrelated = post("alert-update", null, sys);

        link("NWS-urn:oid:A", "841", live, null);
        link("NWS-urn:oid:A", "842", resolved, Instant.parse("2026-10-13T13:00:00Z"));
        link("NWS-urn:oid:A", "843", groupScoped, null);
        link("NWS-urn:oid:A", "844", otherKind, null);
        link("NWS-urn:oid:A", "845", neighbour, null);
        link("NWS-urn:oid:A", "846", merged, null);
        link("NWS-urn:oid:Z", "841", unrelated, null);

        List<AlertPostRepo.ThreadPostRef> rows =
                repo.findReadableThreadPosts(List.of("NWS-urn:oid:A", "NWS-urn:oid:NONE"), sys);

        assertThat(rows).extracting(AlertPostRepo.ThreadPostRef::getPostId)
                .containsExactlyInAnyOrder(live.getId(), resolved.getId());
        assertThat(rows).extracting(AlertPostRepo.ThreadPostRef::getAlertId).containsOnly("NWS-urn:oid:A");
    }
}
