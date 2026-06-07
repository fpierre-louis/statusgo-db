package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Meta half of the {@link ApiResponse} envelope. Carries the generated-at
 * timestamp (for client clock reconciliation), a DTO version tag (so the FE
 * can detect schema rolls without polling), and the list of section names
 * that degraded during assembly (BE-06 — MeService.safeGet wraps sub-fetches;
 * sections that failed and shipped null/empty land here so the FE can render
 * a quiet "some data unavailable" hint instead of pretending it's complete).
 */
public record ApiMeta(Instant generatedAt, String dtoVersion, List<String> degradedSections) {

    public ApiMeta {
        if (degradedSections == null) {
            degradedSections = Collections.emptyList();
        }
    }

    public static ApiMeta now() {
        return new ApiMeta(Instant.now(), "v1", Collections.emptyList());
    }
}
