package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.service.NotificationService;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FD-06 (G1): when an admin removes a post, the inbox rows that quoted it keep
 * existing but stop showing its text. Rows about other posts, and other types
 * that happen to share the reference id, are untouched.
 */
/** Runs like {@code NotificationLogDeferredQueryPostgresIT}: only with NOTIFY_PG_IT=true on a throwaway Postgres (jsonb column, no H2). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@EnabledIfEnvironmentVariable(named = "NOTIFY_PG_IT", matches = "true")
class NotificationLogRemovedPostScrubPostgresIT {

    @Autowired private NotificationLogRepo repo;
    @Autowired private jakarta.persistence.EntityManager em;

    private NotificationLog row(String who, String type, String ref, String body) {
        NotificationLog n = new NotificationLog(who, type, null, "Ridge Watch", body, ref, "/x", null,
                Instant.now(), true, null);
        em.persist(n);
        return n;
    }

    @Test
    void postAndMentionRowsForThatPostLoseTheirBody_everythingElseKeepsIt() {
        NotificationLog a = row("a@x.com", "post_notification", "7", "Ann posted in Ridge Watch: 'rude words'");
        NotificationLog b = row("b@x.com", "post_mention", "7", "Ann mentioned you: 'rude words'");
        NotificationLog otherPost = row("a@x.com", "post_notification", "8", "keep me");
        NotificationLog otherType = row("a@x.com", "hazard_alert", "7", "keep me too");
        em.flush();

        int n = repo.replaceBodyForReference("7", List.of("post_notification", "post_mention"),
                NotificationService.POST_REMOVED_BODY);
        em.clear();

        assertThat(n).isEqualTo(2);
        assertThat(repo.findById(a.getId()).orElseThrow().getBody()).isEqualTo("Post removed by an admin");
        assertThat(repo.findById(b.getId()).orElseThrow().getBody()).isEqualTo("Post removed by an admin");
        assertThat(repo.findById(a.getId()).orElseThrow().getTitle()).isEqualTo("Ridge Watch"); // row kept
        assertThat(repo.findById(otherPost.getId()).orElseThrow().getBody()).isEqualTo("keep me");
        assertThat(repo.findById(otherType.getId()).orElseThrow().getBody()).isEqualTo("keep me too");
    }
}
