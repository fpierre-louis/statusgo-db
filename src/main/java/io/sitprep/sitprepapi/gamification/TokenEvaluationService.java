package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates an event against the catalog, off the request thread.
 *
 * <ol>
 *   <li>Re-read the records ({@link HouseholdTokenCriteria}, {@link UserTokenCriteria}).</li>
 *   <li>Award through {@link TokenAwardService} — idempotent; returns only new awards.</li>
 *   <li>Notify ({@link TokenNotifier}) only for a new award.</li>
 *   <li>Log and swallow every failure, per token, so one bad criterion never
 *       blocks another and nothing reaches the action that earned it.</li>
 * </ol>
 */
@Service
public class TokenEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(TokenEvaluationService.class);

    /** Household Ready = holding all three of these. */
    static final List<TokenKey> HOUSEHOLD_READY_PARTS =
            List.of(TokenKey.PLAN_ARCHITECT, TokenKey.STOCKPILE_STEWARD, TokenKey.DRILL_CREW);

    private final HouseholdTokenCriteria householdCriteria;
    private final UserTokenCriteria userCriteria;
    private final TokenAwardService awards;
    private final TokenNotifier notifier;
    private final HouseholdTokenLedgerRepo householdLedger;
    private final HouseholdAccessService householdAccess;

    public TokenEvaluationService(HouseholdTokenCriteria householdCriteria,
                                  UserTokenCriteria userCriteria,
                                  TokenAwardService awards,
                                  TokenNotifier notifier,
                                  HouseholdTokenLedgerRepo householdLedger,
                                  HouseholdAccessService householdAccess) {
        this.householdCriteria = householdCriteria;
        this.userCriteria = userCriteria;
        this.awards = awards;
        this.notifier = notifier;
        this.householdLedger = householdLedger;
        this.householdAccess = householdAccess;
    }

    @Async(TokenAsyncConfig.EXECUTOR)
    public void evaluateAsync(TokenEvent event) {
        evaluate(event);
    }

    /** Synchronous body of {@link #evaluateAsync}; never throws. */
    void evaluate(TokenEvent event) {
        if (event == null || event.type() == null) return;
        try {
            if (event.type().isHousehold()) evaluateHousehold(event);
            else evaluateUser(event);
        } catch (Exception e) {
            log.warn("token evaluation failed ({}): {}", event.type(), e.toString());
        }
    }

    private void evaluateHousehold(TokenEvent event) {
        String householdId = event.householdId();
        if (householdId == null || householdId.isBlank()) return;
        String actor = normalize(event.actorEmail());
        // Only a member's action can earn a household its tokens.
        if (actor == null || !householdAccess.canReadHousehold(actor, householdId)) return;

        Set<TokenKey> met = householdCriteria.met(householdId);
        for (TokenKey key : met) {
            if (key == TokenKey.HOUSEHOLD_READY || TokenCatalog.get(key).scope() != TokenScope.HOUSEHOLD) continue;
            awardHousehold(householdId, key, actor, event);
        }

        boolean ready = HOUSEHOLD_READY_PARTS.stream()
                .allMatch(k -> householdLedger.existsByHouseholdIdAndTokenKey(householdId, k.name()));
        if (ready) awardHousehold(householdId, TokenKey.HOUSEHOLD_READY, actor, event);
    }

    private void awardHousehold(String householdId, TokenKey key, String actor, TokenEvent event) {
        try {
            awards.awardHousehold(householdId, key, actor, event.type().name(), event.sourceId(), Map.of())
                    .ifPresent(notifier::householdAwarded);
        } catch (Exception e) {
            log.warn("household token {} failed for {}: {}", key, householdId, e.toString());
        }
    }

    private void evaluateUser(TokenEvent event) {
        for (UserTokenCriteria.Candidate c : userCriteria.candidates(event)) {
            try {
                if (c == null || c.key() == null || TokenCatalog.get(c.key()).scope() != TokenScope.USER) continue;
                awards.awardUser(c.email(), c.key(), c.sourceType(), c.sourceId(), c.metadata())
                        .ifPresent(notifier::userAwarded);
            } catch (Exception e) {
                log.warn("user token {} failed: {}", c == null ? null : c.key(), e.toString());
            }
        }
    }

    private static String normalize(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
