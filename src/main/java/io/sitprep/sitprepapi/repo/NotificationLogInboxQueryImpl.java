package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.notifications.NotificationEventType;
import io.sitprep.sitprepapi.notifications.NotificationInboxFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The S2 C5 inbox reads, as JPQL (see {@link NotificationLogInboxQuery}).
 *
 * <p>The needs_response predicate is built once, by {@link #needsResponseJpql},
 * and used by both the page and the count, so the badge and the list can't
 * disagree. It is the SQL form of {@link NotificationInboxFilter#needsResponse}
 * — {@link NotificationEventType#resolve}'s precedence spelled out: the agency
 * prefix first, then a registered type, then the category alone.</p>
 */
public class NotificationLogInboxQueryImpl implements NotificationLogInboxQuery {

    @PersistenceContext
    private EntityManager em;

    private static final String OWNER = "LOWER(n.recipientEmail) = LOWER(:email) AND n.archivedAt IS NULL";
    private static final String ALERT = "n.type = '" + NotificationInboxFilter.ALERT_TYPE + "'";

    @Override
    public List<NotificationLog> findInboxPage(String email, NotificationInboxFilter filter, Instant since,
                                               Instant before, Long beforeId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("email", email);
        StringBuilder q = new StringBuilder("SELECT n FROM NotificationLog n WHERE ").append(OWNER);
        if (since != null) {
            q.append(" AND n.timestamp > :since");
            params.put("since", since);
        }
        if (before != null) {
            params.put("before", before);
            if (beforeId != null) {
                q.append(" AND (n.timestamp < :before OR (n.timestamp = :before AND n.id < :beforeId))");
                params.put("beforeId", beforeId);
            } else {
                q.append(" AND n.timestamp < :before");
            }
        }
        switch (filter == null ? NotificationInboxFilter.ALL : filter) {
            case UNREAD -> q.append(" AND n.readAt IS NULL");
            case ALERTS -> q.append(" AND ").append(ALERT);
            case NEEDS_RESPONSE -> q.append(" AND ").append(needsResponseJpql(params));
            case ALL -> { }
        }
        q.append(" ORDER BY n.timestamp DESC, n.id DESC");
        TypedQuery<NotificationLog> query = em.createQuery(q.toString(), NotificationLog.class);
        params.forEach(query::setParameter);
        return query.setMaxResults(Math.max(1, limit)).getResultList();
    }

    @Override
    public InboxCounts countInbox(String email) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("email", email);
        String needs = needsResponseJpql(params);
        String q = "SELECT COUNT(n),"
                + " SUM(CASE WHEN n.readAt IS NULL THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + ALERT + " THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + ALERT + " AND n.readAt IS NULL THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + needs + " THEN 1 ELSE 0 END)"
                + " FROM NotificationLog n WHERE " + OWNER;
        TypedQuery<Object[]> query = em.createQuery(q, Object[].class);
        params.forEach(query::setParameter);
        Object[] r = query.getSingleResult();
        return new InboxCounts(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]));
    }

    @Override
    public List<NotificationLog> findThreadRows(String email, String threadKey, int limit) {
        if (threadKey == null || threadKey.isBlank()) return List.of();
        return em.createQuery("SELECT n FROM NotificationLog n WHERE " + OWNER
                        + " AND n.alertThreadKey = :threadKey ORDER BY n.timestamp DESC, n.id DESC",
                        NotificationLog.class)
                .setParameter("email", email)
                .setParameter("threadKey", threadKey)
                .setMaxResults(Math.max(1, limit))
                .getResultList();
    }

    @Override
    public Optional<NotificationLog> findLatestDeliveredInThread(String threadKey, Collection<String> alertKeys,
                                                                 Collection<String> legacyRefs, Instant since) {
        Map<String, Object> params = new LinkedHashMap<>();
        String match = threadMatch(threadKey, alertKeys, legacyRefs, params);
        if (match == null) return Optional.empty();
        params.put("since", since == null ? Instant.EPOCH : since);
        TypedQuery<NotificationLog> query = em.createQuery("SELECT n FROM NotificationLog n WHERE " + ALERT
                + " AND n.timestamp >= :since AND " + match
                + " ORDER BY n.timestamp DESC, n.id DESC", NotificationLog.class);
        params.forEach(query::setParameter);
        return query.setMaxResults(1).getResultList().stream().findFirst();
    }

    @Override
    public List<String> findThreadRecipients(String threadKey, Collection<String> alertKeys,
                                             Collection<String> legacyRefs, Instant since) {
        Map<String, Object> params = new LinkedHashMap<>();
        String match = threadMatch(threadKey, alertKeys, legacyRefs, params);
        if (match == null) return List.of();
        params.put("since", since == null ? Instant.EPOCH : since);
        TypedQuery<String> query = em.createQuery("SELECT DISTINCT n.recipientEmail FROM NotificationLog n WHERE "
                + ALERT + " AND n.timestamp >= :since AND " + match, String.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    // ------------------------------------------------------------------

    /** A thread's rows: its key, any of its messages' keys, or a pre-V110 row by referenceId. */
    private static String threadMatch(String threadKey, Collection<String> alertKeys,
                                      Collection<String> legacyRefs, Map<String, Object> params) {
        List<String> or = new ArrayList<>();
        if (threadKey != null && !threadKey.isBlank()) {
            or.add("n.alertThreadKey = :threadKey");
            params.put("threadKey", threadKey);
        }
        if (alertKeys != null && !alertKeys.isEmpty()) {
            or.add("n.alertKey IN :alertKeys");
            params.put("alertKeys", alertKeys);
        }
        if (legacyRefs != null && !legacyRefs.isEmpty()) {
            or.add("(n.alertKey IS NULL AND n.referenceId IN :legacyRefs)");
            params.put("legacyRefs", legacyRefs);
        }
        return or.isEmpty() ? null : "(" + String.join(" OR ", or) + ")";
    }

    /**
     * needs_response, for one row {@code n}. Adds its parameters to
     * {@code params}. An empty event set contributes nothing (JPQL rejects
     * {@code IN ()}).
     */
    static String needsResponseJpql(Map<String, Object> params) {
        params.put("nrRegistered", NotificationInboxFilter.registeredTypes());
        params.put("nrAgency", NotificationEventType.AGENCY_ALERT_REF_PREFIX + "%");
        String notAgency = "(n.referenceId IS NULL OR n.referenceId NOT LIKE :nrAgency)";
        String hasRef = "(n.referenceId IS NOT NULL AND TRIM(n.referenceId) <> '')";

        List<String> or = new ArrayList<>();
        String always = eventIs("Always", NotificationInboxFilter.ALWAYS, params);
        if (always != null) or.add(always);
        String withRef = eventIs("Ref", NotificationInboxFilter.WITH_REFERENCE, params);
        if (withRef != null) or.add("(" + withRef + " AND " + hasRef + ")");
        String pending = eventIs("Pending", NotificationInboxFilter.PENDING, params);
        if (pending != null) {
            params.put("nrInvite", "%" + NotificationInboxFilter.INVITE_REQUEST_LIKE);
            or.add("(" + pending + " AND (n.targetUrl LIKE :nrInvite OR (" + hasRef
                    + " AND n.additionalData LIKE '%@%' AND TRIM(n.additionalData) NOT LIKE '{%')))");
        }
        if (or.isEmpty()) return "1 = 0";
        return "(n.readAt IS NULL AND " + notAgency + " AND (" + String.join(" OR ", or) + "))";
    }

    /**
     * The row resolves to one of {@code events}: a registered type that is
     * one of theirs, or no registered type and one of their categories.
     */
    private static String eventIs(String tag, Set<NotificationEventType> events, Map<String, Object> params) {
        Set<String> types = NotificationInboxFilter.typesOf(events);
        Set<String> cats = NotificationInboxFilter.categoriesOf(events);
        List<String> or = new ArrayList<>();
        if (!types.isEmpty()) {
            or.add("LOWER(TRIM(n.type)) IN :nrTypes" + tag);
            params.put("nrTypes" + tag, types);
        }
        if (!cats.isEmpty()) {
            or.add("((n.type IS NULL OR LOWER(TRIM(n.type)) NOT IN :nrRegistered) AND UPPER(TRIM(n.category)) IN :nrCats"
                    + tag + ")");
            params.put("nrCats" + tag, cats);
        }
        return or.isEmpty() ? null : "(" + String.join(" OR ", or) + ")";
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
