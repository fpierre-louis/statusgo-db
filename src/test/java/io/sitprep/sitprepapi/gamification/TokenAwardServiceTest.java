package io.sitprep.sitprepapi.gamification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1: awards are idempotent and never throw. The real idempotency is the
 * unique index + ON CONFLICT DO NOTHING; these tests pin the contract on top of
 * it — an award is returned only for the call whose insert created the row.
 */
class TokenAwardServiceTest {

    private UserTokenLedgerRepo userLedger;
    private HouseholdTokenLedgerRepo householdLedger;
    private TokenAwardService service;

    @BeforeEach
    void setUp() {
        userLedger = mock(UserTokenLedgerRepo.class);
        householdLedger = mock(HouseholdTokenLedgerRepo.class);
        service = new TokenAwardService(userLedger, householdLedger, new ObjectMapper(),
                TransactionOperations.withoutTransaction());
    }

    @Test
    void aHundredEvaluationsReturnOneAward() {
        // Simulates the database: the first insert creates the row, every later
        // one hits the conflict and inserts nothing.
        AtomicInteger rows = new AtomicInteger();
        when(userLedger.insertIfAbsent(anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(inv -> rows.getAndIncrement() == 0 ? 1 : 0);
        UserTokenLedger row = new UserTokenLedger();
        row.setId(7L);
        when(userLedger.findByUserEmailAndTokenKey("ana@x.com", "HELPFUL_QUESTION")).thenReturn(Optional.of(row));

        int awards = 0;
        for (int i = 0; i < 100; i++) {
            if (service.awardUser("Ana@X.com ", TokenKey.HELPFUL_QUESTION, "ASK_QUESTION_CREATED", "42", Map.of())
                    .isPresent()) awards++;
        }
        assertThat(awards).isEqualTo(1);
        assertThat(rows.get()).isEqualTo(100);
    }

    @Test
    void emailIsLowerCasedAndMetadataSerialised() {
        when(userLedger.insertIfAbsent(anyString(), anyString(), any(), any(), anyString())).thenReturn(0);
        service.awardUser(" Ana@X.com", TokenKey.GROUND_TRUTH, "MAP_CONFIRMED", "9", Map.of("targetType", "osm"));
        verify(userLedger).insertIfAbsent("ana@x.com", "GROUND_TRUTH", "MAP_CONFIRMED", "9", "{\"targetType\":\"osm\"}");
    }

    @Test
    void aDatabaseFailureIsSwallowedNotThrown() {
        when(userLedger.insertIfAbsent(anyString(), anyString(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));
        assertThat(service.awardUser("ana@x.com", TokenKey.DRILL_CREW, "X", "1", null)).isEmpty();

        when(householdLedger.insertIfAbsent(anyString(), anyString(), any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));
        assertThat(service.awardHousehold("hh-1", TokenKey.DRILL_CREW, "ana@x.com", "X", "1", null)).isEmpty();
    }

    @Test
    void householdAwardReturnsOnlyOnInsert() {
        HouseholdTokenLedger row = new HouseholdTokenLedger();
        row.setId(3L);
        when(householdLedger.findByHouseholdIdAndTokenKey("hh-1", "DRILL_CREW")).thenReturn(Optional.of(row));

        when(householdLedger.insertIfAbsent(eq("hh-1"), eq("DRILL_CREW"), eq("ana@x.com"), any(), any(), anyString()))
                .thenReturn(1);
        assertThat(service.awardHousehold("hh-1", TokenKey.DRILL_CREW, "Ana@x.com", "DRILL_COMPLETED", "go-bag", null))
                .contains(row);

        when(householdLedger.insertIfAbsent(eq("hh-1"), eq("DRILL_CREW"), eq("ana@x.com"), any(), any(), anyString()))
                .thenReturn(0);
        assertThat(service.awardHousehold("hh-1", TokenKey.DRILL_CREW, "ana@x.com", "DRILL_COMPLETED", "go-bag", null))
                .isEmpty();
    }

    @Test
    void blankSubjectsNeverReachTheDatabase() {
        assertThat(service.awardUser(" ", TokenKey.DRILL_CREW, null, null, null)).isEmpty();
        assertThat(service.awardHousehold(null, TokenKey.DRILL_CREW, null, null, null, null)).isEmpty();
        verify(userLedger, never()).insertIfAbsent(any(), any(), any(), any(), any());
        verify(householdLedger, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    }
}
