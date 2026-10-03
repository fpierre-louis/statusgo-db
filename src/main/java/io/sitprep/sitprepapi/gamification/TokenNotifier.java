package io.sitprep.sitprepapi.gamification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.service.NotificationService;
import io.sitprep.sitprepapi.service.PushPolicyService;
import io.sitprep.sitprepapi.service.PushPolicyService.Category;
import io.sitprep.sitprepapi.service.PushPolicyService.Lane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tells people about a new award — in the inbox only. Never a push: the row is
 * written by {@link NotificationService#logInboxOnly}, which has no FCM path.
 * The live surface is the app's own quiet toast, which reads
 * {@code GET /api/me/tokens}.
 */
@Component
public class TokenNotifier {

    private static final Logger log = LoggerFactory.getLogger(TokenNotifier.class);

    static final String TARGET_URL = "/profile?tab=tokens";

    private final NotificationService notifications;
    private final PushPolicyService pushPolicy;
    private final GroupRepo groupRepo;
    private final ObjectMapper objectMapper;
    private final TransactionOperations readTx;

    @Autowired
    public TokenNotifier(NotificationService notifications,
                         PushPolicyService pushPolicy,
                         GroupRepo groupRepo,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager txManager) {
        this(notifications, pushPolicy, groupRepo, objectMapper, readOnly(txManager));
    }

    TokenNotifier(NotificationService notifications,
                  PushPolicyService pushPolicy,
                  GroupRepo groupRepo,
                  ObjectMapper objectMapper,
                  TransactionOperations readTx) {
        this.notifications = notifications;
        this.pushPolicy = pushPolicy;
        this.groupRepo = groupRepo;
        this.objectMapper = objectMapper;
        this.readTx = readTx;
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager txManager) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setReadOnly(true);
        return t;
    }

    public void userAwarded(UserTokenLedger award) {
        if (award == null) return;
        TokenDefinition def = TokenCatalog.forStoredKey(award.getTokenKey());
        if (def == null) return;
        send(award.getUserEmail(), def, TokenReadService.USER_PREFIX + award.getId());
    }

    /** One inbox row per current member of the household. */
    public void householdAwarded(HouseholdTokenLedger award) {
        if (award == null) return;
        TokenDefinition def = TokenCatalog.forStoredKey(award.getTokenKey());
        if (def == null) return;
        String awardId = TokenReadService.HOUSEHOLD_PREFIX + award.getId();
        for (String member : membersOf(award.getHouseholdId())) {
            send(member, def, awardId);
        }
    }

    /**
     * Member emails, copied out of a read-only transaction ({@code memberEmails}
     * is a lazy collection) so the inbox writes that follow are not inside a
     * read-only transaction, where Postgres would refuse them.
     */
    private List<String> membersOf(String householdId) {
        try {
            List<String> members = readTx.execute(status -> {
                Group g = householdId == null ? null : groupRepo.findById(householdId).orElse(null);
                if (g == null || g.getMemberEmails() == null) return List.<String>of();
                Set<String> unique = new LinkedHashSet<>();
                g.getMemberEmails().stream().filter(Objects::nonNull)
                        .map(e -> e.trim().toLowerCase(Locale.ROOT))
                        .filter(e -> !e.isEmpty())
                        .forEach(unique::add);
                return new ArrayList<>(unique);
            });
            return members == null ? List.of() : members;
        } catch (Exception e) {
            log.warn("token notify: members of {} not readable: {}", householdId, e.toString());
            return List.of();
        }
    }

    private void send(String email, TokenDefinition def, String awardId) {
        try {
            // Lane B, or nothing: C (inbox switched off) and DROP (category muted)
            // both mean no row.
            if (pushPolicy.evaluate(email, Category.TOKEN_UNLOCKED, null) != Lane.B) return;
            notifications.logInboxOnly(email, NotificationService.TYPE_TOKEN_UNLOCKED,
                    def.name() + " earned", def.description(), awardId, TARGET_URL,
                    payload(def, awardId), Category.TOKEN_UNLOCKED);
        } catch (Exception e) {
            log.warn("token notify failed (to={}, token={}): {}", email, def.key(), e.toString());
        }
    }

    private String payload(TokenDefinition def, String awardId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("awardId", awardId);
        m.put("tokenKey", def.key().name());
        m.put("scope", def.scope().name());
        m.put("iconKey", def.iconKey());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }
}
