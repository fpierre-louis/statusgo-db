package io.sitprep.sitprepapi.practice;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.AdminAuditLogService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Owner/admin "clear shared Practice history" (owner ruling 2026-10-22).
 *
 * <p>A regular member records practice but must not silently delete the
 * household's shared history, so this is gated on
 * {@link HouseholdAccessService#requireCanAdminHousehold} and takes an explicit
 * confirmation at the resource. It removes, in ONE transaction: the
 * household's {@code scenario_run} rows (every status, including an in-progress
 * run, so nothing resumes against cleared history), its
 * {@code family_practice_completion} rows, {@code Group.drillLog} and
 * {@code Group.challengeProgress}. {@code challengeLastShownWeek} is kept: it
 * only suppresses the weekly prompt. Personal runs ({@code household_id} NULL)
 * and the token ledgers are never touched; Practice has no token economy and
 * ledger rows are not practice history.</p>
 *
 * <p>Clearing the drill log also un-completes readiness items derived from it,
 * so the realtime frame makes every device refetch {@code /me} as well as the
 * Practice screens.</p>
 */
@Service
public class PracticeHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PracticeHistoryService.class);

    static final String AUDIT_ACTION = "CLEARED_PRACTICE_HISTORY";

    public record Removed(int scenarioRuns, int familyCompletions, int drillRecords, int weekFlags) {}

    public record ClearResult(String householdId, Instant clearedAt, Removed removed) {}

    private final HouseholdAccessService access;
    private final GroupRepo groupRepo;
    private final ScenarioRunRepo runRepo;
    private final FamilyPracticeCompletionRepo completionRepo;
    private final AdminAuditLogService audit;
    private final WebSocketMessageSender ws;
    private final Clock clock;

    @Autowired
    public PracticeHistoryService(HouseholdAccessService access, GroupRepo groupRepo, ScenarioRunRepo runRepo,
                                  FamilyPracticeCompletionRepo completionRepo, AdminAuditLogService audit,
                                  WebSocketMessageSender ws) {
        this(access, groupRepo, runRepo, completionRepo, audit, ws, Clock.systemUTC());
    }

    PracticeHistoryService(HouseholdAccessService access, GroupRepo groupRepo, ScenarioRunRepo runRepo,
                           FamilyPracticeCompletionRepo completionRepo, AdminAuditLogService audit,
                           WebSocketMessageSender ws, Clock clock) {
        this.access = access;
        this.groupRepo = groupRepo;
        this.runRepo = runRepo;
        this.completionRepo = completionRepo;
        this.audit = audit;
        this.ws = ws;
        this.clock = clock;
    }

    @Transactional
    public ClearResult clear(String callerEmail, String householdId, boolean confirmed) {
        // Authorize first: a member gets 403 whether or not they sent the confirmation.
        access.requireCanAdminHousehold(callerEmail, householdId);
        if (!confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confirm=true is required to clear Practice history");
        }
        Group household = groupRepo.findByGroupId(householdId)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Household not found"));

        int runs = runRepo.deleteAllByHousehold(householdId);
        int completions = completionRepo.deleteAllByHousehold(householdId);

        int drills = household.getDrillLog() == null ? 0 : household.getDrillLog().size();
        int weeks = household.getChallengeProgress() == null ? 0 : household.getChallengeProgress().size();
        if (household.getDrillLog() == null) household.setDrillLog(new HashMap<>());
        else household.getDrillLog().clear();
        if (household.getChallengeProgress() == null) household.setChallengeProgress(new HashMap<>());
        else household.getChallengeProgress().clear();
        // No groupRepo.save: the household is managed in this transaction, so clearing its collections
        // flushes at commit (and PracticeInvariantTest keeps Practice to find* calls on GroupRepo).

        Removed removed = new Removed(runs, completions, drills, weeks);
        Instant now = clock.instant();
        audit.record(callerEmail, AUDIT_ACTION, "group", householdId,
                "scenarioRuns=" + runs + " familyCompletions=" + completions
                        + " drillRecords=" + drills + " weekFlags=" + weeks);
        log.info("Practice history cleared for household {}: {}", householdId, removed);

        Map<String, Object> frame = Map.of("type", "practice-history-cleared",
                "householdId", householdId, "clearedAt", now.toString());
        afterCommit(() -> ws.sendHouseholdPractice(householdId, frame));
        return new ClearResult(householdId, now, removed);
    }

    private static void afterCommit(Runnable publish) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }
}
