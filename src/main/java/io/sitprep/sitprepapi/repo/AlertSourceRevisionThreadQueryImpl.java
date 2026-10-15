package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.AlertSourceRevision;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * See {@link AlertSourceRevisionThreadQuery}.
 *
 * <p><b>Whole-token matching.</b> Both columns are space-separated lists, so a
 * key is matched as {@code ' ' || column || ' ' LIKE '% key %'}: bounded by
 * spaces on both sides, it can never match inside a longer token. LIKE's own
 * wildcards ({@code %}, {@code _}) are literal characters in an upstream id or
 * an event key, so they are escaped — with {@code !}, not a backslash, whose
 * meaning in a string literal differs between Postgres settings and H2. A key
 * holding whitespace cannot be one token and is dropped.</p>
 *
 * <p><b>Chunked, not truncated.</b> One statement holds at most
 * {@link #MAX_TERMS_PER_STATEMENT} terms; a wider ask runs as several and the
 * rows are merged by {@code revisionRef}. A thread lookup or an inbox page asks
 * for a handful, so in practice this is one statement.</p>
 *
 * <p>The LIKE terms cannot use {@code idx_asr_dedupe}; Postgres scans the
 * table (30 days of revisions). That is acceptable for a per-request lookup at
 * today's volume; a trigram or array index is the refinement if it stops being.</p>
 */
public class AlertSourceRevisionThreadQueryImpl implements AlertSourceRevisionThreadQuery {

    /** Terms (LIKE patterns plus IN values) per statement. */
    static final int MAX_TERMS_PER_STATEMENT = 100;

    private static final char ESCAPE = '!';

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<AlertSourceRevision> findThreadCandidates(Collection<String> vtecEventKeys,
                                                          Collection<String> referencedIds,
                                                          Collection<String> dedupeKeys) {
        List<Term> terms = new ArrayList<>();
        for (String k : tokens(vtecEventKeys)) terms.add(new Term(Kind.VTEC, k));
        for (String k : tokens(referencedIds)) terms.add(new Term(Kind.REFERENCE, k));
        for (String k : exact(dedupeKeys)) terms.add(new Term(Kind.DEDUPE, k));
        if (terms.isEmpty()) return List.of();

        Map<String, AlertSourceRevision> out = new LinkedHashMap<>();
        for (int from = 0; from < terms.size(); from += MAX_TERMS_PER_STATEMENT) {
            List<Term> chunk = terms.subList(from, Math.min(terms.size(), from + MAX_TERMS_PER_STATEMENT));
            for (AlertSourceRevision r : run(chunk)) out.putIfAbsent(r.getRevisionRef(), r);
        }
        return List.copyOf(out.values());
    }

    private List<AlertSourceRevision> run(List<Term> chunk) {
        StringBuilder where = new StringBuilder();
        List<String> dedupe = new ArrayList<>();
        int p = 0;
        for (Term t : chunk) {
            if (t.kind() == Kind.DEDUPE) {
                dedupe.add(t.value());
                continue;
            }
            if (!where.isEmpty()) where.append(" or ");
            String column = t.kind() == Kind.VTEC ? "r.vtecEventKeys" : "r.referenceIds";
            where.append("concat(' ', ").append(column).append(", ' ') like :p").append(p++)
                    .append(" escape '").append(ESCAPE).append("'");
        }
        if (!dedupe.isEmpty()) {
            if (!where.isEmpty()) where.append(" or ");
            where.append("r.dedupeKey in :dedupe");
        }

        TypedQuery<AlertSourceRevision> q = em.createQuery(
                "select r from AlertSourceRevision r where " + where, AlertSourceRevision.class);
        p = 0;
        for (Term t : chunk) {
            if (t.kind() == Kind.DEDUPE) continue;
            q.setParameter("p" + p++, "% " + escapeLike(t.value()) + " %");
        }
        if (!dedupe.isEmpty()) q.setParameter("dedupe", dedupe);
        return q.getResultList();
    }

    private enum Kind { VTEC, REFERENCE, DEDUPE }

    private record Term(Kind kind, String value) {}

    /** Trimmed, non-blank, single-token values, de-duplicated in order. */
    private static Set<String> tokens(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in == null) return out;
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty() || t.chars().anyMatch(Character::isWhitespace)) continue;
            out.add(t);
        }
        return out;
    }

    private static Set<String> exact(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        if (in == null) return out;
        for (String s : in) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    static String escapeLike(String s) {
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (char c : s.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') b.append(ESCAPE);
            b.append(c);
        }
        return b.toString();
    }
}
