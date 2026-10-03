package io.sitprep.sitprepapi.gamification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Writes token awards. The only writer of either ledger.
 *
 * <p><b>Idempotent by construction:</b> the write is
 * {@code INSERT … ON CONFLICT DO NOTHING}; an award is returned only when that
 * call created the row. Evaluate a subject a hundred times and one row exists
 * and one award is returned — so whoever notifies on the return value notifies
 * once.</p>
 *
 * <p><b>Never throws.</b> Each award runs in its own {@code REQUIRES_NEW}
 * transaction and the catch sits OUTSIDE that boundary: a failure inside a
 * transactional method marks it rollback-only, and the proxy would then throw
 * on the way out. A token must never fail, block or roll back the action that
 * earned it.</p>
 */
@Service
public class TokenAwardService {

    private static final Logger log = LoggerFactory.getLogger(TokenAwardService.class);

    private final UserTokenLedgerRepo userLedger;
    private final HouseholdTokenLedgerRepo householdLedger;
    private final ObjectMapper objectMapper;
    private final TransactionOperations tx;

    @Autowired
    public TokenAwardService(UserTokenLedgerRepo userLedger,
                             HouseholdTokenLedgerRepo householdLedger,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager txManager) {
        this(userLedger, householdLedger, objectMapper, requiresNew(txManager));
    }

    /** Test seam: unit tests pass {@link TransactionOperations#withoutTransaction()}. */
    TokenAwardService(UserTokenLedgerRepo userLedger,
                      HouseholdTokenLedgerRepo householdLedger,
                      ObjectMapper objectMapper,
                      TransactionOperations tx) {
        this.userLedger = userLedger;
        this.householdLedger = householdLedger;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager txManager) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /** Awards a person's token. Present only if this call created the award. */
    public Optional<UserTokenLedger> awardUser(String email, TokenKey key,
                                               String sourceType, String sourceId,
                                               Map<String, Object> metadata) {
        if (email == null || email.isBlank() || key == null) return Optional.empty();
        String who = email.trim().toLowerCase(Locale.ROOT);
        try {
            Optional<UserTokenLedger> award = tx.execute(status -> {
                int inserted = userLedger.insertIfAbsent(who, key.name(), sourceType, sourceId, json(metadata));
                return inserted == 1
                        ? userLedger.findByUserEmailAndTokenKey(who, key.name())
                        : Optional.<UserTokenLedger>empty();
            });
            return award == null ? Optional.empty() : award;
        } catch (Exception e) {
            log.warn("token award failed (user={}, token={}): {}", who, key, e.toString());
            return Optional.empty();
        }
    }

    /** Awards a household's token. Present only if this call created the award. */
    public Optional<HouseholdTokenLedger> awardHousehold(String householdId, TokenKey key,
                                                         String earnedByEmail,
                                                         String sourceType, String sourceId,
                                                         Map<String, Object> metadata) {
        if (householdId == null || householdId.isBlank() || key == null) return Optional.empty();
        String by = earnedByEmail == null ? null : earnedByEmail.trim().toLowerCase(Locale.ROOT);
        try {
            Optional<HouseholdTokenLedger> award = tx.execute(status -> {
                int inserted = householdLedger.insertIfAbsent(householdId, key.name(), by,
                        sourceType, sourceId, json(metadata));
                return inserted == 1
                        ? householdLedger.findByHouseholdIdAndTokenKey(householdId, key.name())
                        : Optional.<HouseholdTokenLedger>empty();
            });
            return award == null ? Optional.empty() : award;
        } catch (Exception e) {
            log.warn("token award failed (household={}, token={}): {}", householdId, key, e.toString());
            return Optional.empty();
        }
    }

    private String json(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }
}
