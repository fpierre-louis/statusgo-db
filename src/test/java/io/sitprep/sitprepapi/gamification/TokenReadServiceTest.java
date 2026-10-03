package io.sitprep.sitprepapi.gamification;

import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.gamification.TokenDtos.Award;
import io.sitprep.sitprepapi.gamification.TokenDtos.TokensResponse;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import io.sitprep.sitprepapi.service.HouseholdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T1: GET /api/me/tokens shape, household scoping, and who may mark what seen. */
class TokenReadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T18:00:00Z");
    private static final String ME = "ana@x.com";
    private static final String HH = "hh-1";

    private UserTokenLedgerRepo userLedger;
    private HouseholdTokenLedgerRepo householdLedger;
    private HouseholdTokenSeenRepo householdSeen;
    private HouseholdResolver resolver;
    private HouseholdAccessService access;
    private GroupRepo groupRepo;
    private UserInfoRepo userInfoRepo;
    private TokenReadService service;

    @BeforeEach
    void setUp() {
        userLedger = mock(UserTokenLedgerRepo.class);
        householdLedger = mock(HouseholdTokenLedgerRepo.class);
        householdSeen = mock(HouseholdTokenSeenRepo.class);
        resolver = mock(HouseholdResolver.class);
        access = mock(HouseholdAccessService.class);
        groupRepo = mock(GroupRepo.class);
        userInfoRepo = mock(UserInfoRepo.class);
        service = new TokenReadService(userLedger, householdLedger, householdSeen, resolver, access,
                groupRepo, userInfoRepo, Clock.fixed(NOW, ZoneOffset.UTC));
        when(groupRepo.findByMemberEmail(anyString())).thenReturn(List.of());
    }

    private static UserTokenLedger userRow(long id, String key, Instant seenAt) {
        UserTokenLedger r = new UserTokenLedger();
        r.setId(id);
        r.setUserEmail(ME);
        r.setTokenKey(key);
        r.setEarnedAt(NOW.minus(Duration.ofHours(1)));
        r.setSeenAt(seenAt);
        return r;
    }

    private static HouseholdTokenLedger householdRow(long id, String key, String by, Instant earnedAt) {
        HouseholdTokenLedger r = new HouseholdTokenLedger();
        r.setId(id);
        r.setHouseholdId(HH);
        r.setTokenKey(key);
        r.setEarnedByEmail(by);
        r.setEarnedAt(earnedAt);
        return r;
    }

    @Test
    void returnsCatalogEarnedTokensAndUnseenUnlocks() {
        when(userLedger.findByUserEmailOrderByEarnedAtDesc(ME)).thenReturn(List.of(
                userRow(1, "HELPFUL_QUESTION", null),
                userRow(2, "GROUND_TRUTH", NOW)));
        when(resolver.baseHouseholdIdFor(ME)).thenReturn(HH);
        when(access.canReadHousehold(ME, HH)).thenReturn(true);
        when(householdLedger.findByHouseholdIdOrderByEarnedAtDesc(HH)).thenReturn(List.of(
                householdRow(10, "DRILL_CREW", "dana@x.com", NOW.minus(Duration.ofDays(1))),
                householdRow(11, "MEETING_POINT", ME, NOW.minus(Duration.ofDays(30)))));
        when(householdSeen.findSeenAwardIds(eq(ME), anyCollection())).thenReturn(List.of());
        UserInfo dana = new UserInfo();
        dana.setUserEmail("dana@x.com");
        dana.setUserFirstName("Dana");
        when(userInfoRepo.findByUserEmailLowerIn(anyCollection())).thenReturn(List.of(dana));

        TokensResponse r = service.forViewer(ME);

        assertThat(r.catalog()).hasSize(15);
        assertThat(r.householdId()).isEqualTo(HH);
        assertThat(r.userTokens()).extracting(Award::id).containsExactly("user:1", "user:2");
        Award drill = r.householdTokens().get(0);
        assertThat(drill.id()).isEqualTo("household:10");
        assertThat(drill.earnedByName()).isEqualTo("Dana");
        assertThat(drill.earnedByYou()).isFalse();
        assertThat(r.householdTokens().get(1).earnedByYou()).isTrue();
        // Unseen: the personal one, and the household one inside the 14-day window
        // — not the 30-day-old Meeting Point.
        assertThat(r.recentUnseen()).extracting(Award::id).containsExactly("user:1", "household:10");
    }

    @Test
    void noHouseholdTokensWhenTheViewerIsNotAMemberOfTheResolvedHousehold() {
        when(userLedger.findByUserEmailOrderByEarnedAtDesc(ME)).thenReturn(List.of());
        when(resolver.baseHouseholdIdFor(ME)).thenReturn(HH);
        when(access.canReadHousehold(ME, HH)).thenReturn(false);

        TokensResponse r = service.forViewer(ME);

        assertThat(r.householdId()).isNull();
        assertThat(r.householdTokens()).isEmpty();
        verify(householdLedger, never()).findByHouseholdIdOrderByEarnedAtDesc(anyString());
    }

    @Test
    void aRetiredTokenKeyIsDroppedFromTheResponse() {
        when(userLedger.findByUserEmailOrderByEarnedAtDesc(ME)).thenReturn(List.of(userRow(1, "PLAN_KEPT_FRESH", null)));
        assertThat(service.forViewer(ME).userTokens()).isEmpty();
    }

    @Test
    void markSeenTouchesOnlyTheViewersOwnAwards() {
        when(userLedger.markSeen(eq(ME), anyCollection())).thenReturn(1);
        HouseholdTokenLedger mine = householdRow(10, "DRILL_CREW", ME, NOW);
        HouseholdTokenLedger theirs = householdRow(20, "DRILL_CREW", "x@y.com", NOW);
        theirs.setHouseholdId("hh-other");
        when(householdLedger.findAllById(anyCollection())).thenReturn(List.of(mine, theirs));
        when(access.canReadHousehold(ME, HH)).thenReturn(true);
        when(access.canReadHousehold(ME, "hh-other")).thenReturn(false);
        when(householdSeen.insertIfAbsent(anyLong(), anyString())).thenReturn(1);

        int marked = service.markSeen(" Ana@X.com", List.of("user:1", "household:10", "household:20", "junk", "user:abc"));

        assertThat(marked).isEqualTo(2);
        // The email clause in markSeen is the ownership check for user awards.
        verify(userLedger).markSeen(ME, Set.of(1L));
        verify(householdSeen).insertIfAbsent(10L, ME);
        verify(householdSeen, never()).insertIfAbsent(eq(20L), any());
    }

    @Test
    void markSeenWithNothingDoesNothing() {
        assertThat(service.markSeen(ME, List.of())).isZero();
        assertThat(service.markSeen(ME, null)).isZero();
        verify(userLedger, never()).markSeen(any(), any());
    }
}
