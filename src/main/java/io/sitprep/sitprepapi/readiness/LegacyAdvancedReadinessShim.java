package io.sitprep.sitprepapi.readiness;

import io.sitprep.sitprepapi.readiness.ReadinessJourneyDtos.SetItemStateRequest;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TEMPORARY compatibility for app builds that predate Ready for More
 * (EXEC-A1 task 2). <b>Remove after TestFlight build 6 is the minimum
 * supported build and the web app has shipped the Ready for More frontend</b>
 * (added 2026-10-07; target removal: the first release after both are true).
 *
 * <p>Those builds' Home card ("advanced readiness") toggles three self-report
 * items through {@code PUT/DELETE /api/households/{id}/advanced-readiness/{itemKey}}:
 * {@code documentVault}, {@code medicalStockpile} and {@code quarterlyDrill}
 * (its contact items navigate instead of calling this). V98 removed the routes
 * and the table behind them, so the calls 404 and the card silently keeps a
 * device-local toggle.</p>
 *
 * <p>This shim is deliberately narrow and holds NO state of its own:</p>
 * <ul>
 *   <li>{@code documentVault} is the one legacy item with a MANUAL equivalent:
 *       PUT/DELETE become DONE / clear-DONE on {@code documents.first_folder}
 *       through {@link ReadinessJourneyService}, so the new endpoint's rules
 *       apply unchanged (admin-only step, 404 / 403 / 401).</li>
 *   <li>Every other key is a no-op: their truth now lives in the real domain
 *       (stockpile, drills, contacts) or nowhere, and this is not a second
 *       store. Membership is still required.</li>
 *   <li>The response is the legacy map shape {@code {itemKey: {completedAt,
 *       completedBy}}} built ONLY from {@code household_readiness_item_state}:
 *       {@code documentVault} is present iff the household's
 *       {@code documents.first_folder} row is DONE. {@code completedBy} is
 *       null — the row records who created it, not who last marked it done, so
 *       any name here could be wrong.</li>
 * </ul>
 *
 * <p>The old card replaces its local state with this map, so a no-op toggle
 * snaps back off on the device: that is the truth (it is not saved anywhere)
 * rather than a checkmark that pretends to sync. The removed
 * {@code MeDto.household.advancedReadinessProgress} field is NOT re-added: the
 * old card falls back to its own cache when the field is absent, and adding a
 * readiness read to every {@code /me} for a retiring client is not worth it.</p>
 */
@Deprecated(since = "2026-10-07", forRemoval = true)
@RestController
@RequestMapping("/api/households/{householdId}/advanced-readiness")
public class LegacyAdvancedReadinessShim {

    static final String DOCUMENT_VAULT = "documentVault";
    static final String FOLDER_KEY = "documents.first_folder";
    /** Marks rows written through this shim, so their volume can be checked before removal. */
    static final String REASON_CODE = "legacy_document_vault";

    /** The legacy endpoint's own key rule (HouseholdChallengesResource before V98). */
    private static final Pattern LEGACY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$");

    /** Wire shape of one legacy map entry (was MeDto.AdvancedReadinessCompletionDto). */
    public record LegacyCompletionDto(Instant completedAt, String completedBy) {}

    private final ReadinessJourneyService journeyService;
    private final HouseholdReadinessItemStateRepo stateRepo;
    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;

    public LegacyAdvancedReadinessShim(ReadinessJourneyService journeyService,
                                       HouseholdReadinessItemStateRepo stateRepo,
                                       GroupRepo groupRepo,
                                       HouseholdAccessService access) {
        this.journeyService = journeyService;
        this.stateRepo = stateRepo;
        this.groupRepo = groupRepo;
        this.access = access;
    }

    @PutMapping("/{itemKey}")
    public ResponseEntity<Map<String, LegacyCompletionDto>> markComplete(@PathVariable String householdId,
                                                                         @PathVariable String itemKey) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        validate(itemKey);
        if (DOCUMENT_VAULT.equals(itemKey)) {
            journeyService.setState(householdId, FOLDER_KEY,
                    new SetItemStateRequest(ItemStateKind.DONE.name(), null, REASON_CODE), caller);
        } else {
            requireMember(householdId, caller);
        }
        return ResponseEntity.ok(legacyMap(householdId));
    }

    @DeleteMapping("/{itemKey}")
    public ResponseEntity<Map<String, LegacyCompletionDto>> clear(@PathVariable String householdId,
                                                                  @PathVariable String itemKey) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        validate(itemKey);
        if (DOCUMENT_VAULT.equals(itemKey)) {
            journeyService.clearState(householdId, FOLDER_KEY, ItemStateKind.DONE.name(), caller);
        } else {
            requireMember(householdId, caller);
        }
        return ResponseEntity.ok(legacyMap(householdId));
    }

    /** The legacy map, from the new table only. */
    Map<String, LegacyCompletionDto> legacyMap(String householdId) {
        return stateRepo.findFirstByHouseholdIdAndItemKeyAndScope(
                        householdId, FOLDER_KEY, HouseholdReadinessItemState.SCOPE_HOUSEHOLD)
                .filter(r -> r.getState() == ItemStateKind.DONE)
                .map(r -> Map.of(DOCUMENT_VAULT, new LegacyCompletionDto(
                        r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt(), null)))
                .orElse(Map.of());
    }

    private static void validate(String itemKey) {
        if (itemKey == null || !LEGACY_KEY.matcher(itemKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid itemKey — expected 1-96 letters, numbers, hyphens, or underscores");
        }
    }

    private void requireMember(String householdId, String caller) {
        boolean household = householdId != null && groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .isPresent();
        if (!household) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found");
        access.requireCanReadHousehold(caller.trim().toLowerCase(java.util.Locale.ROOT), householdId);
    }
}
