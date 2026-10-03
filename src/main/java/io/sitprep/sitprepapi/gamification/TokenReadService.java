package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.gamification.TokenDtos.Award;
import io.sitprep.sitprepapi.gamification.TokenDtos.CatalogEntry;
import io.sitprep.sitprepapi.gamification.TokenDtos.TokensResponse;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads a viewer's tokens and records which unlocks they have seen.
 *
 * <p>Self only: there is no public read in v1 (tokens are private by default —
 * gamification_social_gameplan.md). Household tokens come from the viewer's
 * base household, and only while the viewer is a member of it.</p>
 */
@Service
public class TokenReadService {

    static final String USER_PREFIX = "user:";
    static final String HOUSEHOLD_PREFIX = "household:";

    /**
     * How far back an unseen household award still toasts. Bounds what a member
     * who joined later is told about — they see the token on the tab, but are
     * not greeted with an "earned" toast for something done months before.
     */
    static final Duration HOUSEHOLD_UNSEEN_WINDOW = Duration.ofDays(14);

    private final UserTokenLedgerRepo userLedger;
    private final HouseholdTokenLedgerRepo householdLedger;
    private final HouseholdTokenSeenRepo householdSeen;
    private final HouseholdResolver householdResolver;
    private final HouseholdAccessService householdAccess;
    private final GroupRepo groupRepo;
    private final UserInfoRepo userInfoRepo;
    private final Clock clock;

    @Autowired
    public TokenReadService(UserTokenLedgerRepo userLedger,
                            HouseholdTokenLedgerRepo householdLedger,
                            HouseholdTokenSeenRepo householdSeen,
                            HouseholdResolver householdResolver,
                            HouseholdAccessService householdAccess,
                            GroupRepo groupRepo,
                            UserInfoRepo userInfoRepo) {
        this(userLedger, householdLedger, householdSeen, householdResolver, householdAccess,
                groupRepo, userInfoRepo, Clock.systemUTC());
    }

    TokenReadService(UserTokenLedgerRepo userLedger,
                     HouseholdTokenLedgerRepo householdLedger,
                     HouseholdTokenSeenRepo householdSeen,
                     HouseholdResolver householdResolver,
                     HouseholdAccessService householdAccess,
                     GroupRepo groupRepo,
                     UserInfoRepo userInfoRepo,
                     Clock clock) {
        this.userLedger = userLedger;
        this.householdLedger = householdLedger;
        this.householdSeen = householdSeen;
        this.householdResolver = householdResolver;
        this.householdAccess = householdAccess;
        this.groupRepo = groupRepo;
        this.userInfoRepo = userInfoRepo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TokensResponse forViewer(String rawEmail) {
        String email = normalize(rawEmail);
        List<CatalogEntry> catalog = TokenCatalog.all().stream().map(CatalogEntry::of).toList();

        List<Award> userTokens = userLedger.findByUserEmailOrderByEarnedAtDesc(email).stream()
                .filter(r -> TokenCatalog.forStoredKey(r.getTokenKey()) != null)
                .map(r -> new Award(USER_PREFIX + r.getId(), TokenScope.USER.name(), r.getTokenKey(),
                        r.getEarnedAt(), r.getSeenAt() != null, null, false))
                .toList();

        String householdId = householdFor(email);
        List<Award> householdTokens = householdId == null ? List.of() : householdAwards(email, householdId);

        Instant cutoff = clock.instant().minus(HOUSEHOLD_UNSEEN_WINDOW);
        List<Award> recentUnseen = new ArrayList<>();
        userTokens.stream().filter(a -> !a.seen()).forEach(recentUnseen::add);
        householdTokens.stream()
                .filter(a -> !a.seen() && a.earnedAt() != null && a.earnedAt().isAfter(cutoff))
                .forEach(recentUnseen::add);
        recentUnseen.sort(Comparator.comparing(Award::earnedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        return new TokensResponse(catalog, userTokens, householdTokens, List.copyOf(recentUnseen), householdId);
    }

    /**
     * Marks the viewer's unlocks seen. A user award counts only if it is the
     * viewer's own; a household award only if the viewer is a member of its
     * household. Anything else is ignored rather than rejected, so the endpoint
     * can't be used to probe which award ids exist.
     */
    @Transactional
    public int markSeen(String rawEmail, List<String> awardIds) {
        if (awardIds == null || awardIds.isEmpty()) return 0;
        String email = normalize(rawEmail);
        Set<Long> userIds = new LinkedHashSet<>();
        Set<Long> householdIds = new LinkedHashSet<>();
        for (String raw : awardIds) {
            if (raw == null) continue;
            Long id;
            if (raw.startsWith(USER_PREFIX) && (id = parseId(raw.substring(USER_PREFIX.length()))) != null) {
                userIds.add(id);
            } else if (raw.startsWith(HOUSEHOLD_PREFIX)
                    && (id = parseId(raw.substring(HOUSEHOLD_PREFIX.length()))) != null) {
                householdIds.add(id);
            }
        }

        int marked = userIds.isEmpty() ? 0 : userLedger.markSeen(email, userIds);

        if (!householdIds.isEmpty()) {
            Map<String, Boolean> memberOf = new HashMap<>();
            for (HouseholdTokenLedger award : householdLedger.findAllById(householdIds)) {
                boolean member = memberOf.computeIfAbsent(award.getHouseholdId(),
                        h -> householdAccess.canReadHousehold(email, h));
                if (member) marked += householdSeen.insertIfAbsent(award.getId(), email);
            }
        }
        return marked;
    }

    private List<Award> householdAwards(String email, String householdId) {
        List<HouseholdTokenLedger> rows = householdLedger.findByHouseholdIdOrderByEarnedAtDesc(householdId).stream()
                .filter(r -> TokenCatalog.forStoredKey(r.getTokenKey()) != null)
                .toList();
        if (rows.isEmpty()) return List.of();

        Set<Long> seen = new HashSet<>(householdSeen.findSeenAwardIds(email,
                rows.stream().map(HouseholdTokenLedger::getId).toList()));

        Set<String> earners = new HashSet<>();
        rows.forEach(r -> { if (r.getEarnedByEmail() != null) earners.add(r.getEarnedByEmail().toLowerCase(Locale.ROOT)); });
        Map<String, String> firstNames = new HashMap<>();
        if (!earners.isEmpty()) {
            for (UserInfo u : userInfoRepo.findByUserEmailLowerIn(earners)) {
                if (u.getUserEmail() != null && u.getUserFirstName() != null && !u.getUserFirstName().isBlank()) {
                    firstNames.put(u.getUserEmail().toLowerCase(Locale.ROOT), u.getUserFirstName().trim());
                }
            }
        }

        return rows.stream().map(r -> {
            String by = r.getEarnedByEmail() == null ? null : r.getEarnedByEmail().toLowerCase(Locale.ROOT);
            return new Award(HOUSEHOLD_PREFIX + r.getId(), TokenScope.HOUSEHOLD.name(), r.getTokenKey(),
                    r.getEarnedAt(), seen.contains(r.getId()),
                    by == null ? null : firstNames.get(by),
                    email.equals(by));
        }).toList();
    }

    /**
     * The viewer's base household, else the first household they belong to (the
     * same fallback {@code MeService} anchors the dashboard to) — and only one the
     * viewer is a member of right now.
     */
    String householdFor(String email) {
        String base = householdResolver.baseHouseholdIdFor(email);
        if (base != null && !base.isBlank() && householdAccess.canReadHousehold(email, base)) return base;
        return groupRepo.findByMemberEmail(email).stream()
                .filter(Objects::nonNull)
                .filter(g -> "Household".equalsIgnoreCase(g.getGroupType()))
                .map(Group::getGroupId)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static Long parseId(String s) {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
