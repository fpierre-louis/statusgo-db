package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EmergencySupportAssignment;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdAccompaniment;
import io.sitprep.sitprepapi.domain.HouseholdClaimInvite;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Person;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.PersonKind;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportAssignmentRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdAccompanimentRepo;
import io.sitprep.sitprepapi.repo.HouseholdClaimInviteRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.HouseholdClaimService.AcceptResult;
import io.sitprep.sitprepapi.service.HouseholdClaimService.ClaimInvite;
import io.sitprep.sitprepapi.service.HouseholdClaimService.ClaimStateException;
import io.sitprep.sitprepapi.service.HouseholdClaimService.Preview;
import io.sitprep.sitprepapi.service.HouseholdClaimService.State;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Household roster EXEC-B — the "claim your spot" token lifecycle and the
 * accept transaction, over the real services on the H2 test schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class HouseholdClaimFlowTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired HouseholdClaimService claims;
    @Autowired HouseholdCompositionService composition;
    @Autowired GroupRepo groups;
    @Autowired UserInfoRepo users;
    @Autowired DemographicRepo demographics;
    @Autowired HouseholdManualMemberRepo manualRepo;
    @Autowired HouseholdMemberBandRepo bandRepo;
    @Autowired HouseholdClaimInviteRepo claimRepo;
    @Autowired HouseholdAccompanimentRepo accompaniments;
    @Autowired EmergencySupportProfileRepo profiles;
    @Autowired EmergencySupportAssignmentRepo assignments;
    @Autowired EmergencyContactGroupRepo contactGroups;

    private String sfx;
    private String owner;
    private String maya;
    private Group hh;
    private HouseholdManualMember mayaRow;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
        owner = email("owner");
        maya = email("maya");
        hh = household(owner, owner);
        user(owner, "Dione", "Pierre-Louis", hh.getGroupId());
        mayaRow = manualRow(hh.getGroupId(), "Maya", HouseholdBand.TEEN);
        demo(hh.getGroupId(), 1, 1, 0, 0);
    }

    // ── mint ────────────────────────────────────────────────────────────

    @Test
    void onlyAnAdminCanMint_andMintingAgainReturnsTheLiveLink() {
        String member = email("member");
        Group g = groups.findByGroupId(hh.getGroupId()).orElseThrow();
        g.getMemberEmails().add(member);
        groups.save(g);

        assertThatThrownBy(() -> claims.mint(hh.getGroupId(), mayaRow.getId(), member))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> claims.mint(hh.getGroupId(), mayaRow.getId(), email("stranger")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        ClaimInvite first = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        assertThat(first.reused()).isFalse();
        assertThat(first.sharePath()).isEqualTo("/claim/" + first.token());
        assertThat(first.token()).hasSizeGreaterThanOrEqualTo(32).doesNotContain("/", "+", "=");
        assertThat(Duration.between(first.issuedAt(), first.expiresAt())).isEqualTo(Duration.ofDays(7));

        ClaimInvite again = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        assertThat(again.reused()).isTrue();
        assertThat(again.token()).isEqualTo(first.token());

        HouseholdCompositionDto dto = composition.compose(hh.getGroupId(), owner);
        Person p = dto.people().stream().filter(x -> x.kind() == PersonKind.MANUAL).findFirst().orElseThrow();
        assertThat(p.claim().pending()).isTrue();
        assertThat(p.claim().expiresAt()).isEqualTo(first.expiresAt());
    }

    @Test
    void anExpiredOpenLinkIsRetired_andReplaced() {
        ClaimInvite first = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        expire(first.token());

        ClaimInvite next = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);

        assertThat(next.token()).isNotEqualTo(first.token());
        assertThat(next.reused()).isFalse();
        assertThat(claimRepo.findById(first.token()).orElseThrow().getRevokedAt()).isNotNull();
    }

    @Test
    void mintingIsRateLimitedPerHousehold() {
        Instant now = Instant.now();
        for (int i = 0; i < HouseholdClaimService.MAX_MINTS_PER_DAY; i++) {
            HouseholdClaimInvite inv = new HouseholdClaimInvite();
            inv.setToken("seed-" + sfx + "-" + i);
            inv.setHouseholdId(hh.getGroupId());
            inv.setManualMemberId("gone-" + i);
            inv.setIssuedByEmail(owner);
            inv.setIssuedAt(now.minusSeconds(60));
            inv.setExpiresAt(now.plus(Duration.ofDays(7)));
            inv.setRevokedAt(now);
            claimRepo.save(inv);
        }
        assertThatThrownBy(() -> claims.mint(hh.getGroupId(), mayaRow.getId(), owner))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void aManualMemberOfAnotherHouseholdIs404() {
        Group other = household(email("x"), email("x"));
        HouseholdManualMember theirs = manualRow(other.getGroupId(), "Theirs", HouseholdBand.KID);
        assertThatThrownBy(() -> claims.mint(hh.getGroupId(), theirs.getId(), owner))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── resolve ─────────────────────────────────────────────────────────

    @Test
    void resolveShowsNamesAndBand_neverAnEmail() {
        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);

        Preview p = claims.resolve(inv.token());

        assertThat(p.state()).isEqualTo(State.OK);
        assertThat(p.householdName()).isEqualTo("Test " + sfx);
        assertThat(p.memberName()).isEqualTo("Maya");
        assertThat(p.band()).isEqualTo(HouseholdBand.TEEN);
        assertThat(p.inviterFirstName()).isEqualTo("Dione");
        assertThat(p.toString()).doesNotContain("@");
    }

    @Test
    void resolveReportsEveryDeadState() {
        assertThat(claims.resolve("nope-" + sfx).state()).isEqualTo(State.NOT_FOUND);

        ClaimInvite expired = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        expire(expired.token());
        assertThat(claims.resolve(expired.token()).state()).isEqualTo(State.EXPIRED);

        ClaimInvite revoked = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        claims.revoke(hh.getGroupId(), mayaRow.getId(), owner);
        assertThat(claims.resolve(revoked.token()).state()).isEqualTo(State.REVOKED);

        ClaimInvite gone = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        manualRepo.deleteById(mayaRow.getId());
        assertThat(claims.resolve(gone.token()).state()).isEqualTo(State.MEMBER_GONE);
        assertThat(claims.resolve(gone.token()).memberName()).isNull();
    }

    @Test
    void aConsumedLinkResolvesAsConsumed() {
        user(maya, "Maya", "Lee", null);
        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        claims.accept(inv.token(), maya);
        assertThat(claims.resolve(inv.token()).state()).isEqualTo(State.CONSUMED);
    }

    // ── accept ──────────────────────────────────────────────────────────

    @Test
    void acceptMovesTheSlotToTheAccount_everyReferenceIncluded() {
        Group solo = household(maya, maya);                  // what provisioning made at sign-up
        user(maya, "Maya", "Lee", solo.getGroupId());
        HouseholdManualMember leo = manualRow(hh.getGroupId(), "Leo", HouseholdBand.KID);
        Demographic d = row(hh);
        d.setKids(1);
        demographics.save(d);

        HouseholdAccompaniment withOwner = accompaniment(hh.getGroupId(), "user", owner, "manual", mayaRow.getId());
        HouseholdAccompaniment mayaWithLeo = accompaniment(hh.getGroupId(), "manual", mayaRow.getId(), "manual", leo.getId());
        EmergencySupportProfile profile = profile(hh.getGroupId(), "manual", mayaRow.getId());
        EmergencySupportAssignment primary = assignment(hh.getGroupId(), "manual", mayaRow.getId(), EmergencySupportAssignment.Role.PRIMARY);
        EmergencySupportAssignment backup = assignment(hh.getGroupId(), "manual", mayaRow.getId(), EmergencySupportAssignment.Role.BACKUP);
        EmergencyContactGroup family = contactGroup(hh.getGroupId(), "Aunt Rose", "manual", mayaRow.getId(), "Maya");

        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        AcceptResult r = claims.accept(inv.token(), " " + maya.toUpperCase() + " ");

        assertThat(r.state()).isEqualTo(State.OK);
        assertThat(r.alreadyClaimed()).isFalse();
        assertThat(r.householdId()).isEqualTo(hh.getGroupId());
        assertThat(r.band()).isEqualTo(HouseholdBand.TEEN);
        assertThat(r.claimedName()).isEqualTo("Maya");
        assertThat(r.baseChanged()).isTrue();
        assertThat(r.baseHouseholdId()).isEqualTo(hh.getGroupId());

        // membership + band
        assertThat(groups.findByGroupId(hh.getGroupId()).orElseThrow().getMemberEmails()).contains(maya);
        assertThat(bandRepo.findById(new HouseholdMemberBand.Key(hh.getGroupId(), maya)).orElseThrow().getBand())
                .isEqualTo(HouseholdBand.TEEN);
        assertThat(users.findByUserEmailIgnoreCase(maya).orElseThrow().getJoinedGroupIDs()).contains(hh.getGroupId());
        assertThat(users.findByUserEmailIgnoreCase(maya).orElseThrow().getBaseHouseholdId()).isEqualTo(hh.getGroupId());

        // references
        HouseholdAccompaniment a1 = accompaniments.findById(withOwner.getId()).orElseThrow();
        assertThat(a1.getAccompaniedKind()).isEqualTo("user");
        assertThat(a1.getAccompaniedId()).isEqualTo(maya);
        assertThat(a1.isPending()).isFalse();
        HouseholdAccompaniment a2 = accompaniments.findById(mayaWithLeo.getId()).orElseThrow();
        assertThat(a2.getSupervisorKind()).isEqualTo("user");
        assertThat(a2.getSupervisorId()).isEqualTo(maya);
        assertThat(a2.getAccompaniedId()).isEqualTo(leo.getId());
        EmergencySupportProfile p = profiles.findById(profile.getId()).orElseThrow();
        assertThat(p.getSubjectType()).isEqualTo("user");
        assertThat(p.getSubjectId()).isEqualTo(maya);
        for (EmergencySupportAssignment a : List.of(primary, backup)) {
            EmergencySupportAssignment x = assignments.findById(a.getId()).orElseThrow();
            assertThat(x.getSubjectType()).isEqualTo("user");
            assertThat(x.getSubjectId()).isEqualTo(maya);
        }
        EmergencyContact aunt = contactGroups.findById(family.getId()).orElseThrow().getContacts().get(0);
        assertThat(aunt.getSubjectType()).isEqualTo("user");
        assertThat(aunt.getSubjectId()).isEqualTo(maya);
        assertThat(aunt.getSubjectName()).isEqualTo("Maya Lee");

        // the manual row is gone; counts unchanged; the slot is now the account
        assertThat(manualRepo.findById(mayaRow.getId())).isEmpty();
        assertThat(row(hh).getAdults()).isEqualTo(1);
        assertThat(row(hh).getTeens()).isEqualTo(1);
        assertThat(row(hh).getKids()).isEqualTo(1);
        HouseholdCompositionDto dto = composition.compose(hh.getGroupId(), owner);
        assertThat(dto.people()).extracting(Person::slotKey)
                .containsExactly("user:" + owner, "user:" + maya, "manual:" + leo.getId());
        assertThat(dto.people().get(1).band()).isEqualTo(HouseholdBand.TEEN);
        assertThat(dto.summary().unnamed()).isZero();

        HouseholdClaimInvite consumed = claimRepo.findById(inv.token()).orElseThrow();
        assertThat(consumed.getConsumedAt()).isNotNull();
        assertThat(consumed.getConsumedByEmail()).isEqualTo(maya);

        // admins hear about it (presence-aware, existing new_member type); the claimer does not
        verify(notifications).deliverPresenceAware(eq(owner), eq("Maya joined Test " + sfx),
                contains("claimed their spot"), anyString(), nullable(String.class), eq("new_member"),
                eq(hh.getGroupId()), anyString(), nullable(String.class), nullable(String.class),
                nullable(String.class));
        verify(notifications, never()).deliverPresenceAware(eq(maya), anyString(), anyString(), anyString(),
                nullable(String.class), anyString(), anyString(), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class));
        verify(ws).sendHouseholdManualMemberDeletion(hh.getGroupId(), mayaRow.getId());
    }

    @Test
    void reAcceptingWithTheSameAccountIsIdempotent_anotherAccountGetsConsumed() {
        user(maya, "Maya", "Lee", null);
        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        claims.accept(inv.token(), maya);

        AcceptResult again = claims.accept(inv.token(), maya);
        assertThat(again.alreadyClaimed()).isTrue();
        assertThat(again.householdId()).isEqualTo(hh.getGroupId());
        assertThat(again.band()).isEqualTo(HouseholdBand.TEEN);
        assertThat(row(hh).getTeens()).isEqualTo(1);

        String other = email("other");
        user(other, "O", null, null);
        assertState(() -> claims.accept(inv.token(), other), State.CONSUMED, HttpStatus.GONE);
        assertThat(groups.findByGroupId(hh.getGroupId()).orElseThrow().getMemberEmails()).doesNotContain(other);
    }

    @Test
    void deadLinksAreRefused_andNothingChanges() {
        user(maya, "Maya", "Lee", null);
        assertState(() -> claims.accept("nope-" + sfx, maya), State.NOT_FOUND, HttpStatus.NOT_FOUND);

        ClaimInvite expired = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        expire(expired.token());
        assertState(() -> claims.accept(expired.token(), maya), State.EXPIRED, HttpStatus.GONE);

        ClaimInvite revoked = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        claims.revoke(hh.getGroupId(), mayaRow.getId(), owner);
        assertState(() -> claims.accept(revoked.token(), maya), State.REVOKED, HttpStatus.GONE);

        ClaimInvite gone = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);
        manualRepo.deleteById(mayaRow.getId());
        assertState(() -> claims.accept(gone.token(), maya), State.MEMBER_GONE, HttpStatus.GONE);

        assertThat(groups.findByGroupId(hh.getGroupId()).orElseThrow().getMemberEmails()).doesNotContain(maya);
        assertThat(claimRepo.findById(gone.token()).orElseThrow().getConsumedAt()).isNull();
    }

    @Test
    void aBaseHouseholdWithDataIsLeftAlone() {
        Group home = household(maya, maya);
        demo(home.getGroupId(), 1, 0, 0, 0);                 // a real plan lives there
        user(maya, "Maya", "Lee", home.getGroupId());
        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);

        AcceptResult r = claims.accept(inv.token(), maya);

        assertThat(r.baseChanged()).isFalse();
        assertThat(r.baseHouseholdId()).isEqualTo(home.getGroupId());
        assertThat(users.findByUserEmailIgnoreCase(maya).orElseThrow().getBaseHouseholdId()).isEqualTo(home.getGroupId());
        assertThat(groups.findByGroupId(hh.getGroupId()).orElseThrow().getMemberEmails()).contains(maya);
    }

    @Test
    void aSoloBaseSharedWithSomeoneElseIsLeftAlone() {
        Group home = household(maya, maya, email("partner"));
        user(maya, "Maya", "Lee", home.getGroupId());
        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);

        assertThat(claims.accept(inv.token(), maya).baseChanged()).isFalse();
    }

    @Test
    void aMissingBaseIsSet() {
        user(maya, "Maya", "Lee", null);
        ClaimInvite inv = claims.mint(hh.getGroupId(), mayaRow.getId(), owner);

        AcceptResult r = claims.accept(inv.token(), maya);

        assertThat(r.baseChanged()).isTrue();
        assertThat(users.findByUserEmailIgnoreCase(maya).orElseThrow().getBaseHouseholdId()).isEqualTo(hh.getGroupId());
    }

    @Test
    void anExistingMemberClaimingTheirOwnEntryRemovesTheDoubleCount() {
        // The owner added themselves by hand before signing up: counted twice.
        HouseholdManualMember dup = manualRow(hh.getGroupId(), "Dione", HouseholdBand.ADULT);
        Demographic d = row(hh);
        d.setAdults(2);
        demographics.save(d);
        ClaimInvite inv = claims.mint(hh.getGroupId(), dup.getId(), owner);

        AcceptResult r = claims.accept(inv.token(), owner);

        assertThat(r.band()).isEqualTo(HouseholdBand.ADULT);
        assertThat(r.baseChanged()).isFalse();
        assertThat(manualRepo.findById(dup.getId())).isEmpty();
        assertThat(row(hh).getAdults()).isEqualTo(1);
        assertThat(groups.findByGroupId(hh.getGroupId()).orElseThrow().getMemberEmails()).containsExactly(owner);
        assertThat(composition.compose(hh.getGroupId(), owner).summary().unnamed()).isZero();
        verify(notifications, never()).deliverPresenceAware(eq(owner), anyString(), anyString(), anyString(),
                nullable(String.class), anyString(), anyString(), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class));
    }

    @Test
    void onCollisionTheAccountsOwnRowsWin() {
        String teen = email("teen");
        Group g = groups.findByGroupId(hh.getGroupId()).orElseThrow();
        g.getMemberEmails().add(teen);
        groups.save(g);
        user(teen, "Maya", "Lee", null);
        HouseholdAccompaniment userRow = accompaniment(hh.getGroupId(), "user", owner, "user", teen);
        HouseholdAccompaniment manualRowAcc = accompaniment(hh.getGroupId(), "user", owner, "manual", mayaRow.getId());
        EmergencySupportProfile own = profile(hh.getGroupId(), "user", teen);
        EmergencySupportProfile manualProfile = profile(hh.getGroupId(), "manual", mayaRow.getId());
        EmergencySupportAssignment ownPrimary = assignment(hh.getGroupId(), "user", teen, EmergencySupportAssignment.Role.PRIMARY);
        EmergencySupportAssignment manualPrimary = assignment(hh.getGroupId(), "manual", mayaRow.getId(), EmergencySupportAssignment.Role.PRIMARY);
        EmergencySupportAssignment manualBackup = assignment(hh.getGroupId(), "manual", mayaRow.getId(), EmergencySupportAssignment.Role.BACKUP);

        claims.accept(claims.mint(hh.getGroupId(), mayaRow.getId(), owner).token(), teen);

        assertThat(accompaniments.findById(userRow.getId())).isPresent();
        assertThat(accompaniments.findById(manualRowAcc.getId())).isEmpty();
        assertThat(profiles.findById(own.getId())).isPresent();
        assertThat(profiles.findById(manualProfile.getId())).isEmpty();
        assertThat(assignments.findById(ownPrimary.getId())).isPresent();
        assertThat(assignments.findById(manualPrimary.getId())).isEmpty();
        assertThat(assignments.findById(manualBackup.getId()).orElseThrow().getSubjectId()).isEqualTo(teen);
    }

    @Test
    void aSelfReferenceIsDropped() {
        user(maya, "Maya", "Lee", null);
        // Maya-the-account already supervised Maya-the-manual-entry (a de-dup case).
        Group g = groups.findByGroupId(hh.getGroupId()).orElseThrow();
        g.getMemberEmails().add(maya);
        groups.save(g);
        HouseholdAccompaniment self = accompaniment(hh.getGroupId(), "user", maya, "manual", mayaRow.getId());

        claims.accept(claims.mint(hh.getGroupId(), mayaRow.getId(), owner).token(), maya);

        assertThat(accompaniments.findById(self.getId())).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private static void assertState(Runnable call, State state, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ClaimStateException.class, e -> {
            assertThat(e.state()).isEqualTo(state);
            assertThat(e.getStatusCode()).isEqualTo(status);
        });
    }

    private void expire(String token) {
        HouseholdClaimInvite i = claimRepo.findById(token).orElseThrow();
        i.setIssuedAt(Instant.now().minus(Duration.ofDays(9)));
        i.setExpiresAt(Instant.now().minus(Duration.ofDays(2)));
        claimRepo.save(i);
    }

    private String email(String who) {
        return who + "-" + sfx + "@x.com";
    }

    private Group household(String owner, String... members) {
        Group g = new Group();
        g.setGroupId("hh-" + UUID.randomUUID());
        g.setGroupType("Household");
        g.setGroupName("Test " + sfx);
        g.setPrivacy("Private");
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(List.of(owner)));
        g.setMemberEmails(new ArrayList<>(Arrays.asList(members)));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groups.save(g);
    }

    private UserInfo user(String email, String first, String last, String base) {
        UserInfo u = new UserInfo();
        u.setUserEmail(email);
        u.setUserFirstName(first);
        u.setUserLastName(last);
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setBaseHouseholdId(base);
        return users.save(u);
    }

    private Demographic demo(String hid, int a, int t, int k, int i) {
        Demographic x = new Demographic();
        x.setHouseholdId(hid);
        x.setOwnerEmail("seed-" + UUID.randomUUID() + "@x.com");
        x.setAdults(a);
        x.setTeens(t);
        x.setKids(k);
        x.setInfants(i);
        return demographics.save(x);
    }

    private Demographic row(Group g) {
        return demographics.findFirstByHouseholdIdOrderByIdDesc(g.getGroupId()).orElseThrow();
    }

    private HouseholdManualMember manualRow(String hid, String name, HouseholdBand band) {
        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(UUID.randomUUID().toString());
        m.setHouseholdId(hid);
        m.setName(name);
        m.setIsAdult(band == HouseholdBand.ADULT);
        m.setBand(band);
        return manualRepo.save(m);
    }

    private HouseholdAccompaniment accompaniment(String hid, String supKind, String supId, String accKind, String accId) {
        HouseholdAccompaniment a = new HouseholdAccompaniment();
        a.setHouseholdId(hid);
        a.setSupervisorKind(supKind);
        a.setSupervisorId(supId);
        a.setAccompaniedKind(accKind);
        a.setAccompaniedId(accId);
        a.setPending(false);
        return accompaniments.save(a);
    }

    private EmergencySupportProfile profile(String hid, String type, String id) {
        EmergencySupportProfile p = new EmergencySupportProfile();
        p.setHouseholdId(hid);
        p.setSubjectType(type);
        p.setSubjectId(id);
        return profiles.save(p);
    }

    private EmergencySupportAssignment assignment(String hid, String type, String id, EmergencySupportAssignment.Role role) {
        EmergencySupportAssignment a = new EmergencySupportAssignment();
        a.setHouseholdId(hid);
        a.setSubjectType(type);
        a.setSubjectId(id);
        a.setRole(role);
        a.setHelperType(EmergencySupportAssignment.HelperType.MEMBER);
        a.setHelperUserEmail(owner);
        a.setHelperName("Dione");
        return assignments.save(a);
    }

    private EmergencyContactGroup contactGroup(String hid, String contactName, String subjectType, String subjectId, String subjectName) {
        EmergencyContactGroup g = new EmergencyContactGroup();
        g.setOwnerEmail(owner);
        g.setHouseholdId(hid);
        g.setName("Family");
        EmergencyContact c = new EmergencyContact();
        c.setName(contactName);
        c.setPhone("555-0100");
        c.setSubjectType(subjectType);
        c.setSubjectId(subjectId);
        c.setSubjectName(subjectName);
        g.addContact(c);
        return contactGroups.save(g);
    }
}
