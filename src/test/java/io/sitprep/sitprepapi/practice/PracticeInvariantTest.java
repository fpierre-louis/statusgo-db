package io.sitprep.sitprepapi.practice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE Practice invariant, enforced structurally: simulated Practice choices
 * can never mutate live emergency state — Active Situation, movement
 * directive, governing alert, welfare status, check-in, live location, or
 * household condition.
 *
 * <p>Rather than list every class that could do harm (a denylist goes stale
 * the day someone adds a service), the practice package may depend on an
 * <b>allowlist</b> of outside classes, each of which is read-only for
 * Practice's purposes. Adding to this list is a design decision: say in the
 * commit why the new dependency cannot write emergency state.</p>
 *
 * <p>Also enforced: the one shared repository Practice touches,
 * {@code GroupRepo}, is used for lookup only.</p>
 */
class PracticeInvariantTest {

    private static final Path PRACTICE = Path.of("src/main/java/io/sitprep/sitprepapi/practice");

    private static final Set<String> ALLOWED_OUTSIDE = Set.of(
            // reads: who is calling, may they see this household
            "io.sitprep.sitprepapi.util.AuthUtils",
            "io.sitprep.sitprepapi.service.HouseholdAccessService",
            "io.sitprep.sitprepapi.service.PlatformAccessService",
            "io.sitprep.sitprepapi.constant.PlatformPermission",
            // reads: is something live right now (suppression)
            "io.sitprep.sitprepapi.readiness.ActiveResponseResolver",
            "io.sitprep.sitprepapi.readiness.ActiveResponseResolver.ActiveResponse",
            // EXEC-H1: the alert nudge is a read-only value, like ActiveResponse.
            "io.sitprep.sitprepapi.readiness.ActiveResponseResolver.AlertHeadsUp",
            "io.sitprep.sitprepapi.service.RiskProfileService",
            "io.sitprep.sitprepapi.dto.RiskProfileDtos.RiskProfileDto",
            "io.sitprep.sitprepapi.service.ConcealmentSafetyService",
            "io.sitprep.sitprepapi.repo.UserInfoRepo",
            "io.sitprep.sitprepapi.repo.GroupRepo",
            "io.sitprep.sitprepapi.domain.Group",
            "io.sitprep.sitprepapi.domain.UserInfo",
            // a semantic action NAME (an enum), so the FE keeps one route map
            "io.sitprep.sitprepapi.readiness.ReadinessAction",
            // idempotent POSTs (annotation only)
            "io.sitprep.sitprepapi.web.Idempotent");

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?(io\\.sitprep\\.sitprepapi\\.[\\w.*]+);", Pattern.MULTILINE);
    private static final Pattern INLINE_FQN = Pattern.compile("(?<![\\w.\"])io\\.sitprep\\.sitprepapi\\.(?!practice\\b)[\\w.]+");
    private static final Pattern GROUP_REPO_CALL = Pattern.compile("groupRepo\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern USER_REPO_CALL = Pattern.compile("userInfoRepo\\s*\\.\\s*(\\w+)\\s*\\(");

    @Test
    void practiceDependsOnlyOnReadOnlyOutsideClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String src = Files.readString(file);
            Matcher m = IMPORT.matcher(src);
            while (m.find()) {
                String imported = m.group(2);
                if (imported.startsWith("io.sitprep.sitprepapi.practice.")) continue;
                if (imported.endsWith(".*")) {
                    violations.add(file.getFileName() + ": wildcard import " + imported);
                } else if (!ALLOWED_OUTSIDE.contains(imported)) {
                    violations.add(file.getFileName() + ": imports " + imported);
                }
            }
            String code = stripComments(src.replaceAll("(?m)^(package|import)\\s.*$", ""));
            Matcher fq = INLINE_FQN.matcher(code);
            while (fq.find()) {
                if (!ALLOWED_OUTSIDE.contains(fq.group())) {
                    violations.add(file.getFileName() + ": references " + fq.group());
                }
            }
        }
        assertThat(violations)
                .as("Practice may only use read-only outside classes (see ALLOWED_OUTSIDE)")
                .isEmpty();
    }

    @Test
    void sharedRepositoriesAreReadOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sources()) {
            String src = Files.readString(file);
            for (Pattern p : List.of(GROUP_REPO_CALL, USER_REPO_CALL)) {
                Matcher m = p.matcher(src);
                while (m.find()) {
                    if (!m.group(1).startsWith("find")) {
                        violations.add(file.getFileName() + ": " + m.group());
                    }
                }
            }
        }
        assertThat(violations).as("Practice reads households and users; it never writes them").isEmpty();
    }

    @Test
    void theScanSeesThePackage() throws IOException {
        // Guard against a moved package silently turning the scan into a no-op.
        assertThat(sources()).anySatisfy(p -> assertThat(p.getFileName().toString())
                .isEqualTo("PracticeSuppressionService.java"));
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(PRACTICE)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
