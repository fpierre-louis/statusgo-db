package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.AdvancedReadinessCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.MeDto.AdvancedReadinessCompletionDto;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.util.AuthUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Weekly preparedness challenge progress, persisted per-household.
 *
 * <p>Backs the FE swap-in flagged in {@code src/me/challenges/challenges.js}:
 * isThisWeekDone / markChallengeDone / getDrillsCompleted now resolve
 * against {@code Household.challengeProgress} rather than per-device
 * {@code meCache}. Idempotent — calling the endpoint twice with the same
 * weekKey is a no-op and still returns 200. Permission is household
 * membership (anyone in the household can mark the drill done; not
 * admin-gated).</p>
 *
 * <p>Side-note: the underlying entity is a {@code Group} with
 * {@code groupType="Household"}; access checks live in
 * {@link HouseholdAccessService} which already enforces that semantic.</p>
 */
@RestController
@RequestMapping("/api/households")
public class HouseholdChallengesResource {

    /**
     * ISO week-year format we accept ("2026-W22"). Mirrors the FE
     * {@code currentWeekKey()} in challenges.js. Year is a 4-digit int,
     * week is two digits 01..53. Defensive — the FE will send the
     * canonical form, but the BE shouldn't trust client input.
     */
    private static final Pattern WEEK_KEY = Pattern.compile("^\\d{4}-W(0[1-9]|[1-4]\\d|5[0-3])$");
    private static final Pattern ITEM_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$");

    private final GroupRepo groupRepo;
    private final HouseholdAccessService access;

    public HouseholdChallengesResource(GroupRepo groupRepo, HouseholdAccessService access) {
        this.groupRepo = groupRepo;
        this.access = access;
    }

    /**
     * Mark this household's preparedness challenge for {@code weekKey}
     * as complete. Idempotent — a repeat call is a no-op (returns 200
     * with the current map). Returns the full {@code challengeProgress}
     * map so the FE can rehydrate state without a follow-up /me hit.
     *
     * <p>Auth: caller must be in {@code memberEmails} of the household.
     * Owner / admin gating is intentionally NOT enforced — any member
     * may report that the drill happened (the household is collective).</p>
     */
    @PostMapping("/{householdId}/challenges/{weekKey}/complete")
    @Transactional
    public ResponseEntity<Map<String, Boolean>> markComplete(
            @PathVariable String householdId,
            @PathVariable String weekKey
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        validateWeekKey(weekKey);
        access.requireCanReadHousehold(caller, householdId);

        Group household = householdOr404(householdId);

        Map<String, Boolean> progress = household.getChallengeProgress();
        if (progress == null) {
            progress = new HashMap<>();
            household.setChallengeProgress(progress);
        }

        boolean alreadyDone = Boolean.TRUE.equals(progress.get(weekKey));
        if (!alreadyDone) {
            progress.put(weekKey, Boolean.TRUE);
            groupRepo.save(household);
        }
        // Defensive copy — never hand the persistence-context-managed
        // collection out onto the wire.
        return ResponseEntity.ok(new HashMap<>(progress));
    }

    /**
     * Mark that this household has seen the weekly challenge prompt for
     * {@code weekKey}. Viewing/dismissing is deliberately not completion; it
     * only prevents repeated drawer auto-opens across devices.
     */
    @PostMapping("/{householdId}/challenges/{weekKey}/shown")
    @Transactional
    public ResponseEntity<Map<String, String>> markShown(
            @PathVariable String householdId,
            @PathVariable String weekKey
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        validateWeekKey(weekKey);
        access.requireCanReadHousehold(caller, householdId);

        Group household = householdOr404(householdId);
        if (!weekKey.equals(household.getChallengeLastShownWeek())) {
            household.setChallengeLastShownWeek(weekKey);
            groupRepo.save(household);
        }
        return ResponseEntity.ok(Map.of("challengeLastShownWeek", household.getChallengeLastShownWeek()));
    }

    /**
     * Self-report an optional advanced-readiness item as complete. Admin-only
     * because it edits household-shared plan state; idempotent because repeat
     * taps should settle to the same row, not append activity.
     */
    @PutMapping("/{householdId}/advanced-readiness/{itemKey}")
    @Transactional
    public ResponseEntity<Map<String, AdvancedReadinessCompletionDto>> markAdvancedReadinessComplete(
            @PathVariable String householdId,
            @PathVariable String itemKey
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        validateItemKey(itemKey);
        access.requireCanAdminHousehold(caller, householdId);

        Group household = householdOr404(householdId);
        Map<String, AdvancedReadinessCompletion> progress = household.getAdvancedReadinessProgress();
        if (progress == null) {
            progress = new HashMap<>();
            household.setAdvancedReadinessProgress(progress);
        }

        progress.putIfAbsent(itemKey, new AdvancedReadinessCompletion(Instant.now(), caller));
        groupRepo.save(household);
        return ResponseEntity.ok(advancedDto(progress));
    }

    /**
     * Untoggle an optional advanced-readiness item. This removes only the
     * self-reported optional row; contact- or plan-derived readiness remains
     * derived from the source documents.
     */
    @DeleteMapping("/{householdId}/advanced-readiness/{itemKey}")
    @Transactional
    public ResponseEntity<Map<String, AdvancedReadinessCompletionDto>> clearAdvancedReadiness(
            @PathVariable String householdId,
            @PathVariable String itemKey
    ) {
        String caller = AuthUtils.requireAuthenticatedEmail();
        validateItemKey(itemKey);
        access.requireCanAdminHousehold(caller, householdId);

        Group household = householdOr404(householdId);
        Map<String, AdvancedReadinessCompletion> progress = household.getAdvancedReadinessProgress();
        if (progress != null && progress.remove(itemKey) != null) {
            groupRepo.save(household);
        }
        return ResponseEntity.ok(advancedDto(progress));
    }

    private void validateWeekKey(String weekKey) {
        if (weekKey == null || !WEEK_KEY.matcher(weekKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid weekKey — expected ISO week format like 2026-W22");
        }
    }

    private void validateItemKey(String itemKey) {
        if (itemKey == null || !ITEM_KEY.matcher(itemKey).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid itemKey — expected 1-96 letters, numbers, hyphens, or underscores");
        }
    }

    private Group householdOr404(String householdId) {
        return groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Household not found"));
    }

    private static Map<String, AdvancedReadinessCompletionDto> advancedDto(
            Map<String, AdvancedReadinessCompletion> progress
    ) {
        if (progress == null || progress.isEmpty()) return Map.of();
        Map<String, AdvancedReadinessCompletionDto> out = new HashMap<>();
        for (var entry : progress.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            AdvancedReadinessCompletion c = entry.getValue();
            if (c.getCompletedAt() == null) continue;
            out.put(entry.getKey(), new AdvancedReadinessCompletionDto(c.getCompletedAt(), c.getCompletedBy()));
        }
        return out;
    }
}
