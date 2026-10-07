package io.sitprep.sitprepapi.readiness;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for {@code /api/households/{id}/readiness} (CONTRACT.md §7).
 *
 * <p>No internal ranking score is ever carried here — the client shows one
 * next step and plain counts, never a number that grades the household.</p>
 */
public final class ReadinessJourneyDtos {

    private ReadinessJourneyDtos() {}

    /** Bump when the JSON shape changes. */
    public static final int SCHEMA_VERSION = 1;

    public record ReadinessJourneyDto(
            int schemaVersion,
            String catalogVersion,
            String recommendationVersion,
            String householdId,
            Instant generatedAt,
            JourneyMode mode,
            EssentialsDto essentials,
            ActiveResponseDto activeResponse,
            int doneCount,
            boolean allCaughtUp,
            NextStepDto nextStep,
            List<AreaDto> areas,
            List<ToolDto> tools
    ) {}

    public record EssentialsDto(boolean complete, int done, int total, String nextKey) {}

    public record ActiveResponseDto(String kind, String title, String detail, ActionDto action) {}

    public record NextStepDto(
            String itemKey,
            ReadinessArea area,
            String title,
            String description,
            ReasonDto reason,
            BandDto time,
            BandDto cost,
            BandDto effort,
            List<ProvenanceDto> provenance,
            ActionDto action
    ) {}

    public record AreaDto(
            ReadinessArea key,
            String title,
            String description,
            int done,
            int total,
            SetupHintDto setupHint,
            String emptyCopy,
            List<ItemDto> items
    ) {}

    public record SetupHintDto(String title, String description, ActionDto action) {}

    public record ItemDto(
            String key,
            ReadinessArea area,
            ReadinessScope scope,
            String title,
            String description,
            List<String> guidance,
            CompletionState completion,
            CompletionSource completionSource,
            Instant completedAt,
            Freshness freshness,
            Instant reviewDueAt,
            ItemStateKind householdState,
            UserStateDto userState,
            BandDto time,
            BandDto cost,
            BandDto effort,
            List<ProvenanceDto> provenance,
            ActionDto action,
            CapabilitiesDto capabilities
    ) {}

    public record UserStateDto(ItemStateKind state, Instant until) {}

    public record CapabilitiesDto(
            boolean canMarkDone,
            boolean canUndo,
            boolean canSkip,
            boolean canRemind,
            boolean canMarkNotRelevant,
            boolean canRestore
    ) {}

    public record ReasonDto(String code, String text) {}

    public record BandDto(String band, String label) {
        static BandDto of(TimeBand b) { return b == null ? null : new BandDto(b.name(), b.label()); }
        static BandDto of(CostBand b) { return b == null ? null : new BandDto(b.name(), b.label()); }
        static BandDto of(EffortBand b) { return b == null ? null : new BandDto(b.name(), b.label()); }
    }

    public record ProvenanceDto(ProvenanceKind kind, String label, String url) {
        static ProvenanceDto of(ReadinessCatalog.Provenance p) {
            return new ProvenanceDto(p.kind(), p.kind().label(), p.url());
        }
    }

    public record ActionDto(ReadinessAction type, Map<String, String> params) {
        static ActionDto of(ReadinessAction type, Map<String, String> params) {
            return new ActionDto(type, params == null ? Map.of() : params);
        }
    }

    public record ToolDto(String key, String title, String description, ActionDto action) {}

    /**
     * {@code PUT …/items/{itemKey}/state} body. {@code remindInDays} applies
     * to REMIND_LATER only (1, 7 or 30; default 7).
     */
    public record SetItemStateRequest(String state, Integer remindInDays, String reasonCode) {}
}
