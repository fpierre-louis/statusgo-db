package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.constant.PostKind;
import io.sitprep.sitprepapi.constant.SystemAccounts;
import io.sitprep.sitprepapi.domain.Post;
import io.sitprep.sitprepapi.domain.PostComment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2 C6 — {@link AlertPostRepo#countThreadParticipants}: the post detail's
 * {@code alertDiscussion.participants} is ONE {@code COUNT(DISTINCT)} over what
 * any viewer may read, and it never counts someone the post would not name.
 *
 * <p>On REAL Postgres over the replayed Flyway schema ({@code task} carries a
 * jsonb column H2 cannot create — the same reason as
 * {@code AlertPostThreadQueryPostgresIT}). Runs only when
 * {@code ALERT_PG_IT=true}, against the throwaway local DB
 * {@code sitprep_alerts_it}; skipped on CI/Heroku. Each test rolls back.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it-pg")
@TestPropertySource(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/sitprep_alerts_it")
@EnabledIfEnvironmentVariable(named = "ALERT_PG_IT", matches = "true")
class AlertThreadParticipantsPostgresIT {

    private static final Instant T = Instant.parse("2026-10-13T12:00:00Z");
    private static final String SYS = SystemAccounts.SITPREP_EMAIL;
    private static final List<String> PERSONAL = Arrays.stream(PostKind.values())
            .map(PostKind::wire).filter(PostKind::isPersonalScope).toList();

    @Autowired private AlertPostRepo repo;
    @Autowired private TestEntityManager em;

    private Post post(String kind, String groupId, String requester, Long parentId, boolean hidden) {
        Post p = new Post();
        p.setKind(kind);
        p.setGroupId(groupId);
        p.setRequesterEmail(requester);
        p.setParentPostId(parentId);
        p.setAuthorHidden(hidden);
        p.setTitle("t");
        p.setDescription("d");
        p.setStatus(Post.PostStatus.OPEN);
        p.setPriority(Post.PostPriority.MEDIUM);
        p.setCreatedAt(T);
        p.setUpdatedAt(T);
        return em.persistAndFlush(p);
    }

    private void reply(Post on, String author) {
        PostComment c = new PostComment();
        c.setPostId(on.getId());
        c.setAuthor(author);
        c.setContent("c");
        c.setTimestamp(T);
        c.setUpdatedAt(T);
        em.persistAndFlush(c);
    }

    @Test
    void countsDistinctPeopleAcrossTheThreadsPostsRepliesAndQuotes() {
        Post a = post("alert-update", null, SYS, null, false);
        Post b = post("alert-update", null, SYS, null, false); // a later revision, another geocell
        reply(a, "ann@example.com");
        reply(a, "ANN@example.com ");                // same person, other case/whitespace
        reply(b, "ann@example.com");                 // same person on another post of the thread
        reply(b, "bob@example.com");
        reply(a, SYS);                               // SitPrep is never a participant
        Post quote = post("post", null, "cara@example.com", a.getId(), false);
        reply(quote, "dan@example.com");             // a reply on a neighbour's quote counts
        reply(quote, "bob@example.com");             // … once
        Post elsewhere = post("alert-update", null, SYS, null, false);
        reply(elsewhere, "eve@example.com");         // another alert's thread

        long n = repo.countThreadParticipants(List.of(a.getId(), b.getId()), SYS, PERSONAL);

        assertThat(n).isEqualTo(4); // ann, bob, cara, dan
    }

    @Test
    void addsNothingAStrangerCouldNotRead_orAHiddenAuthorTheyWouldNotSee() {
        Post a = post("alert-update", null, SYS, null, false);
        reply(a, "ann@example.com");

        // Shared into a group: neither its author nor its replies count.
        Post inGroup = post("post", "grp-1", "gail@example.com", a.getId(), false);
        reply(inGroup, "gus@example.com");
        // A personal-scope kind is its requester's only.
        Post personal = post(PostKind.TASK.wire(), null, "tia@example.com", a.getId(), false);
        reply(personal, "tom@example.com");
        // HR6: a hidden author is "a neighbor" — never counted from the post they hid…
        Post hidden = post("post", null, "hal@example.com", a.getId(), true);
        // … while the people who replied to it by name still are.
        reply(hidden, "ivy@example.com");

        long n = repo.countThreadParticipants(List.of(a.getId()), SYS, PERSONAL);

        assertThat(n).isEqualTo(2); // ann, ivy
    }

    @Test
    void aThreadNobodyDiscussedHasNoParticipants() {
        Post a = post("alert-update", null, SYS, null, false);

        assertThat(repo.countThreadParticipants(List.of(a.getId()), SYS, PERSONAL)).isZero();
    }
}
