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
 *   <li><b>Manual members</b> (dependents without accounts): accounted (safe)
 *       only when an adult has claimed them via a "with me" accompaniment;
 *       otherwise noResponse.</li>
 * </ul>
 */
public final class StatusRollups {

    private StatusRollups() {}

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
            boolean claimed = accompaniments.stream().anyMatch(a ->
                    a.accompaniedRef() != null
                            && "manual".equals(a.accompaniedRef().kind())
                            && m.id() != null
                            && m.id().equals(a.accompaniedRef().id()));
            if (claimed) safe++;
            else noResponse++;
        }

        return new StatusRollup(total, safe + help + injured, safe, help, injured, noResponse);
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
