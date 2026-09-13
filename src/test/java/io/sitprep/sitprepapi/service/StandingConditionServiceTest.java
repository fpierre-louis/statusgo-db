package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.sitprep.sitprepapi.domain.HouseholdStandingCondition;
import io.sitprep.sitprepapi.dto.StandingConditionDtos.StandingConditionRequest;
import io.sitprep.sitprepapi.repo.HouseholdStandingConditionRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Household Standing Conditions — the durable household-authored operating
 * state the scenario matrix kept asking for and finding nowhere to put.
 *
 * <p>The load-bearing test in this file is {@code alertExpiryNeverClears}. The
 * boil-water notice disappears from the CAP feed the moment the county stops
 * republishing it, and the household still cannot drink the water. Everything
 * else here guards the edges of that.
 */
class StandingConditionServiceTest {

    private static final String HH = "hh-1";
    private static final String OTHER_HH = "hh-2";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String STRANGER = "stranger@x.com";

    private HouseholdStandingConditionRepo repo;
    private HouseholdAccessService access;
    private StandingConditionService service;
    private List<HouseholdStandingCondition> rows;

    @BeforeEach
    void setUp() {
        repo = mock(HouseholdStandingConditionRepo.class);
        access = mock(HouseholdAccessService.class);
        UserInfoRepo userInfoRepo = mock(UserInfoRepo.class);
        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        service = new StandingConditionService(repo, access, userInfoRepo);

        rows = new ArrayList<>();
        AtomicLong seq = new AtomicLong(1);
        when(repo.save(any(HouseholdStandingCondition.class))).thenAnswer(inv -> {
            HouseholdStandingCondition c = inv.getArgument(0);
            if (c.getId() == null) { c.setId(seq.getAndIncrement()); rows.add(c); }
            return c;
        });
        when(repo.findById(any())).thenAnswer(inv ->
                rows.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByHouseholdIdAndStatusOrderByUpdatedAtDesc(anyString(), anyString()))
                .thenAnswer(inv -> rows.stream()
                        .filter(r -> r.getHouseholdId().equals(inv.getArgument(0)))
                        .filter(r -> r.getStatus().equals(inv.getArgument(1)))
                        .toList());

        // Default: ADMIN can write, MEMBER can read, STRANGER can do neither.
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanAdminHousehold(eq(MEMBER), anyString());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanAdminHousehold(eq(STRANGER), anyString());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requireCanReadHousehold(eq(STRANGER), anyString());
    }

    private StandingConditionRequest req(String cat, String title, String instruction) {
        return new StandingConditionRequest(cat, title, instruction);
    }

    // ── THE INVARIANT ───────────────────────────────────────────────────────

    @Test
    @DisplayName("nothing in the alert pipeline can clear a condition — only a person")
    void alertExpiryNeverClears() {
        var created = service.create(HH, ADMIN,
                req("WATER", "Do not drink the tap water", "Bottled water for drinking and cooking."));
        assertThat(created.active()).isTrue();

        // There is no code path from an alert to this row, by construction:
        // the only transitions to CLEARED are through clear(), which requires a
        // caller. If a future change adds an automatic sweep, this is the test
        // that should stop it.
        long clearingMethods = java.util.Arrays.stream(StandingConditionService.class.getMethods())
                .filter(m -> m.getName().toLowerCase().contains("clear"))
                .count();
        assertThat(clearingMethods).as("exactly one way to clear, and it takes a caller").isEqualTo(1);

        var stillThere = service.activeFor(HH, MEMBER);
        assertThat(stillThere.active()).hasSize(1);
        assertThat(stillThere.active().get(0).title()).isEqualTo("Do not drink the tap water");
    }

    @Test
    @DisplayName("a condition survives indefinitely — it is not coupled to any expiry")
    void hasNoExpiry() {
        service.create(HH, ADMIN, req("WATER", "Boil water notice", null));
        HouseholdStandingCondition row = rows.get(0);
        // No expiresAt field exists to age it out. Scenario 30's fourteen-day
        // advisory needs exactly this.
        assertThat(java.util.Arrays.stream(HouseholdStandingCondition.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("expiresAt", "ttl", "autoClearAt");
        assertThat(row.isActive()).isTrue();
    }

    // ── LIFECYCLE ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("create records actor and timestamps")
    void createRecordsProvenance() {
        var dto = service.create(HH, ADMIN, req("POWER", "Outage 2-6 PM", "Charge phones before 2."));
        assertThat(dto.category()).isEqualTo("POWER");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.updatedByEmail()).isEqualTo(ADMIN);
        assertThat(dto.createdAt()).isNotNull();
        assertThat(dto.updatedAt()).isNotNull();
        assertThat(dto.clearedAt()).isNull();
    }

    @Test
    @DisplayName("clear is a lifecycle transition, not a delete")
    void clearKeepsTheRow() {
        var c = service.create(HH, ADMIN, req("ACCESS", "Main road closed", null));
        var cleared = service.clear(HH, c.id(), ADMIN);

        assertThat(cleared.status()).isEqualTo("CLEARED");
        assertThat(cleared.active()).isFalse();
        assertThat(cleared.clearedByEmail()).isEqualTo(ADMIN);
        assertThat(cleared.clearedAt()).isNotNull();
        // The row survives: "who said this was over, and when" outlives the
        // condition, and deleting it would destroy the only record.
        verify(repo, never()).delete(any());
        verify(repo, never()).deleteById(any());
        assertThat(rows).hasSize(1);
        // And it drops out of the active list.
        assertThat(service.activeFor(HH, MEMBER).active()).isEmpty();
    }

    @Test
    @DisplayName("clearing twice is harmless and does not move the cleared-by")
    void clearIsIdempotent() {
        var c = service.create(HH, ADMIN, req("OTHER", "Something", null));
        var first = service.clear(HH, c.id(), ADMIN);
        var second = service.clear(HH, c.id(), ADMIN);
        assertThat(second.clearedAt()).isEqualTo(first.clearedAt());
    }

    @Test
    @DisplayName("editing a cleared condition reactivates it rather than orphaning a duplicate")
    void updateReactivates() {
        var c = service.create(HH, ADMIN, req("WATER", "Boil water", null));
        service.clear(HH, c.id(), ADMIN);

        var updated = service.update(HH, c.id(), ADMIN, req("WATER", "Do not drink the tap water", "Bottled only."));

        assertThat(updated.status()).isEqualTo("ACTIVE");
        assertThat(updated.clearedAt()).isNull();
        assertThat(updated.title()).isEqualTo("Do not drink the tap water");
        assertThat(rows).as("no duplicate row").hasSize(1);
    }

    @Test
    @DisplayName("no workflow states — this is not a task tracker")
    void hasNoWorkflowStates() {
        // acknowledged / assigned / escalated / snoozed / completed all belong
        // to other systems, and each would pull this toward being one.
        var names = java.util.Arrays.stream(HouseholdStandingCondition.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertThat(names).doesNotContain(
                "acknowledgedAt", "assigneeEmail", "escalatedAt", "snoozedUntil", "completedAt");
    }

    // ── AUTHORIZATION ───────────────────────────────────────────────────────

    @Test
    @DisplayName("any household member may read")
    void memberCanRead() {
        service.create(HH, ADMIN, req("POWER", "Outage", null));
        assertThat(service.activeFor(HH, MEMBER).active()).hasSize(1);
    }

    @Test
    @DisplayName("an ordinary member may not create, edit or clear")
    void memberCannotWrite() {
        assertThatThrownBy(() -> service.create(HH, MEMBER, req("POWER", "x", null)))
                .isInstanceOf(ResponseStatusException.class);
        var c = service.create(HH, ADMIN, req("POWER", "x", null));
        assertThatThrownBy(() -> service.update(HH, c.id(), MEMBER, req("POWER", "y", null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.clear(HH, c.id(), MEMBER))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("a non-household caller is denied entirely")
    void strangerDenied() {
        assertThatThrownBy(() -> service.activeFor(HH, STRANGER))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(HH, STRANGER, req("POWER", "x", null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("an id from another household cannot be addressed")
    void cannotReachAcrossHouseholds() {
        var c = service.create(HH, ADMIN, req("WATER", "Boil water", null));
        assertThatThrownBy(() -> service.clear(OTHER_HH, c.id(), ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such condition");
    }

    // ── VALIDATION ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("category must be one of the small taxonomy")
    void rejectsUnknownCategory() {
        assertThatThrownBy(() -> service.create(HH, ADMIN, req("EARTHQUAKE", "x", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("category");
    }

    @Test
    @DisplayName("category is case-insensitive on the way in, canonical on the way out")
    void normalisesCategory() {
        assertThat(service.create(HH, ADMIN, req("water", "x", null)).category()).isEqualTo("WATER");
    }

    @Test
    @DisplayName("title is required; instruction is optional")
    void validatesText() {
        assertThatThrownBy(() -> service.create(HH, ADMIN, req("WATER", "   ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("title");
        assertThat(service.create(HH, ADMIN, req("WATER", "Boil water", null)).instruction()).isNull();
        assertThat(service.create(HH, ADMIN, req("WATER", "Boil water", "  ")).instruction()).isNull();
    }

    // ── PROJECTION + PRIVACY ────────────────────────────────────────────────

    @Test
    @DisplayName("the plan projection returns only ACTIVE conditions")
    void projectionIsActiveOnly() {
        var a = service.create(HH, ADMIN, req("WATER", "Boil water", null));
        service.create(HH, ADMIN, req("POWER", "Outage", null));
        service.clear(HH, a.id(), ADMIN);

        var projected = service.activeForProjection(HH);
        assertThat(projected).hasSize(1);
        assertThat(projected.get(0).category()).isEqualTo("POWER");
    }

    @Test
    @DisplayName("the public activation contract carries no standing conditions")
    void notOnThePublicContract() {
        // T-77: public APIs use explicit allowlisted recipient DTOs. This is
        // private household operating state and must stay out BY CONSTRUCTION —
        // there is no field to populate, so it cannot leak by omission.
        var names = java.util.Arrays.stream(
                        io.sitprep.sitprepapi.dto.PublicActivationDtos.PublicActivationDto
                                .class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(names).noneMatch(n -> n.toLowerCase().contains("standing")
                || n.toLowerCase().contains("condition"));
    }

    @Test
    @DisplayName("serialized conditions carry no household identifiers beyond what the client needs")
    void serializationIsNarrow() throws Exception {
        var dto = service.create(HH, ADMIN, req("WATER", "Do not drink the tap water", "Bottled only."));
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(dto);
        assertThat(json).doesNotContain("householdId");
        assertThat(json).contains("Do not drink the tap water");
    }

    @Test
    @DisplayName("the list is stamped with an asOf so a cached copy can date itself")
    void listCarriesAsOf() {
        service.create(HH, ADMIN, req("WATER", "Boil water", null));
        var doc = service.activeFor(HH, MEMBER);
        assertThat(doc.asOf()).isNotNull();
        assertThat(doc.asOf()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("known-empty is an empty list, which is different from a failure")
    void knownEmptyIsEmpty() {
        var doc = service.activeFor(HH, MEMBER);
        assertThat(doc.active()).isEmpty();
        // The caller can tell this apart from "could not load" because it got a
        // document at all. The client turns that distinction into copy.
        assertThat(doc.asOf()).isNotNull();
    }
}
