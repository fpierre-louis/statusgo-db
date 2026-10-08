package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.GroupMemberViewDto.StatusRollup;
import io.sitprep.sitprepapi.dto.HouseholdAccompanimentDto;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * THE accountability-rollup math (Thin-Client Refactor Phase 1), extracted
 * from {@code GroupViewService} so the Global Readiness Engine can derive
 * {@code dominantStatus} from the SAME aggregation — zero duplication.
 * {@code GroupViewService.computeRollup} now delegates here verbatim.
 *
 * <p>Semantics (mirror the retired {@code useHouseholdData.counts} exactly):</p>
 * <ul>
 *   <li><b>Real members:</b> freshness-clamped — while the group's alert is
 *       Active, a status last updated before the alert start ({@code updatedAt})
 *       is treated as NO RESPONSE. SAFE / HELP / INJURED bucket to their
 *       counts; anything else (incl. blank / stale / unknown) is noResponse.</li>
 *   <li><b>Manual members</b> (dependents without accounts) — see
 *       {@link #manualBucket}: a fresh status an admin set for them buckets
 *       to SAFE / HELP / INJURED (V103); otherwise a "with me" accompaniment
 *       counts them safe; otherwise noResponse. An unmarked, unaccompanied
 *       dependent stays noResponse — nobody has accounted for them.</li>
 * </ul>
 */
public final class StatusRollups {

    private StatusRollups() {}

    /**
     * The line that decides which answers count during a check-in: when it
     * STARTED. `updatedAt` only as the fallback for check-ins opened before
     * alertActivatedAt existed — any edit to the group moved it, and with it
     * which answers counted (open-items plan 1.3, 2026-10-02).
     */
    public static java.time.Instant anchorFor(io.sitprep.sitprepapi.domain.Group g) {
        if (g == null) return null;
        return g.getAlertActivatedAt() != null ? g.getAlertActivatedAt() : g.getUpdatedAt();
    }

    public static StatusRollup compute(List<String> memberEmails,
                                       Map<String, UserInfo> byEmail,
                                       List<HouseholdManualMemberDto> manualMembers,
                                       List<HouseholdAccompanimentDto> accompaniments,
                                       boolean alertActive,
                                       Instant updatedAt) {
        long startMs = (alertActive && updatedAt != null) ? updatedAt.toEpochMilli() : 0L;
        int safe = 0, help = 0, injured = 0, noResponse = 0, total = 0;

        for (String email : memberEmails) {
            total++;
            UserInfo u = byEmail.get(normalize(email));
            Instant statusAt = u == null ? null : u.getUserStatusLastUpdated();
            long updatedMs = statusAt == null ? 0L : statusAt.toEpochMilli();
            boolean fresh = startMs == 0L || updatedMs >= startMs;
            String raw = u == null ? null : u.getUserStatus();
            String v = (fresh && raw != null && !raw.isBlank())
                    ? raw.trim().toUpperCase(Locale.ROOT) : "NO RESPONSE";
            switch (v) {
                case "SAFE" -> safe++;
                case "HELP" -> help++;
                case "INJURED" -> injured++;
                default -> noResponse++;
            }
        }

        for (HouseholdManualMemberDto m : manualMembers) {
            total++;
            String bucket = manualBucket(m, accompaniments, alertActive, updatedAt);
            if (bucket == null) { noResponse++; continue; }
            switch (bucket) {
                case "HELP" -> help++;
                case "INJURED" -> injured++;
                default -> safe++;
            }
        }

        return new StatusRollup(total, safe + help + injured, safe, help, injured, noResponse);
    }

    /**
     * Where one manual member counts — SAFE / HELP / INJURED, or null for
     * noResponse. Shared by this rollup and {@code GroupService}'s
     * check-in rollup so the two cannot disagree (gameplan §5.5).
     * <ol>
     *   <li>A FRESH status set for them wins. Fresh = the DTO still shows it
     *       (a calm SAFE inside its 24h; HELP / INJURED until changed) and,
     *       while a check-in runs, it was set at or after the anchor — the
     *       same clamp an account's status gets.</li>
     *   <li>So HELP / INJURED outrank the SAFE an accompaniment implies —
     *       even a stale one: it counts as noResponse, never SAFE.</li>
     *   <li>Otherwise a "with me" accompaniment counts them safe.</li>
     *   <li>Otherwise null: nobody has accounted for them.</li>
     * </ol>
     */
    public static String manualBucket(HouseholdManualMemberDto m,
                                      List<HouseholdAccompanimentDto> accompaniments,
                                      boolean alertActive, Instant anchor) {
        if (m == null) return null;
        HouseholdManualMemberDto.ManualStatus st = m.status();
        if (st != null && st.value() != null) {
            String v = st.value().trim().toUpperCase(Locale.ROOT);
            boolean fresh = !alertActive || anchor == null
                    || (st.updatedAt() != null && !st.updatedAt().isBefore(anchor));
            if (fresh) return v;
            // HELP / INJURED from before the check-in: noResponse, as an
            // account's would be — never the SAFE an accompaniment implies.
            // The row still shows the bad news (it never lapses); the count
            // just won't call that child safe.
            if ("HELP".equals(v) || "INJURED".equals(v)) return null;
        }
        boolean claimed = accompaniments != null && accompaniments.stream().anyMatch(a ->
                a.accompaniedRef() != null
                        && "manual".equals(a.accompaniedRef().kind())
                        && m.id() != null
                        && m.id().equals(a.accompaniedRef().id()));
        return claimed ? "SAFE" : null;
    }

    /**
     * Canonical dominant-status derivation from a rollup — severity first,
     * so the label never hides a member in trouble:
     * <ol>
     *   <li>empty roster / all silent → {@code UNKNOWN}</li>
     *   <li>any injured → {@code INJURED}; any help → {@code HELP}</li>
     *   <li>some accounted + some silent → {@code CHECK_IN}</li>
     *   <li>everyone accounted safe → {@code SAFE}</li>
     * </ol>
     */
    public static String dominantStatus(StatusRollup r) {
        if (r == null || r.total() == 0) return "UNKNOWN";
        if (r.injured() > 0) return "INJURED";
        if (r.help() > 0) return "HELP";
        if (r.noResponse() == r.total()) return "UNKNOWN";
        if (r.noResponse() > 0) return "CHECK_IN";
        return "SAFE";
    }

    static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
