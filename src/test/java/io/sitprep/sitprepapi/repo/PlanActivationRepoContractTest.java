package io.sitprep.sitprepapi.repo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B8 — "still running" is two conditions, and a comment asking the next person
 * to remember the second one is not a constraint.
 *
 * <h4>What this asserts</h4>
 * Any {@code @Query} in {@link PlanActivationRepo} that asks whether a row is
 * STILL RUNNING ({@code expiresAt >}) must also ask whether anyone ENDED it
 * ({@code endedAt IS NULL}). A query that checks only the timer keeps an ended
 * activation alive on exactly one surface — which is the disagreement between
 * surfaces the whole plan-activation epic exists to close. It shipped once
 * already: Home read EVACUATING for three days off a row nobody could close.
 *
 * <h4>Why the source and not the schema</h4>
 * There is nothing in JPA to hang this on. The predicate lives in JPQL string
 * literals, so the strings are what gets read. The same reasoning as the
 * frontend's {@code dangerInk.test.js}: a test that measures the thing the
 * defect would live in, rather than a thing that merely correlates with it.
 *
 * <h4>The complement queries are exempt, on purpose</h4>
 * {@code expiresAt <=} / {@code <} ask the opposite question — "is this over by
 * the timer" — and the expiry sweep's own {@code endedAt IS NULL} is there for
 * a different reason (do not announce an ending twice), documented at the
 * query. Exempting them by DIRECTION rather than by name means a new complement
 * query needs no edit here, and a new liveness query cannot escape by being
 * named something this test never heard of.
 */
class PlanActivationRepoContractTest {

    private static final Path SOURCE =
            Path.of("src/main/java/io/sitprep/sitprepapi/repo/PlanActivationRepo.java");

    /**
     * Comments are stripped BEFORE scanning, and that is load-bearing.
     *
     * <p>This file's own class comment says {@code endedAt IS NULL} in prose,
     * and the repository's does too. Scanning the raw text would find those and
     * pass — the exact way the frontend's first source-scanning test passed by
     * matching its own header. The strings are the subject; everything a human
     * wrote about them is noise.</p>
     */
    private static String stripComments(String java) {
        String noBlock = java.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    /** Every {@code @Query("...")} literal, with its Java string concatenation joined up. */
    private static List<String> queryLiterals(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("@Query\\s*\\((.*?)\\)\\s*(?:[A-Za-z<])", Pattern.DOTALL).matcher(code);
        while (m.find()) {
            StringBuilder sb = new StringBuilder();
            Matcher parts = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
            while (parts.find()) sb.append(parts.group(1));
            out.add(sb.toString());
        }
        return out;
    }

    @Test
    void everyLivenessQueryAlsoChecksEndedAt() throws IOException {
        String code = stripComments(Files.readString(SOURCE));
        List<String> queries = queryLiterals(code);

        // If the parser stops finding queries the test must fail LOUDLY rather
        // than pass over an empty list — a scanner that finds nothing agrees
        // with every possible source, which is the failure mode this whole
        // class of test is prone to.
        assertFalse(queries.isEmpty(), "parsed no @Query literals out of " + SOURCE);

        List<String> liveness = queries.stream()
                .filter(q -> q.matches("(?s).*\\bexpiresAt\\s*>.*"))
                .toList();
        assertFalse(liveness.isEmpty(),
                "no query asks `expiresAt >` any more — if liveness moved, move this test with it");

        for (String q : liveness) {
            assertTrue(q.matches("(?si).*\\bendedAt\\s+IS\\s+NULL\\b.*"),
                    "a query asks whether an activation is still running but never asks "
                    + "whether anyone ended it. `expiresAt` is a 72-hour timer; `endedAt` is a "
                    + "household saying it is over. Add `AND a.endedAt IS NULL`.\n  " + q);
        }
    }

    /**
     * The deleted method, kept as a named absence.
     *
     * <p>{@code findFirstActiveByOwnerEmail} was the second copy of the live
     * predicate. It also carried a latent 500: with {@code @Query} the
     * {@code findFirst} prefix applies no limit — Spring Data derives a limit
     * from a method name only when it derives the whole query — so declared
     * {@code Optional} it threw {@code IncorrectResultSizeDataAccessException}
     * for any owner with two live rows. {@code createActivation} produces those
     * freely; nothing called the method, so it never fired.</p>
     *
     * <p>Re-adding it would restore both problems at once, so the name is
     * banned rather than merely absent.</p>
     */
    @Test
    void theSecondCopyOfTheLivePredicateStaysDeleted() throws IOException {
        String code = stripComments(Files.readString(SOURCE));
        assertFalse(code.contains("findFirstActiveByOwnerEmail"),
                "findFirstActiveByOwnerEmail is back. It duplicated the live predicate AND "
                + "threw IncorrectResultSizeDataAccessException for an owner with two live "
                + "activations. Take the first element of findActiveByOwnerEmail instead.");
    }
}
