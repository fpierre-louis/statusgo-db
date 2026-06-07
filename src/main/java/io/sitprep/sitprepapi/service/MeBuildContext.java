package io.sitprep.sitprepapi.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Thread-local collector of degraded sub-section names during a single
 * {@code MeService.buildMe} (or {@code buildMePlans}) invocation.
 *
 * <p>Replaces the old "silent fallback" behaviour of
 * {@code MeService.safeGet}: when a sub-fetch throws and we substitute a
 * fallback so the rest of the /me payload still ships, we now ALSO record
 * the section name here. {@code MeResource} reads the collected set and
 * passes it through {@code ApiMeta.degradedSections} so the FE can render
 * a per-section "Tap to retry" instead of pretending an empty list is the
 * authoritative answer.</p>
 *
 * <p>Lifecycle: {@link #begin()} on entry, {@link #drain()} (which also
 * clears the thread-local) on exit. Always call {@code drain()} in a
 * {@code finally} block so a thrown exception doesn't leak state into the
 * next pooled thread's request.</p>
 *
 * <p>Order: sections are stored in a {@link LinkedHashSet} so the FE
 * receives them in the order they failed, which mirrors the assembly
 * order in {@code MeService.assemble} / {@code assemblePlans}. The
 * collector silently no-ops when {@link #begin()} has not been called,
 * so unit tests or background callers that exercise {@code safeGet}
 * outside of a /me request don't blow up.</p>
 */
final class MeBuildContext {

    private static final ThreadLocal<Set<String>> SECTIONS = new ThreadLocal<>();

    private MeBuildContext() {
    }

    /** Initialise an empty collector for the current thread. */
    static void begin() {
        SECTIONS.set(new LinkedHashSet<>());
    }

    /**
     * Record {@code section} as degraded for the current request. No-op
     * when {@link #begin()} was not called (e.g. test harness, background
     * job) so callers don't have to defend.
     */
    static void markDegraded(String section) {
        if (section == null || section.isBlank()) return;
        Set<String> current = SECTIONS.get();
        if (current == null) return;
        current.add(section);
    }

    /**
     * Drain the collected section names and clear the thread-local.
     * Always call in a {@code finally} block. Returns an immutable
     * copy so the caller can hold it past the cleanup without aliasing.
     */
    static List<String> drain() {
        Set<String> current = SECTIONS.get();
        SECTIONS.remove();
        if (current == null || current.isEmpty()) return Collections.emptyList();
        return List.copyOf(current);
    }
}
