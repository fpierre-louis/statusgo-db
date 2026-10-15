package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.NotificationLog;
import io.sitprep.sitprepapi.notifications.NotificationEventType;
import io.sitprep.sitprepapi.notifications.NotificationInboxFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The S2 C5 inbox reads, as native SQL (see {@link NotificationLogInboxQuery}).
 *
 * <p><b>Native, not JPQL — on purpose.</b> The needs_response predicate is a
 * long OR of IN-lists; as HQL it sent Hibernate's ANTLR parser into a
 * full-context prediction that allocated hundreds of MB per parse. On the
 * 256 MB prod heap the first {@code /counts} call took the dyno down (OOM,
 * v699, 2026-10-15). Native SQL is never parsed by HQL.</p>
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

    private static final String OWNER = "LOWER(n.recipient_email) = LOWER(:email) AND n.archived_at IS NULL";
    private static final String ALERT = "n.type = '" + NotificationInboxFilter.ALERT_TYPE + "'";
    private static final String TS = "n.\"timestamp\"";
    private static final String ORDER = " ORDER BY " + TS + " DESC, n.id DESC";

    @Override
    public List<NotificationLog> findInboxPage(String email, NotificationInboxFilter filter, Instant since,
                                               Instant before, Long beforeId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("email", email);
        StringBuilder q = new StringBuilder("SELECT n.* FROM notification_log n WHERE ").append(OWNER);
        if (since != null) {
            q.append(" AND ").append(TS).append(" > :since");
            params.put("since", since);
        }
        if (before != null) {
            params.put("before", before);
            if (beforeId != null) {
                q.append(" AND (").append(TS).append(" < :before OR (").append(TS).append(" = :before AND n.id < :beforeId))");
                params.put("beforeId", beforeId);
            } else {
                q.append(" AND ").append(TS).append(" < :before");
            }
        }
        switch (filter == null ? NotificationInboxFilter.ALL : filter) {
            case UNREAD -> q.append(" AND n.read_at IS NULL");
            case ALERTS -> q.append(" AND ").append(ALERT);
            case NEEDS_RESPONSE -> q.append(" AND ").append(needsResponseJpql(params));
            case ALL -> { }
        }
        q.append(ORDER).append(" LIMIT ").append(Math.max(1, limit));
        Query query = em.createNativeQuery(q.toString(), NotificationLog.class);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<NotificationLog> rows = query.getResultList();
        return rows;
    }

    @Override
    public InboxCounts countInbox(String email) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("email", email);
        String needs = needsResponseJpql(params);
        String q = "SELECT COUNT(*),"
                + " SUM(CASE WHEN n.read_at IS NULL THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + ALERT + " THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + ALERT + " AND n.read_at IS NULL THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN " + needs + " THEN 1 ELSE 0 END)"
                + " FROM notification_log n WHERE " + OWNER;
        Query query = em.createNativeQuery(q);
        params.forEach(query::setParameter);
        Object[] r = (Object[]) query.getSingleResult();
        return new InboxCounts(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]));
    }

    @Override
    public List<NotificationLog> findThreadRows(String email, String threadKey, int limit) {
        if (threadKey == null || threadKey.isBlank()) return List.of();
        @SuppressWarnings("unchecked")
        List<NotificationLog> rows = em.createNativeQuery("SELECT n.* FROM notification_log n WHERE " + OWNER
                        + " AND n.alert_thread_key = :threadKey" + ORDER + " LIMIT " + Math.max(1, limit),
                        NotificationLog.class)
                .setParameter("email", email)
                .setParameter("threadKey", threadKey)
                .getResultList();
        return rows;
    }

    @Override
    public Optional<NotificationLog> findLatestDeliveredInThread(String threadKey, Collection<String> alertKeys,
                                                                 Collection<String> legacyRefs, Instant since) {
        Map<String, Object> params = new LinkedHashMap<>();
        String match = threadMatch(threadKey, alertKeys, legacyRefs, params);
        if (match == null) return Optional.empty();
        params.put("since", since == null ? Instant.EPOCH : since);
        Query query = em.createNativeQuery("SELECT n.* FROM notification_log n WHERE " + ALERT
                + " AND " + TS + " >= :since AND " + match + ORDER + " LIMIT 1", NotificationLog.class);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<NotificationLog> rows = query.getResultList();
        return rows.stream().findFirst();
    }

    @Override
    public List<String> findThreadRecipients(String threadKey, Collection<String> alertKeys,
                                             Collection<String> legacyRefs, Instant since) {
        Map<String, Object> params = new LinkedHashMap<>();
        String match = threadMatch(threadKey, alertKeys, legacyRefs, params);
        if (match == null) return List.of();
        params.put("since", since == null ? Instant.EPOCH : since);
        Query query = em.createNativeQuery("SELECT DISTINCT n.recipient_email FROM notification_log n WHERE "
                + ALERT + " AND " + TS + " >= :since AND " + match);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<String> rows = query.getResultList();
        return rows;
    }

    // ------------------------------------------------------------------

    /** A thread's rows: its key, any of its messages' keys, or a pre-V110 row by referenceId. */
    private static String threadMatch(String threadKey, Collection<String> alertKeys,
                                      Collection<String> legacyRefs, Map<String, Object> params) {
        List<String> or = new ArrayList<>();
        if (threadKey != null && !threadKey.isBlank()) {
            or.add("n.alert_thread_key = :threadKey");
            params.put("threadKey", threadKey);
        }
        if (alertKeys != null && !alertKeys.isEmpty()) {
            or.add("n.alert_key IN (:alertKeys)");
            params.put("alertKeys", alertKeys);
        }
        if (legacyRefs != null && !legacyRefs.isEmpty()) {
            or.add("(n.alert_key IS NULL AND n.reference_id IN (:legacyRefs))");
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
        String notAgency = "(n.reference_id IS NULL OR n.reference_id NOT LIKE :nrAgency)";
        String hasRef = "(n.reference_id IS NOT NULL AND TRIM(n.reference_id) <> '')";

        List<String> or = new ArrayList<>();
        String always = eventIs("Always", NotificationInboxFilter.ALWAYS, params);
        if (always != null) or.add(always);
        String withRef = eventIs("Ref", NotificationInboxFilter.WITH_REFERENCE, params);
        if (withRef != null) or.add("(" + withRef + " AND " + hasRef + ")");
        String pending = eventIs("Pending", NotificationInboxFilter.PENDING, params);
        if (pending != null) {
            params.put("nrInvite", "%" + NotificationInboxFilter.INVITE_REQUEST_LIKE);
            or.add("(" + pending + " AND (n.target_url LIKE :nrInvite OR (" + hasRef
                    + " AND n.additional_data LIKE '%@%' AND TRIM(n.additional_data) NOT LIKE '{%')))");
        }
        if (or.isEmpty()) return "1 = 0";
        return "(n.read_at IS NULL AND " + notAgency + " AND (" + String.join(" OR ", or) + "))";
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
            or.add("LOWER(TRIM(n.type)) IN (:nrTypes" + tag + ")");
            params.put("nrTypes" + tag, types);
        }
        if (!cats.isEmpty()) {
            or.add("((n.type IS NULL OR LOWER(TRIM(n.type)) NOT IN (:nrRegistered)) AND UPPER(TRIM(n.category)) IN (:nrCats"
                    + tag + "))");
            params.put("nrCats" + tag, cats);
        }
        return or.isEmpty() ? null : "(" + String.join(" OR ", or) + ")";
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
