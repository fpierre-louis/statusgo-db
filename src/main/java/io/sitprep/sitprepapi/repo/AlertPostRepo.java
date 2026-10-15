package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlertPostRepo extends JpaRepository<AlertPost, Long> {

    /**
     * Dedup lookup. Used by {@code AlertDispatchService} before creating
     * a new AlertPost — if a non-resolved row already exists for this
     * (alertId, geocellId), skip. The unique index enforces this at the
     * DB level too; this method makes the application-side flow cleaner.
     */
    Optional<AlertPost> findByAlertIdAndGeocellId(String alertId, String geocellId);

    /**
     * Has this alert been dispatched under ANY geocell, resolved or not? The
     * push-once-per-alert guard: a USGS epicenter revision can move a quake's
     * representative coordinate into a different zip bucket, which passes the
     * (alertId, geocellId) dedupe and would otherwise push the same quake twice.
     */
    boolean existsByAlertId(String alertId);

    /** Batched fold of alert areas onto a page of posts (V89). */
    List<AlertPost> findByPostIdIn(Collection<Long> postIds);

    /**
     * Live (unresolved) posts for any of these alerts, across every geocell.
     * The community feed's alert pin uses it to find the post for an alert
     * that COVERS the viewer, wherever that post's own point happens to sit.
     */
    @Query("""
           SELECT ap FROM AlertPost ap
            WHERE ap.alertId IN :alertIds
              AND ap.resolvedAt IS NULL
              AND ap.postId IS NOT NULL
           """)
    List<AlertPost> findActiveByAlertIdIn(@Param("alertIds") Collection<String> alertIds);

    /**
     * Active (unresolved) AlertPosts for one alertId. Resolve tick
     * iterates this when the upstream alert ends, marking each row's
     * {@code resolvedAt} + cascading visual demotion to the parent
     * {@link io.sitprep.sitprepapi.domain.GroupPost} via GroupPostRepo.
     */
    @Query("""
           SELECT ap FROM AlertPost ap
            WHERE ap.alertId = :alertId
              AND ap.resolvedAt IS NULL
           """)
    List<AlertPost> findActiveByAlertId(@Param("alertId") String alertId);

    /**
     * Distinct alert IDs the dispatcher has already posted for, for
     * any geocell. Used by the dispatch tick as the first-pass filter
     * — anything in this set is either being resolved or has at least
     * one geocell covered. Geocell-level dedup happens via
     * {@link #findByAlertIdAndGeocellId}.
     */
    @Query("SELECT DISTINCT ap.alertId FROM AlertPost ap WHERE ap.resolvedAt IS NULL")
    List<String> findActiveAlertIds();

    /** One (alertId, postId) pair from {@link #findReadableThreadPosts}. */
    interface ThreadPostRef {
        String getAlertId();
        Long getPostId();
    }

    /**
     * The community discussion posts for a batch of alerts — the feed card's
     * {@code communityThreads} (S2 C2). ONE query for a whole feed response,
     * however many cards it carries.
     *
     * <p><b>Resolved rows are included on purpose.</b> {@code resolvedAt} marks
     * the dispatcher's post as no longer pinned, which happens the moment NWS
     * replaces the message it was created from — but the post, and the
     * neighbours' replies on it, are still the conversation about this hazard.
     * Live-ness is the card's job ({@code lifecycleInfo}), not this list's.</p>
     *
     * <p><b>Readable means what {@code PostReadAuthorizer} already allows any
     * viewer:</b> a groupless {@code alert-update} authored by the SitPrep
     * system account. A group-scoped row, a row of any other kind, or one
     * merged into another post is never listed, so the feed cannot point a
     * stranger at something the post endpoint would refuse.</p>
     *
     * @param alertIds    legacy dispatch keys ({@code SOURCE-id}) — what
     *                    {@code alert_post.alert_id} stores
     * @param systemEmail {@code SystemAccounts.SITPREP_EMAIL}
     */
    @Query("""
           SELECT ap.alertId AS alertId, ap.postId AS postId
             FROM AlertPost ap, Post p
            WHERE ap.alertId IN :alertIds
              AND p.id = ap.postId
              AND p.groupId IS NULL
              AND p.kind = 'alert-update'
              AND LOWER(p.requesterEmail) = LOWER(:systemEmail)
              AND p.mergedIntoPostId IS NULL
           """)
    List<ThreadPostRef> findReadableThreadPosts(@Param("alertIds") Collection<String> alertIds,
                                                @Param("systemEmail") String systemEmail);

    /**
     * How many distinct people take part in an alert's discussion — the post
     * detail's {@code alertDiscussion.participants} (S2 C6). ONE query for the
     * whole thread, however many posts and replies it spans.
     *
     * <p>{@code postIds} are the thread's readable dispatched posts
     * ({@link #findReadableThreadPosts}). The discussion is the replies on
     * them, plus the posts neighbours wrote quoting them
     * ({@code parent_task_id}) and the replies on those. Counted
     * case-insensitively by email; SitPrep's own account is not a
     * participant.</p>
     *
     * <p><b>Only what any viewer may read</b> ({@code PostReadAuthorizer}'s
     * groupless arm): a quote post shared into a group, or one of the
     * personal-scope kinds, adds nothing — not its author, not its replies — so
     * the number cannot reveal activity inside a group to a stranger.</p>
     *
     * <p><b>A hidden author (HR6) is never counted from the post they hid.</b>
     * Counting them would need their identity, and counting them DISTINCTLY
     * against named replies would tell a reader whether the "neighbor" is one
     * of the named people. Their named replies still count, as anyone's do.</p>
     *
     * <p>Native SQL because JPQL cannot count over a {@code UNION}. Proved on
     * Postgres in {@code AlertThreadParticipantsPostgresIT} ({@code task}'s
     * jsonb column keeps it off H2).</p>
     *
     * @param postIds       readable dispatched posts of the thread (non-empty)
     * @param systemEmail   {@code SystemAccounts.SITPREP_EMAIL}
     * @param personalKinds the kinds {@code PostKind.isPersonalScope} names (non-empty)
     */
    @Query(value = """
           SELECT COUNT(DISTINCT LOWER(TRIM(x.author)))
             FROM (
                   SELECT c.author AS author
                     FROM task_comment c
                    WHERE c.task_id IN (:postIds)
                   UNION ALL
                   SELECT q.requester_email AS author
                     FROM task q
                    WHERE q.parent_task_id IN (:postIds)
                      AND (q.group_id IS NULL OR TRIM(q.group_id) = '')
                      AND q.kind NOT IN (:personalKinds)
                      AND q.author_hidden = FALSE
                   UNION ALL
                   SELECT qc.author AS author
                     FROM task_comment qc
                     JOIN task q2 ON q2.id = qc.task_id
                    WHERE q2.parent_task_id IN (:postIds)
                      AND (q2.group_id IS NULL OR TRIM(q2.group_id) = '')
                      AND q2.kind NOT IN (:personalKinds)
                  ) x
            WHERE x.author IS NOT NULL
              AND TRIM(x.author) <> ''
              AND LOWER(TRIM(x.author)) <> LOWER(:systemEmail)
           """, nativeQuery = true)
    long countThreadParticipants(@Param("postIds") Collection<Long> postIds,
                                 @Param("systemEmail") String systemEmail,
                                 @Param("personalKinds") Collection<String> personalKinds);
}
