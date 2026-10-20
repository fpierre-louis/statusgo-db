package io.sitprep.sitprepapi.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fails closed when a development backend would touch production.
 *
 * <p>Why it exists (P1, LAUNCH_READINESS "local and development runs write to
 * the production database"): {@code application.yml} imports
 * {@code application-local.yml} in EVERY profile, and that file points at the
 * production RDS cluster. An IntelliJ run with no profile therefore read and
 * wrote real household data, and the tracked FE {@code .env.development}
 * sends {@code npm run dev} traffic straight to it.</p>
 *
 * <p>A <b>development runtime</b> is any process not on a Heroku dyno (Heroku
 * sets {@code DYNO} on web, release and one-off dynos; laptops and build
 * containers do not), or any process with a development profile active. In a
 * development runtime, unless the break-glass override is set:</p>
 * <ul>
 *   <li>the datasource must be local (H2, localhost, 127.0.0.1, ::1); any other
 *       host stops startup with a message that never prints the URL;</li>
 *   <li>{@code @Scheduled} jobs default to off ({@code app.scheduling.enabled=false}
 *       unless set explicitly), so a laptop is never a second scheduler; this
 *       default holds even under break glass;</li>
 *   <li>R2 credentials are replaced with placeholders, so uploads cannot reach
 *       the production bucket.</li>
 * </ul>
 *
 * <p><b>Break glass</b> (deliberate production access from a laptop, e.g. a
 * supervised data fix): set {@value #BREAK_GLASS_ENV}={@value #BREAK_GLASS_VALUE}.
 * Nothing else unlocks it. Production on Heroku is untouched: with {@code DYNO}
 * set and no development profile, this class does nothing.</p>
 */
public final class LocalProductionGuard implements EnvironmentPostProcessor, Ordered {

    public static final String BREAK_GLASS_ENV = "SITPREP_ALLOW_PRODUCTION_FROM_DEV";
    public static final String BREAK_GLASS_VALUE = "i-am-connecting-a-dev-backend-to-production";

    static final Set<String> DEV_PROFILES = Set.of("local", "dev", "test", "practice-preview", "it-pg");
    static final String PLACEHOLDER = "dev-disabled";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /** After config data (including the imported application-local.yml) is loaded. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!isDevelopmentRuntime(env)) return;
        // Even under break glass a laptop is never a second production scheduler:
        // @Scheduled jobs default off locally (an explicit setting still wins).
        env.getPropertySources().addLast(new MapPropertySource("localProductionGuardDefaults",
                Map.of("app.scheduling.enabled", "false")));
        if (BREAK_GLASS_VALUE.equals(env.getProperty(BREAK_GLASS_ENV))) return;

        if (!isLocalDatabase(safeProperty(env, "spring.datasource.url"))) {
            throw new IllegalStateException(
                    "Refusing to start: a development backend is configured with a non-local database. "
                    + "Development runs (no Heroku DYNO, or a local/dev/test/preview profile) may only use "
                    + "a local database. Point JDBC_DATABASE_URL at localhost (see scripts/local-isolated.sh), "
                    + "or, for deliberate production access, set " + BREAK_GLASS_ENV + "=" + BREAK_GLASS_VALUE + ".");
        }

        Map<String, Object> forced = new LinkedHashMap<>();
        forced.put("r2.account-id", PLACEHOLDER);
        forced.put("r2.access-key-id", PLACEHOLDER);
        forced.put("r2.secret-access-key", PLACEHOLDER);
        env.getPropertySources().addFirst(new MapPropertySource("localProductionGuardForced", forced));
    }

    static boolean isDevelopmentRuntime(ConfigurableEnvironment env) {
        String dyno = env.getProperty("DYNO");
        boolean onHeroku = dyno != null && !dyno.isBlank();
        boolean devProfile = Arrays.stream(env.getActiveProfiles())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(DEV_PROFILES::contains);
        return !onHeroku || devProfile;
    }

    /** Blank/unresolved means nothing to guard (startup fails on its own without a datasource). */
    static boolean isLocalDatabase(String url) {
        if (url == null || url.isBlank()) return true;
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:h2:")) return true;
        int scheme = u.indexOf("://");
        if (scheme < 0) return false;
        String rest = u.substring(scheme + 3);
        int at = rest.lastIndexOf('@', indexOfAny(rest, "/?"));
        if (at >= 0) rest = rest.substring(at + 1);
        String hostList = rest.substring(0, indexOfAny(rest, "/?"));
        if (hostList.isEmpty()) return false;
        for (String hostPort : hostList.split(",")) {
            if (!LOCAL_HOSTS.contains(host(hostPort))) return false;
        }
        return true;
    }

    private static String host(String hostPort) {
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            return end < 0 ? hostPort : hostPort.substring(0, end + 1);
        }
        int colon = hostPort.indexOf(':');
        return colon < 0 ? hostPort : hostPort.substring(0, colon);
    }

    private static int indexOfAny(String s, String chars) {
        int min = s.length();
        for (char c : chars.toCharArray()) {
            int i = s.indexOf(c);
            if (i >= 0 && i < min) min = i;
        }
        return min;
    }

    private static String safeProperty(ConfigurableEnvironment env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }
}
