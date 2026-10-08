package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.practice.PracticeContent.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * THE answer to "may this Practice content be used right now?" — every
 * Practice read and write goes through here, so the global switch, the
 * per-key kill switch and the publish state cannot disagree between callers.
 *
 * <p>Three layers, any one of which says no:</p>
 * <ol>
 *   <li>{@code app.practice.enabled} (env {@code PRACTICE_ENABLED}) — all of
 *       Practice. A config var flip restarts the dyno; no release needed.</li>
 *   <li>{@link PracticeContentControl} — one key, live on the next request.</li>
 *   <li>The content's own {@link PublishState}.</li>
 * </ol>
 *
 * <p>Starting and continuing differ on purpose. A RETIRED version cannot start
 * but a run already on it may finish — retiring is routine. A DISABLED key
 * stops everything at once — disabling is the emergency brake for content that
 * turned out to be unsafe, and unsafe text must not keep being shown.</p>
 */
@Service
public class PracticeAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(PracticeAvailabilityService.class);

    /** Profiles where unreviewed (DRAFT / SAFETY_REVIEWED) content may be previewed. */
    static final Set<String> PREVIEW_PROFILES = Set.of("local", "test", "practice-preview");

    /** Profiles that veto preview outright, even alongside an allowed one. */
    static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    /** A startable catalog entry. {@code preview} = not yet PUBLISHED (local review only). */
    public record Entry(Version version, boolean preview) {}

    public enum StartDenial { PRACTICE_OFF, NOT_FOUND, DISABLED, NOT_PUBLISHED }

    /** What a pinned run may do with its content. */
    public enum RunContent {
        /** Content present and live: continue normally. */
        AVAILABLE,
        /** Retired since the run began: may finish, cannot be restarted. */
        RETIRED,
        /** Kill switch or global switch: show nothing from the content. */
        DISABLED,
        /** Version gone from the jar, or its hash no longer matches: fail closed. */
        MISSING
    }

    public record RunCheck(RunContent status, Version version) {
        public boolean canContinue() {
            return status == RunContent.AVAILABLE || status == RunContent.RETIRED;
        }
    }

    public record StartCheck(Entry entry, StartDenial denial) {
        public boolean allowed() { return denial == null; }
    }

    /** One row of the operator status list. */
    public record ContentStatus(String key, int version, PracticeKind kind, PublishState publishState,
                                String contentHash, boolean startable, boolean disabled,
                                Instant disabledAt, String disabledReason, String updatedBy) {}

    private final PracticeCatalog catalog;
    private final PracticeContentControlRepo controlRepo;
    private final boolean enabled;
    private final boolean includeUnpublished;
    private final Clock clock;

    @Autowired
    public PracticeAvailabilityService(PracticeCatalog catalog,
                                       PracticeContentControlRepo controlRepo,
                                       @Value("${app.practice.enabled:true}") boolean enabled,
                                       @Value("${app.practice.include-unpublished:false}") boolean includeUnpublished,
                                       Environment environment) {
        this(catalog, controlRepo, enabled,
                previewAllowed(includeUnpublished, environment.getActiveProfiles()), Clock.systemUTC());
    }

    /**
     * The structural guard on {@code PRACTICE_INCLUDE_UNPUBLISHED} (owner review
     * 2026-10-08): DRAFT means not approved, so "nobody will set this in
     * production" is not a boundary. The flag counts only when an allowlisted
     * preview profile is active and no production profile is. Heroku runs with
     * neither, so a stray config var there is ignored — loudly.
     */
    static boolean previewAllowed(boolean flag, String[] activeProfiles) {
        if (!flag) return false;
        Set<String> profiles = Set.copyOf(Arrays.asList(activeProfiles == null ? new String[0] : activeProfiles));
        boolean production = profiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
        boolean preview = profiles.stream().anyMatch(PREVIEW_PROFILES::contains);
        if (production || !preview) {
            log.error("PRACTICE_INCLUDE_UNPUBLISHED is set but active profiles {} are not a preview environment; "
                    + "IGNORED. Unreviewed practice content stays unavailable.", profiles);
            return false;
        }
        log.warn("Practice preview ON (profiles {}): DRAFT / SAFETY_REVIEWED content is startable.", profiles);
        return true;
    }

    /** Is unreviewed content startable here (after the profile guard)? */
    public boolean previewActive() {
        return includeUnpublished;
    }

    PracticeAvailabilityService(PracticeCatalog catalog, PracticeContentControlRepo controlRepo,
                                boolean enabled, boolean includeUnpublished, Clock clock) {
        this.catalog = catalog;
        this.controlRepo = controlRepo;
        this.enabled = enabled;
        this.includeUnpublished = includeUnpublished;
        this.clock = clock;
    }

    public boolean practiceEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Every startable entry of one kind, by key. Empty when Practice is off. */
    @Transactional(readOnly = true)
    public List<Entry> startable(PracticeKind kind) {
        if (!enabled) return List.of();
        Map<String, PracticeContentControl> controls = controlsByKey();
        List<Entry> out = new ArrayList<>();
        for (String key : catalog.keys(kind)) {
            if (isDisabled(controls.get(key))) continue;
            catalog.latestStartable(key, includeUnpublished).ifPresent(v -> out.add(entry(v)));
        }
        return out;
    }

    /** May a new run of {@code key} start, and on which version? */
    @Transactional(readOnly = true)
    public StartCheck checkStart(String key) {
        if (!enabled) return new StartCheck(null, StartDenial.PRACTICE_OFF);
        if (catalog.versions(key).isEmpty()) return new StartCheck(null, StartDenial.NOT_FOUND);
        if (isDisabled(controlRepo.findById(key).orElse(null))) return new StartCheck(null, StartDenial.DISABLED);
        Optional<Version> v = catalog.latestStartable(key, includeUnpublished);
        return v.map(version -> new StartCheck(entry(version), null))
                .orElseGet(() -> new StartCheck(null, StartDenial.NOT_PUBLISHED));
    }

    /**
     * What a run pinned to {@code key} v{@code version} with {@code contentHash}
     * may do. The hash must still match — a version file edited underneath a
     * run is treated as missing, never silently swapped in.
     */
    @Transactional(readOnly = true)
    public RunCheck checkRun(String key, int version, String contentHash) {
        Optional<Version> v = catalog.exact(key, version);
        if (v.isEmpty() || !v.get().contentHash().equals(contentHash)) return new RunCheck(RunContent.MISSING, null);
        if (!enabled || isDisabled(controlRepo.findById(key).orElse(null))) {
            return new RunCheck(RunContent.DISABLED, null);
        }
        PublishState state = v.get().publishState();
        if (state == PublishState.RETIRED) return new RunCheck(RunContent.RETIRED, v.get());
        if (state == PublishState.PUBLISHED || includeUnpublished) return new RunCheck(RunContent.AVAILABLE, v.get());
        // A run begun on unreviewed content in a local preview never continues where previews are off.
        return new RunCheck(RunContent.DISABLED, null);
    }

    /** Every loaded version plus its switch state — the operator view. */
    @Transactional(readOnly = true)
    public List<ContentStatus> statusList() {
        Map<String, PracticeContentControl> controls = controlsByKey();
        List<ContentStatus> out = new ArrayList<>();
        for (Version v : catalog.all()) {
            PracticeContentControl c = controls.get(v.key());
            boolean disabled = isDisabled(c);
            boolean startable = enabled && !disabled
                    && catalog.latestStartable(v.key(), includeUnpublished)
                        .map(latest -> latest.version() == v.version()).orElse(false);
            out.add(new ContentStatus(v.key(), v.version(), v.kind(), v.publishState(), v.contentHash(),
                    startable, disabled,
                    c == null ? null : c.getDisabledAt(),
                    c == null ? null : c.getDisabledReason(),
                    c == null ? null : c.getUpdatedBy()));
        }
        return out;
    }

    public List<PracticeCatalog.Rejected> rejected() {
        return catalog.rejected();
    }

    // ------------------------------------------------------------------
    // Kill switch
    // ------------------------------------------------------------------

    @Transactional
    public PracticeContentControl disable(String key, String reason, String actor) {
        PracticeContentControl c = controlRepo.findById(key).orElseGet(() -> {
            PracticeContentControl fresh = new PracticeContentControl();
            fresh.setContentKey(key);
            return fresh;
        });
        Instant now = clock.instant();
        c.setDisabledAt(now);
        c.setDisabledReason(reason);
        c.setUpdatedBy(actor);
        c.setUpdatedAt(now);
        return controlRepo.save(c);
    }

    @Transactional
    public Optional<PracticeContentControl> enable(String key, String actor) {
        Optional<PracticeContentControl> existing = controlRepo.findById(key);
        if (existing.isEmpty()) return Optional.empty();
        PracticeContentControl c = existing.get();
        c.setDisabledAt(null);
        c.setUpdatedBy(actor);
        c.setUpdatedAt(clock.instant());
        return Optional.of(controlRepo.save(c));
    }

    // ------------------------------------------------------------------

    private Entry entry(Version v) {
        return new Entry(v, v.publishState() != PublishState.PUBLISHED);
    }

    private Map<String, PracticeContentControl> controlsByKey() {
        Map<String, PracticeContentControl> out = new HashMap<>();
        for (PracticeContentControl c : controlRepo.findAll()) out.put(c.getContentKey(), c);
        return out;
    }

    private static boolean isDisabled(PracticeContentControl c) {
        return c != null && c.isDisabled();
    }
}
