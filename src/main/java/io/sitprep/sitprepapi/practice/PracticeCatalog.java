package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every Practice content version shipped in the jar, loaded and validated once
 * at boot.
 *
 * <p><b>Fails closed, never loud at runtime.</b> A file that does not parse or
 * does not pass {@link PracticeContentValidator} is excluded and logged at
 * ERROR — it is never startable — but it cannot take the API down. The build
 * is where invalid content is meant to be caught
 * ({@code PracticeShippedContentTest}).</p>
 *
 * <p>Two versions claiming the same key+version are both rejected: ambiguity
 * about which text a run saw is exactly what the hash exists to prevent.</p>
 */
@Component
public class PracticeCatalog {

    private static final Logger log = LoggerFactory.getLogger(PracticeCatalog.class);

    public static final String DEFAULT_LOCATION = "classpath*:practice/content/**/*.json";

    /** A file that was not loaded, and why. Surfaced on the operator status list. */
    public record Rejected(String resourcePath, String key, Integer version, List<String> problems) {}

    private final Map<String, List<Version>> byKey;
    private final List<Rejected> rejected;

    @Autowired
    public PracticeCatalog(@Value("${app.practice.content-location:" + DEFAULT_LOCATION + "}") String location) {
        this(load(location));
    }

    PracticeCatalog(Loaded loaded) {
        List<Rejected> bad = new ArrayList<>(loaded.rejected());
        Map<String, List<Version>> grouped = new LinkedHashMap<>();
        Map<String, List<Version>> byIdentity = new HashMap<>();
        for (Version v : loaded.versions()) {
            byIdentity.computeIfAbsent(v.key() + "#" + v.version(), k -> new ArrayList<>()).add(v);
        }
        for (Version v : loaded.versions()) {
            List<Version> same = byIdentity.get(v.key() + "#" + v.version());
            if (same.size() > 1) {
                bad.add(new Rejected(v.resourcePath(), v.key(), v.version(),
                        List.of("duplicate key+version " + v.key() + " v" + v.version())));
                continue;
            }
            grouped.computeIfAbsent(v.key(), k -> new ArrayList<>()).add(v);
        }
        for (List<Version> versions : grouped.values()) {
            versions.sort(Comparator.comparingInt(Version::version).reversed());
        }
        this.byKey = grouped;
        this.rejected = List.copyOf(bad);
        for (Rejected r : rejected) {
            log.error("Practice content rejected: {} — {}", r.resourcePath(), r.problems());
        }
        log.info("Practice catalog: {} key(s), {} version(s), {} rejected",
                byKey.size(), byKey.values().stream().mapToInt(List::size).sum(), rejected.size());
    }

    /** Test seam: build a catalog from versions already in hand (validated here like files are). */
    static PracticeCatalog of(List<Version> versions) {
        List<Version> ok = new ArrayList<>();
        List<Rejected> bad = new ArrayList<>();
        for (Version v : versions) {
            List<String> problems = PracticeContentValidator.validate(v);
            if (problems.isEmpty()) ok.add(v);
            else bad.add(new Rejected(v.resourcePath(), v.key(), v.version(), problems));
        }
        return new PracticeCatalog(new Loaded(ok, bad));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** All keys of one kind, in a stable order. */
    public List<String> keys(PracticeKind kind) {
        return byKey.entrySet().stream()
                .filter(e -> e.getValue().get(0).kind() == kind)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Every loaded version of {@code key}, newest first. */
    public List<Version> versions(String key) {
        return byKey.getOrDefault(key, List.of());
    }

    /**
     * The version a NEW run of {@code key} would use: the newest PUBLISHED
     * version, or — only when {@code includeUnpublished} — the newest
     * DRAFT/SAFETY_REVIEWED/PUBLISHED one. Never RETIRED.
     */
    public Optional<Version> latestStartable(String key, boolean includeUnpublished) {
        for (Version v : versions(key)) {
            PublishState s = v.publishState();
            if (s.startableInProduction()) return Optional.of(v);
            if (includeUnpublished && s != PublishState.RETIRED) return Optional.of(v);
        }
        return Optional.empty();
    }

    /** One exact version, for a run that pinned it. */
    public Optional<Version> exact(String key, int version) {
        return versions(key).stream().filter(v -> v.version() == version).findFirst();
    }

    public List<Version> all() {
        return byKey.values().stream().flatMap(List::stream).toList();
    }

    public List<Rejected> rejected() {
        return rejected;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    record Loaded(List<Version> versions, List<Rejected> rejected) {}

    static Loaded load(String location) {
        List<Version> ok = new ArrayList<>();
        List<Rejected> bad = new ArrayList<>();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(location);
        } catch (IOException e) {
            log.error("Practice catalog: could not list {}", location, e);
            return new Loaded(ok, bad);
        }
        for (Resource r : resources) {
            String path = describe(r);
            try (InputStream in = r.getInputStream()) {
                Version v = PracticeContentParser.parse(in, path);
                List<String> problems = PracticeContentValidator.validate(v);
                if (problems.isEmpty()) ok.add(v);
                else bad.add(new Rejected(path, v.key(), v.version(), problems));
            } catch (IOException | RuntimeException e) {
                bad.add(new Rejected(path, null, null, List.of("unreadable: " + e.getMessage())));
            }
        }
        return new Loaded(ok, bad);
    }

    private static String describe(Resource r) {
        try {
            String url = r.getURL().toString();
            int i = url.indexOf("practice/content/");
            return i >= 0 ? url.substring(i) : url;
        } catch (IOException e) {
            return String.valueOf(r.getFilename());
        }
    }
}
