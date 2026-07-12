package io.sitprep.sitprepapi.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.exception.InvalidTokenException;
import io.sitprep.sitprepapi.repo.GhostDemandVoteRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.resource.GhostTenantResource;
import io.sitprep.sitprepapi.resource.PublicOutreachResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ghost Tenant Phase 3 — demand-signal endpoint + outreach worker. Runs on H2
 * (@ActiveProfiles("test")); @Transactional rolls each test back so the global
 * {@code processOutreach()} sweep only ever sees the group(s) that test created.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GhostTenantFeatureTest {

    @Autowired GroupRepo groupRepo;
    @Autowired GhostDemandVoteRepo voteRepo;
    @Autowired GhostTenantService ghostTenantService;
    @Autowired GhostTenantResource ghostTenantResource;
    @Autowired OutreachTokenService outreachTokenService;
    @Autowired PublicOutreachResource publicOutreachResource;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private Group newGroup(String claimState) {
        Group g = new Group();
        g.setGroupId("g-" + UUID.randomUUID());
        g.setGroupName("City of Testville");
        g.setClaimState(claimState);
        return groupRepo.saveAndFlush(g);
    }

    private Group reload(String id) {
        return groupRepo.findById(id).orElseThrow();
    }

    private void authAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList()));
    }

    // ── Demand signal ──────────────────────────────────────────────────────

    @Test
    void demandSignal_increments_once_per_distinct_resident() {
        Group g = newGroup("GHOST");

        int afterAlice1 = ghostTenantService.recordDemandSignal(g.getGroupId(), "alice@example.com");
        int afterAlice2 = ghostTenantService.recordDemandSignal(g.getGroupId(), "ALICE@example.com"); // dup (case-insensitive)
        int afterBob    = ghostTenantService.recordDemandSignal(g.getGroupId(), "bob@example.com");

        assertThat(afterAlice1).isEqualTo(1);
        assertThat(afterAlice2).isEqualTo(1); // idempotent — same resident can't inflate the signal
        assertThat(afterBob).isEqualTo(2);
        assertThat(reload(g.getGroupId()).getGhostDemandSignal()).isEqualTo(2);
    }

    @Test
    void demandSignal_rejected_for_non_ghost_group() {
        Group g = newGroup("CLAIMED");
        assertThatThrownBy(() -> ghostTenantService.recordDemandSignal(g.getGroupId(), "a@example.com"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reload(g.getGroupId()).getGhostDemandSignal()).isZero();
    }

    @Test
    void demandSignal_endpoint_increments_for_authenticated_caller() {
        Group g = newGroup("GHOST");
        authAs("carol@example.com");

        ResponseEntity<?> resp = ghostTenantResource.demandSignal(g.getGroupId());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(reload(g.getGroupId()).getGhostDemandSignal()).isEqualTo(1);
    }

    // ── Outreach worker ────────────────────────────────────────────────────

    @Test
    void worker_contacts_eligible_ghost_and_advances_state() {
        Group g = newGroup("GHOST");
        g.setGhostDemandSignal(5);
        g.setOfficialContactEmail("mayor@testville.gov");
        groupRepo.saveAndFlush(g);

        int processed = ghostTenantService.processOutreach();

        assertThat(processed).isEqualTo(1);
        Group after = reload(g.getGroupId());
        assertThat(after.getOutreachCount()).isEqualTo(1);
        assertThat(after.getLastOutreachDate()).isNotNull();
    }

    @Test
    void worker_skips_optedOut_capped_recent_and_zeroDemand() {
        // (a) opted out
        Group optedOut = newGroup("GHOST");
        optedOut.setGhostDemandSignal(9);
        optedOut.setOfficialContactEmail("a@gov");
        optedOut.setOutreachOptOut(true);
        // (b) at lifetime cap (3)
        Group capped = newGroup("GHOST");
        capped.setGhostDemandSignal(9);
        capped.setOfficialContactEmail("b@gov");
        capped.setOutreachCount(3);
        // (c) contacted recently (within the weekly cadence)
        Group recent = newGroup("GHOST");
        recent.setGhostDemandSignal(9);
        recent.setOfficialContactEmail("c@gov");
        recent.setLastOutreachDate(Instant.now().minus(Duration.ofDays(2)));
        // (d) no demand yet
        Group noDemand = newGroup("GHOST");
        noDemand.setGhostDemandSignal(0);
        noDemand.setOfficialContactEmail("d@gov");
        // (e) demand but no official contact to email
        Group noContact = newGroup("GHOST");
        noContact.setGhostDemandSignal(9);
        groupRepo.saveAll(java.util.List.of(optedOut, capped, recent, noDemand, noContact));
        groupRepo.flush();

        int processed = ghostTenantService.processOutreach();

        assertThat(processed).isZero();
        assertThat(reload(optedOut.getGroupId()).getOutreachCount()).isZero();
        assertThat(reload(capped.getGroupId()).getOutreachCount()).isEqualTo(3); // unchanged
        assertThat(reload(recent.getGroupId()).getOutreachCount()).isZero();
        assertThat(reload(noDemand.getGroupId()).getLastOutreachDate()).isNull();
    }

    @Test
    void worker_stops_at_lifetime_cap_after_repeated_runs() {
        Group g = newGroup("GHOST");
        g.setGhostDemandSignal(4);
        g.setOfficialContactEmail("clerk@testville.gov");
        // backdate so the weekly cadence never blocks a subsequent run
        g.setLastOutreachDate(Instant.now().minus(Duration.ofDays(30)));
        groupRepo.saveAndFlush(g);

        // Run 1 → contact #1, then backdate again so cadence allows run 2, etc.
        for (int i = 0; i < 5; i++) {
            ghostTenantService.processOutreach();
            Group after = reload(g.getGroupId());
            if (after.getOutreachCount() < 3) {
                after.setLastOutreachDate(Instant.now().minus(Duration.ofDays(30)));
                groupRepo.saveAndFlush(after);
            }
        }

        assertThat(reload(g.getGroupId()).getOutreachCount()).isEqualTo(3); // never exceeds LIFETIME_CAP
    }

    // ── Tokenized one-click opt-out ─────────────────────────────────────────

    @Test
    void optOutToken_roundtrips_to_its_groupId() {
        Group g = newGroup("GHOST");
        String token = outreachTokenService.generateToken(g.getGroupId(), "mayor@testville.gov");
        assertThat(outreachTokenService.validateAndExtractGroupId(token)).isEqualTo(g.getGroupId());
    }

    @Test
    void optOut_publicEndpoint_setsFlagTrue() {
        Group g = newGroup("GHOST");
        g.setOfficialContactEmail("mayor@testville.gov");
        g.setGhostDemandSignal(3);
        groupRepo.saveAndFlush(g);

        String token = outreachTokenService.generateToken(g.getGroupId(), "mayor@testville.gov");
        ResponseEntity<String> resp = publicOutreachResource.optOut(token);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(reload(g.getGroupId()).isOutreachOptOut()).isTrue();
    }

    @Test
    void optOut_publicEndpoint_isIdempotent() {
        Group g = newGroup("GHOST");
        g.setOfficialContactEmail("mayor@testville.gov");
        groupRepo.saveAndFlush(g);
        String token = outreachTokenService.generateToken(g.getGroupId(), "mayor@testville.gov");

        assertThat(publicOutreachResource.optOut(token).getStatusCode().value()).isEqualTo(200);
        assertThat(publicOutreachResource.optOut(token).getStatusCode().value()).isEqualTo(200); // 2nd click still OK
        assertThat(reload(g.getGroupId()).isOutreachOptOut()).isTrue();
    }

    @Test
    void optOut_tamperedToken_isRejected_andLeavesFlagFalse() {
        Group g = newGroup("GHOST");
        g.setOfficialContactEmail("mayor@testville.gov");
        groupRepo.saveAndFlush(g);

        String valid = outreachTokenService.generateToken(g.getGroupId(), "mayor@testville.gov");
        // Flip the final signature char — invalidates the HMAC (guaranteed different).
        char last = valid.charAt(valid.length() - 1);
        String tampered = valid.substring(0, valid.length() - 1) + (last == 'A' ? 'B' : 'A');

        ResponseEntity<String> resp = publicOutreachResource.optOut(tampered);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);   // tamper => 400 Bad Request
        assertThat(reload(g.getGroupId()).isOutreachOptOut()).isFalse(); // and NOT opted out
    }

    @Test
    void optOutToken_forgedWithWrongSecret_throwsInvalidToken() {
        Group g = newGroup("GHOST");
        // A token signed with a secret we don't hold must never validate.
        String forged = JWT.create()
                .withIssuer("sitprep-outreach")
                .withSubject(g.getGroupId())
                .sign(Algorithm.HMAC256("attacker-secret"));

        assertThatThrownBy(() -> outreachTokenService.validateAndExtractGroupId(forged))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void optOutToken_garbageInput_throwsInvalidToken() {
        assertThatThrownBy(() -> outreachTokenService.validateAndExtractGroupId("not-a-real-token"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
