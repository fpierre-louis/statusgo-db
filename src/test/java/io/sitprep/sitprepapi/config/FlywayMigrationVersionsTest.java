package io.sitprep.sitprepapi.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two migrations must never share a version.
 *
 * <p><b>This test exists because the absence of it took production down.</b> On
 * 2026-09-01 two lanes each picked V68 — one had already been applied and
 * recorded in {@code flyway_schema_history}, the other had not — and Flyway
 * refuses to start at all when it finds a duplicate:</p>
 *
 * <pre>
 *   FlywayException: Found more than one migration with version 68
 *     -> V68__plan_activation_end.sql
 *     -> V68__group_cover_image.sql
 * </pre>
 *
 * <p>It refuses during BEAN CREATION, so the failure is not a bad migration —
 * it is the whole application failing to boot. api.sitprep.app returned 503 for
 * four minutes.</p>
 *
 * <h4>Why nothing caught it</h4>
 *
 * <p>{@code mvn clean package} passed on 680 tests minutes before the push.
 * <b>Flyway validates versions at BOOT, not at build</b>, so a duplicate is
 * invisible to every gate that does not start a context — which is every fast
 * gate we have. The filenames are also perfectly ordinary on their own; the
 * defect only exists in the relationship between two files that two people
 * wrote separately, which is exactly the class a human review of either diff
 * would pass.</p>
 *
 * <p>So this reads the DIRECTORY rather than the classpath, needs no Spring
 * context, and runs in milliseconds inside the gate that already exists.
 * Heroku compiles tests, so a duplicate now fails the deploy BUILD instead of
 * the dyno.</p>
 */
class FlywayMigrationVersionsTest {

    /** Flyway's own naming contract: V, a version, two underscores, a description. */
    private static final Pattern MIGRATION =
            Pattern.compile("^V(\\d+(?:[._]\\d+)*)__([A-Za-z0-9_]+)\\.sql$");

    private static final Path DIR = Paths.get("src/main/resources/db/migration");

    private static List<String> filenames() throws IOException {
        assertTrue(Files.isDirectory(DIR), "migration directory is missing: " + DIR.toAbsolutePath());
        try (Stream<Path> files = Files.list(DIR)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void noTwoMigrationsShareAVersion() throws IOException {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (String name : filenames()) {
            Matcher m = MIGRATION.matcher(name);
            if (!m.matches()) continue;   // shape is the next test's problem
            byVersion.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(name);
        }

        List<String> clashes = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " claimed by " + String.join(" and ", e.getValue()))
                .toList();

        assertTrue(clashes.isEmpty(),
                "Two migrations share a version. Flyway will refuse to start and the app will not "
                + "boot — this is a production outage, not a failed migration.\n"
                + "  " + String.join("\n  ", clashes) + "\n"
                + "FIX: renumber the one that has NOT been applied yet. If one is already recorded "
                + "in flyway_schema_history it must keep its number, because renaming an applied "
                + "migration leaves a version in the database with no file to match it — which "
                + "fails validation just as hard.");
    }

    @Test
    void everyMigrationFollowsFlywayNaming() throws IOException {
        List<String> bad = filenames().stream()
                .filter(n -> !MIGRATION.matcher(n).matches())
                .toList();

        assertTrue(bad.isEmpty(),
                "Not a Flyway migration name (expected V<version>__<description>.sql): " + bad);
    }

    @Test
    void theNextFreeVersionIsObvious() throws IOException {
        // Not an assertion about correctness — a printout, so the number is in
        // the build log of whoever is about to pick one. The outage happened
        // because two people each reasoned about "the next free number" from a
        // tree that did not yet contain the other's file.
        int max = 0;
        for (String name : filenames()) {
            Matcher m = MIGRATION.matcher(name);
            if (m.matches() && m.group(1).matches("\\d+")) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        assertTrue(max > 0, "no numbered migrations found at all");
        System.out.println("[flyway] highest migration on this branch: V" + max
                + " — the next free version is V" + (max + 1)
                + ", but PULL FIRST: another lane may have taken it.");
    }
}
