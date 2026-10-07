package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.constant.PetSpecies;
import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Person;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.PersonKind;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Pet;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.HouseholdPetDto;
import io.sitprep.sitprepapi.dto.MeDto;
import io.sitprep.sitprepapi.readiness.EssentialsReadinessService;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.HouseholdCompositionService.CountsBelowNamedException;
import io.sitprep.sitprepapi.service.HouseholdCompositionService.CountsRequest;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Household roster EXEC-B — the composition derivation and the write-time
 * counts invariant, over the real services on the H2 test schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class HouseholdCompositionTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired GroupRepo groups;
    @Autowired UserInfoRepo users;
    @Autowired DemographicRepo demographics;
    @Autowired HouseholdManualMemberRepo manualRepo;
    @Autowired HouseholdMemberBandRepo bandRepo;

    @Autowired HouseholdCompositionService composition;
    @Autowired HouseholdManualMemberService manual;
    @Autowired HouseholdPetService pets;
    @Autowired GroupService groupService;
    @Autowired DemographicService demographicService;
    @Autowired EssentialsReadinessService essentials;
    @Autowired MeService meService;

    private String sfx;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── read ────────────────────────────────────────────────────────────

    @Test
    void composesAccountsThenManualThenPlaceholders_andPetsNamedThenPlaceholders() {
        String owner = email("owner"), b = email("b"), c = email("c");
        Group g = household(owner, owner, b, c);
        user(owner, "Dione", "Pierre-Louis", g.getGroupId());
        user(b, "Francis", null, g.getGroupId());
        // c has no profile row — name stays null, never derived from the email.
        manualRow(g.getGroupId(), "Chris", null, null, HouseholdBand.TEEN);
        manualRow(g.getGroupId(), "Taylor", false, 6, null); // band null → derived KID
        demo(g.getGroupId(), 3, 1, 2, 1, 1, 1, 1);
        pets.add(owner, g.getGroupId(), new HouseholdPetService.UpsertRequest(null, "Rex", "Dog", null, null));

        HouseholdCompositionDto dto = composition.compose(g.getGroupId(), b);

        assertThat(dto.version()).isEqualTo(1);
        assertThat(dto.planned()).isTrue();
        assertThat(dto.people()).extracting(Person::slotKey).containsExactly(
                "user:" + b, "user:" + owner, "user:" + c,          // you first, then roster order
                "manual:" + manualId(g, "Chris"), "manual:" + manualId(g, "Taylor"),
                "placeholder:KID:1", "placeholder:INFANT:1");
        assertThat(dto.people()).extracting(Person::kind).containsExactly(
                PersonKind.ACCOUNT, PersonKind.ACCOUNT, PersonKind.ACCOUNT,
                PersonKind.MANUAL, PersonKind.MANUAL, PersonKind.PLACEHOLDER, PersonKind.PLACEHOLDER);
        assertThat(dto.people()).extracting(Person::band).containsExactly(
                HouseholdBand.ADULT, HouseholdBand.ADULT, HouseholdBand.ADULT,
                HouseholdBand.TEEN, HouseholdBand.KID, HouseholdBand.KID, HouseholdBand.INFANT);
        Person you = dto.people().get(0);
        assertThat(you.isYou()).isTrue();
        assertThat(you.name()).isEqualTo("Francis");
        assertThat(you.role()).isEqualTo("MEMBER");
        assertThat(dto.people().get(1).role()).isEqualTo("OWNER");
        assertThat(dto.people().get(2).name()).isNull();
        assertThat(dto.people().get(3).email()).isNull();
        assertThat(dto.people().get(3).claim().pending()).isFalse();

        assertThat(dto.pets()).extracting(Pet::slotKey).containsExactly(
                "pet:" + dto.pets().get(0).petId(), "placeholder:CAT:1", "placeholder:OTHER:1");
        assertThat(dto.pets().get(0).species()).isEqualTo(PetSpecies.DOG);

        assertThat(dto.counts()).isEqualTo(new HouseholdCompositionDto.Counts(3, 1, 2, 1, 1, 1, 1));
        assertThat(dto.minimum()).isEqualTo(new HouseholdCompositionDto.Counts(3, 1, 1, 0, 1, 0, 0));
        var s = dto.summary();
        assertThat(s.people()).isEqualTo(dto.people().size()).isEqualTo(7);
        assertThat(s.pets()).isEqualTo(dto.pets().size()).isEqualTo(3);
        assertThat(s.total()).isEqualTo(10).isEqualTo(dto.counts().people() + dto.counts().pets());
        assertThat(s.named()).isEqualTo(6);
        assertThat(s.unnamed()).isEqualTo(4);
        assertThat(s.named() + s.unnamed()).isEqualTo(s.total());
        assertThat(s.accounts()).isEqualTo(3);
        assertThat(s.manual()).isEqualTo(2);
    }

    @Test
    void anAccountThatClaimedATeenSpotIsCountedAsATeen() {
        String owner = email("owner"), teen = email("teen");
        Group g = household(owner, owner, teen);
        HouseholdMemberBand row = new HouseholdMemberBand();
        row.setHouseholdId(g.getGroupId());
        row.setUserEmail(teen);
        row.setBand(HouseholdBand.TEEN);
        bandRepo.save(row);
        demo(g.getGroupId(), 1, 1, 0, 0, 0, 0, 0);

        HouseholdCompositionDto dto = composition.compose(g.getGroupId(), owner);

        assertThat(dto.people()).extracting(Person::band).containsExactly(HouseholdBand.ADULT, HouseholdBand.TEEN);
        assertThat(dto.minimum().teens()).isEqualTo(1);
        assertThat(dto.summary().unnamed()).isZero();
    }

    @Test
    void withoutAPlanRowOnlyNamedPeopleAreListed() {
        String owner = email("owner");
        Group g = household(owner, owner);
        manualRow(g.getGroupId(), "Kid", false, 4, null);

        HouseholdCompositionDto dto = composition.compose(g.getGroupId(), owner);

        assertThat(dto.planned()).isFalse();
        // No row → the plan counts who is named (one adult account, one kid).
        assertThat(dto.counts()).isEqualTo(new HouseholdCompositionDto.Counts(1, 0, 1, 0, 0, 0, 0));
        assertThat(dto.counts()).isEqualTo(dto.minimum());
        assertThat(dto.people()).extracting(Person::kind).containsExactly(PersonKind.ACCOUNT, PersonKind.MANUAL);
        assertThat(dto.summary().total()).isEqualTo(2);
        assertThat(dto.summary().unnamed()).isZero();
    }

    @Test
    void capabilitiesAreTheServersOwnGates() {
        String owner = email("owner"), admin = email("admin"), member = email("member");
        Group g = household(owner, owner, admin, member);
        g.setAdminEmails(new ArrayList<>(List.of(owner, admin)));
        groups.save(g);
        manualRow(g.getGroupId(), "Kid", false, 4, null);
        demo(g.getGroupId(), 4, 0, 1, 0, 0, 0, 0); // one ADULT placeholder

        HouseholdCompositionDto asAdmin = composition.compose(g.getGroupId(), admin);
        assertThat(asAdmin.viewer().role()).isEqualTo("ADMIN");
        assertThat(asAdmin.viewer().canEditCounts()).isTrue();
        assertThat(person(asAdmin, "user:" + owner).capabilities().canRemove()).isFalse();  // never the owner
        assertThat(person(asAdmin, "user:" + admin).capabilities().canRemove()).isFalse();  // never yourself
        assertThat(person(asAdmin, "user:" + member).capabilities().canRemove()).isTrue();
        Person kid = person(asAdmin, "manual:" + manualId(g, "Kid"));
        assertThat(kid.capabilities().canInviteToClaim()).isTrue();
        assertThat(kid.capabilities().canRename()).isTrue();
        Person open = person(asAdmin, "placeholder:ADULT:1");
        assertThat(open.capabilities().canName()).isTrue();
        assertThat(open.capabilities().canRemove()).isTrue();

        HouseholdCompositionDto asMember = composition.compose(g.getGroupId(), member);
        assertThat(asMember.viewer().role()).isEqualTo("MEMBER");
        assertThat(asMember.viewer().canEditCounts()).isFalse();
        assertThat(person(asMember, "user:" + admin).capabilities().canRemove()).isFalse();
        assertThat(person(asMember, "manual:" + manualId(g, "Kid")).capabilities().canInviteToClaim()).isFalse();
        assertThat(person(asMember, "manual:" + manualId(g, "Kid")).capabilities().canRemove()).isTrue();
        assertThat(person(asMember, "placeholder:ADULT:1").capabilities().canName()).isTrue();
        assertThat(person(asMember, "placeholder:ADULT:1").capabilities().canRemove()).isFalse();
    }

    @Test
    void homePrintsTheCompositionNumber_notTheAccountCount_andTheEssentialAgrees() {
        String owner = email("owner"), b = email("b"), c = email("c");
        Group g = household(owner, owner, b, c);
        UserInfo me = user(owner, "Dione", "P", g.getGroupId());
        user(b, "B", null, g.getGroupId());
        user(c, "C", null, g.getGroupId());
        manualRow(g.getGroupId(), "Chris", false, 8, null);
        manualRow(g.getGroupId(), "Taylor", false, 1, null);
        demo(g.getGroupId(), 3, 0, 1, 1, 1, 1, 1);

        MeDto.HouseholdDto hh = meService.buildMe(me.getFirebaseUid()).orElseThrow().me().household();

        assertThat(hh.memberCount()).isEqualTo(3);                 // accounts — what Home used to print
        assertThat(hh.composition()).isNotNull();
        assertThat(hh.composition().people()).isEqualTo(5);         // what Home prints now
        assertThat(hh.composition().people())
                .isEqualTo(composition.compose(g.getGroupId(), b).summary().people());
        // Parity with EssentialsReadinessService: headCount = people + pets.
        assertThat(hh.composition().total()).isEqualTo(8);
        assertThat(essentials.evaluate(groups.findByGroupId(g.getGroupId()).orElseThrow(), owner).demographics())
                .isEqualTo(hh.composition().total() > 0);
    }

    // ── write-time count rules ──────────────────────────────────────────

    @Test
    void namingFillsAnOpenPlaceholder_elseRaisesTheBand() {
        String owner = email("owner");
        Group g = household(owner, owner);
        demo(g.getGroupId(), 1, 0, 2, 0, 0, 0, 0);

        manual.add(g.getGroupId(), req("Ava", null, 7, null), owner);
        assertThat(row(g).getKids()).isEqualTo(2); // filled

        manual.add(g.getGroupId(), req("Ben", null, 9, null), owner);
        assertThat(row(g).getKids()).isEqualTo(2); // filled the last one

        manual.add(g.getGroupId(), req("Cal", null, 5, null), owner);
        assertThat(row(g).getKids()).isEqualTo(3); // none open → raised
    }

    @Test
    void anExplicitBandOutranksAge_andTheAdultBandMeansAdult() {
        String owner = email("owner");
        Group g = household(owner, owner);
        demo(g.getGroupId(), 1, 1, 0, 0, 0, 0, 0);

        HouseholdManualMemberDto teen = manual.add(g.getGroupId(), req("Maya", null, null, "teen"), owner);
        assertThat(teen.band()).isEqualTo("TEEN");
        assertThat(teen.isAdult()).isFalse();
        assertThat(row(g).getTeens()).isEqualTo(1);  // the teen placeholder filled
        assertThat(row(g).getKids()).isZero();       // not mis-counted as a kid

        HouseholdManualMemberDto grandpa = manual.add(g.getGroupId(), req("Grandpa", null, null, "ADULT"), owner);
        assertThat(grandpa.band()).isEqualTo("ADULT");
        assertThat(grandpa.isAdult()).isTrue();
        assertThat(row(g).getAdults()).isEqualTo(2);

        assertThatThrownBy(() -> manual.add(g.getGroupId(), req("X", null, null, "elder"), owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namingWithoutAPlanRowSeedsOneAtTheNamedTotals() {
        String owner = email("owner"), b = email("b");
        Group g = household(owner, owner, b);
        assertThat(demographics.findFirstByHouseholdIdOrderByIdDesc(g.getGroupId())).isEmpty();

        manual.add(g.getGroupId(), req("Ava", null, 7, null), owner);

        Demographic d = row(g);
        assertThat(d.getAdults()).isEqualTo(2);
        assertThat(d.getKids()).isEqualTo(1);
        assertThat(d.getOwnerEmail()).isEqualTo(owner);
    }

    @Test
    void deletingANamedPersonLowersTheirBand_andABandChangeMovesTheCount() {
        String owner = email("owner");
        Group g = household(owner, owner);
        demo(g.getGroupId(), 1, 0, 2, 0, 0, 0, 0);
        HouseholdManualMemberDto ava = manual.add(g.getGroupId(), req("Ava", null, 7, null), owner);
        HouseholdManualMemberDto ben = manual.add(g.getGroupId(), req("Ben", null, 9, null), owner);

        manual.update(g.getGroupId(), ben.id(), req(null, null, null, "teen"));
        assertThat(row(g).getKids()).isEqualTo(1);
        assertThat(row(g).getTeens()).isEqualTo(1);

        manual.update(g.getGroupId(), ava.id(), req("Ava R", null, null, null)); // rename: no move
        assertThat(row(g).getKids()).isEqualTo(1);

        manual.remove(g.getGroupId(), ava.id());
        assertThat(row(g).getKids()).isZero();
        assertThat(row(g).getTeens()).isEqualTo(1);
    }

    @Test
    void deletingAPersonNeverDropsTheBandBelowWhoIsStillNamed() {
        String owner = email("owner");
        Group g = household(owner, owner);
        demo(g.getGroupId(), 1, 0, 3, 0, 0, 0, 0);
        HouseholdManualMemberDto ava = manual.add(g.getGroupId(), req("Ava", null, 7, null), owner);
        manual.add(g.getGroupId(), req("Ben", null, 9, null), owner);

        manual.remove(g.getGroupId(), ava.id());

        assertThat(row(g).getKids()).isEqualTo(2); // 3 − 1; Ben + one placeholder remain
    }

    @Test
    void petsFillRaiseLowerAndMove() {
        String owner = email("owner");
        Group g = household(owner, owner);
        demo(g.getGroupId(), 1, 0, 0, 0, 1, 0, 0);

        HouseholdPetDto rex = pets.add(owner, g.getGroupId(), new HouseholdPetService.UpsertRequest(null, "Rex", "dog", null, null));
        assertThat(row(g).getDogs()).isEqualTo(1);   // filled
        pets.add(owner, g.getGroupId(), new HouseholdPetService.UpsertRequest(null, "Tom", "cat", null, null));
        assertThat(row(g).getCats()).isEqualTo(1);   // raised
        pets.add(owner, g.getGroupId(), new HouseholdPetService.UpsertRequest(null, "Polly", null, null, null));
        assertThat(row(g).getPets()).isEqualTo(1);   // no species → other

        pets.update(owner, g.getGroupId(), rex.id(), new HouseholdPetService.UpsertRequest(null, null, "cat", null, null));
        assertThat(row(g).getDogs()).isZero();
        assertThat(row(g).getCats()).isEqualTo(2);

        pets.remove(owner, g.getGroupId(), rex.id());
        assertThat(row(g).getCats()).isEqualTo(1);
    }

    @Test
    void anAccountJoiningFillsAnAdultPlaceholder_thenRaisesAdults() {
        String owner = email("owner"), b = email("b"), c = email("c");
        Group g = household(owner, owner);
        user(owner, "O", null, g.getGroupId());
        user(b, "B", null, null);
        user(c, "C", null, null);
        demo(g.getGroupId(), 2, 0, 0, 0, 0, 0, 0);

        groupService.joinHouseholdByInvite(g.getGroupId(), b);
        assertThat(row(g).getAdults()).isEqualTo(2);  // filled

        g = groups.findByGroupId(g.getGroupId()).orElseThrow();
        List<String> pending = new ArrayList<>(List.of(c));
        g.setPendingMemberEmails(pending);
        groups.save(g);
        groupService.approveMemberAction(g.getGroupId(), c);
        assertThat(row(g).getAdults()).isEqualTo(3);  // raised
    }

    @Test
    void anAccountJoiningWithoutAPlanRowCreatesNone() {
        String owner = email("owner"), b = email("b");
        Group g = household(owner, owner);
        user(b, "B", null, null);

        groupService.joinHouseholdByInvite(g.getGroupId(), b);

        assertThat(demographics.findFirstByHouseholdIdOrderByIdDesc(g.getGroupId())).isEmpty();
    }

    @Test
    void removingAnAccountKeepsItsSlot_asAnUnnamedPlaceholderInItsBand() {
        String owner = email("owner"), teen = email("teen");
        Group g = household(owner, owner, teen);
        user(owner, "O", null, g.getGroupId());
        user(teen, "T", null, null);
        HouseholdMemberBand band = new HouseholdMemberBand();
        band.setHouseholdId(g.getGroupId());
        band.setUserEmail(teen);
        band.setBand(HouseholdBand.TEEN);
        bandRepo.save(band);
        demo(g.getGroupId(), 1, 1, 0, 0, 0, 0, 0);

        groupService.removeMember(g.getGroupId(), teen);

        assertThat(row(g).getTeens()).isEqualTo(1);
        assertThat(bandRepo.findById(new HouseholdMemberBand.Key(g.getGroupId(), teen))).isEmpty();
        HouseholdCompositionDto dto = composition.compose(g.getGroupId(), owner);
        assertThat(dto.people()).extracting(Person::slotKey).containsExactly("user:" + owner, "placeholder:TEEN:1");
    }

    @Test
    void removingAMemberReanchorsTheirBase_toAnotherHouseholdTheyBelongTo() {
        String owner = email("owner"), b = email("b");
        Group g = household(owner, owner, b);
        Group other = household(b, b);
        user(b, "B", null, g.getGroupId());

        groupService.removeMember(g.getGroupId(), b);

        assertThat(users.findByUserEmailIgnoreCase(b).orElseThrow().getBaseHouseholdId())
                .isEqualTo(other.getGroupId());
    }

    @Test
    void removingAMemberWithNoOtherHouseholdGetsAPersonalOne_neverTheOldId() {
        String owner = email("owner"), b = email("b");
        Group g = household(owner, owner, b);
        user(b, "B", "Lee", g.getGroupId());

        groupService.removeMember(g.getGroupId(), " " + b.toUpperCase() + " ");

        String base = users.findByUserEmailIgnoreCase(b).orElseThrow().getBaseHouseholdId();
        assertThat(base).isNotNull().isNotEqualTo(g.getGroupId());
        Group personal = groups.findByGroupId(base).orElseThrow();
        assertThat(personal.getGroupType()).isEqualTo("Household");
        assertThat(personal.getMemberEmails()).containsExactly(b);
        assertThat(groups.findByGroupId(g.getGroupId()).orElseThrow().getMemberEmails()).containsExactly(owner);
    }

    @Test
    void removingSomeoneWhoseBaseIsElsewhereLeavesTheirBaseAlone() {
        String owner = email("owner"), b = email("b");
        Group g = household(owner, owner, b);
        Group home = household(b, b);
        user(b, "B", null, home.getGroupId());

        groupService.removeMember(g.getGroupId(), b);

        assertThat(users.findByUserEmailIgnoreCase(b).orElseThrow().getBaseHouseholdId()).isEqualTo(home.getGroupId());
    }

    // ── counts writes ───────────────────────────────────────────────────

    @Test
    void aCountsWriteBelowNamedIs409_withTheBandAndItsFloor() {
        String owner = email("owner"), b = email("b"), c = email("c");
        Group g = household(owner, owner, b, c);
        demo(g.getGroupId(), 3, 0, 0, 0, 0, 0, 0);

        assertThatThrownBy(() -> composition.setCounts(g.getGroupId(),
                new CountsRequest(1, null, null, null, null, null, null), owner))
                .isInstanceOfSatisfying(CountsBelowNamedException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.band()).isEqualTo("ADULT");
                    assertThat(e.minimum()).isEqualTo(3);
                    assertThat(e.requested()).isEqualTo(1);
                    assertThat(e.getReason()).contains("3 named adults");
                });
        assertThat(row(g).getAdults()).isEqualTo(3);

        assertThatThrownBy(() -> composition.setCounts(g.getGroupId(),
                new CountsRequest(null, -1, null, null, null, null, null), owner))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        HouseholdCompositionDto after = composition.setCounts(g.getGroupId(),
                new CountsRequest(4, null, 2, null, 1, null, null), owner);
        assertThat(after.counts()).isEqualTo(new HouseholdCompositionDto.Counts(4, 0, 2, 0, 1, 0, 0));
        assertThat(after.summary().unnamed()).isEqualTo(4); // 1 adult + 2 kids + 1 dog
    }

    @Test
    void aCountsWriteWithNoPlanRowCreatesIt() {
        String owner = email("owner");
        Group g = household(owner, owner);

        HouseholdCompositionDto dto = composition.setCounts(g.getGroupId(),
                new CountsRequest(2, null, null, null, null, 1, null), owner);

        assertThat(dto.planned()).isTrue();
        assertThat(row(g).getAdults()).isEqualTo(2);
        assertThat(row(g).getCats()).isEqualTo(1);
    }

    @Test
    void aBrandNewHousehold_countingOneMore_startsFromWhoIsNamed_notFromZero() {
        // The add drawer's "Just count them": server count + 1 for one band.
        // Before 2026-10-07 a household with no row answered {kids: 1} with a
        // 409 about ADULTS (unspecified fields defaulted to 0, below the
        // viewer's own account), and {adults: 0 + 1} created a row that counted
        // nobody new.
        String owner = email("owner");
        Group g = household(owner, owner);
        HouseholdCompositionDto before = composition.compose(g.getGroupId(), owner);
        assertThat(before.planned()).isFalse();
        assertThat(before.counts().kids()).isZero();

        HouseholdCompositionDto kid = composition.setCounts(g.getGroupId(),
                new CountsRequest(null, null, before.counts().kids() + 1, null, null, null, null), owner);
        assertThat(kid.planned()).isTrue();
        assertThat(kid.counts()).isEqualTo(new HouseholdCompositionDto.Counts(1, 0, 1, 0, 0, 0, 0));
        assertThat(kid.summary().people()).isEqualTo(2);
        assertThat(kid.people()).extracting(Person::slotKey).contains("placeholder:KID:1");

        String owner2 = email("owner2");
        Group g2 = household(owner2, owner2);
        HouseholdCompositionDto start = composition.compose(g2.getGroupId(), owner2);
        HouseholdCompositionDto adult = composition.setCounts(g2.getGroupId(),
                new CountsRequest(start.counts().adults() + 1, null, null, null, null, null, null), owner2);
        assertThat(adult.counts().adults()).isEqualTo(2);
        assertThat(adult.summary().people()).isEqualTo(2);
        assertThat(adult.people()).extracting(Person::slotKey).contains("placeholder:ADULT:1");
        // Every sizing surface reads this same row.
        assertThat(row(g2).getAdults()).isEqualTo(2);
    }

    @Test
    void aBrandNewHousehold_firstNamedAdult_createsTheRowEverySurfaceReads() {
        String owner = email("owner");
        Group g = household(owner, owner);
        UserInfo me = user(owner, "Avery", "Agent", g.getGroupId());
        assertThat(demographics.findFirstByHouseholdIdOrderByIdDesc(g.getGroupId())).isEmpty();
        assertThat(essentials.evaluate(groups.findByGroupId(g.getGroupId()).orElseThrow(), owner).demographics()).isFalse();

        manual.add(g.getGroupId(), req("RFM Test Adult", null, null, "ADULT"), owner);

        Demographic d = row(g);
        assertThat(d.getAdults()).isEqualTo(2);
        HouseholdCompositionDto dto = composition.compose(g.getGroupId(), owner);
        assertThat(dto.summary().people()).isEqualTo(2);
        MeDto.HouseholdDto hh = meService.buildMe(me.getFirebaseUid()).orElseThrow().me().household();
        assertThat(hh.composition().people()).isEqualTo(2);
        assertThat(hh.demographic()).isNotNull();
        assertThat(hh.demographic().adults()).isEqualTo(2);
        assertThat(essentials.evaluate(groups.findByGroupId(g.getGroupId()).orElseThrow(), owner).demographics()).isTrue();

        // Undo: deleting the person lowers the band back to who is named.
        manual.remove(g.getGroupId(), manualId(g, "RFM Test Adult"));
        assertThat(row(g).getAdults()).isEqualTo(1);
    }

    @Test
    void homeNeverReadsAnotherHouseholdsHeadCount_throughTheOwnerFallback() {
        String owner = email("owner");
        Group old = household(owner, owner);
        Group fresh = household(owner, owner);
        UserInfo me = user(owner, "Avery", null, fresh.getGroupId());
        Demographic foreign = demo(old.getGroupId(), 4, 0, 0, 0, 0, 0, 0);
        foreign.setOwnerEmail(owner);
        demographics.save(foreign);

        MeDto.HouseholdDto hh = meService.buildMe(me.getFirebaseUid()).orElseThrow().me().household();
        assertThat(hh.groupId()).isEqualTo(fresh.getGroupId());
        assertThat(hh.demographic()).isNull();
        assertThat(hh.composition().people()).isEqualTo(1);
        assertThat(essentials.evaluate(groups.findByGroupId(fresh.getGroupId()).orElseThrow(), owner).demographics())
                .isFalse();

        // A legacy row with no household key still counts for the base.
        Demographic legacy = demo(null, 2, 0, 0, 0, 0, 0, 0);
        legacy.setOwnerEmail(owner);
        demographics.save(legacy);
        MeDto.HouseholdDto again = meService.buildMe(me.getFirebaseUid()).orElseThrow().me().household();
        assertThat(again.demographic()).isNotNull();
        assertThat(again.demographic().adults()).isEqualTo(2);
    }

    @Test
    void theLegacyDemographicSave_rejectsBelowNamed_andIgnoresAClientSentId() {
        String owner = email("owner"), b = email("b");
        Group g = household(owner, owner, b);
        user(owner, "O", null, g.getGroupId());
        Demographic mine = demo(g.getGroupId(), 2, 0, 0, 0, 0, 0, 0);
        Group otherHh = household(email("x"), email("x"));
        Demographic theirs = demo(otherHh.getGroupId(), 5, 0, 0, 0, 0, 0, 0);
        authenticateAs(owner);

        Demographic below = new Demographic();
        below.setAdults(1);
        assertThatThrownBy(() -> demographicService.saveDemographic(below))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        Demographic hijack = new Demographic();
        hijack.setId(theirs.getId());
        hijack.setAdults(2);
        hijack.setKids(1);
        Demographic saved = demographicService.saveDemographic(hijack);

        assertThat(saved.getId()).isEqualTo(mine.getId());
        assertThat(row(g).getKids()).isEqualTo(1);
        assertThat(demographics.findFirstByHouseholdIdOrderByIdDesc(otherHh.getGroupId()).orElseThrow().getAdults())
                .isEqualTo(5);
    }

    // ── fixtures ────────────────────────────────────────────────────────

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

    private Demographic demo(String hid, int a, int t, int k, int i, int d, int c, int o) {
        Demographic x = new Demographic();
        x.setHouseholdId(hid);
        x.setOwnerEmail("seed-" + UUID.randomUUID() + "@x.com");
        x.setAdults(a);
        x.setTeens(t);
        x.setKids(k);
        x.setInfants(i);
        x.setDogs(d);
        x.setCats(c);
        x.setPets(o);
        return demographics.save(x);
    }

    private HouseholdManualMember manualRow(String hid, String name, Boolean isAdult, Integer age, HouseholdBand band) {
        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(UUID.randomUUID().toString());
        m.setHouseholdId(hid);
        m.setName(name);
        m.setIsAdult(isAdult == null ? Boolean.FALSE : isAdult);
        m.setAge(age);
        m.setBand(band);
        return manualRepo.save(m);
    }

    private String manualId(Group g, String name) {
        return manualRepo.findByHouseholdIdOrderByCreatedAtAsc(g.getGroupId()).stream()
                .filter(m -> name.equals(m.getName())).findFirst().orElseThrow().getId();
    }

    private Demographic row(Group g) {
        return demographics.findFirstByHouseholdIdOrderByIdDesc(g.getGroupId()).orElseThrow();
    }

    private static Person person(HouseholdCompositionDto dto, String slotKey) {
        return dto.people().stream().filter(p -> p.slotKey().equals(slotKey)).findFirst().orElseThrow();
    }

    private static HouseholdManualMemberService.UpsertRequest req(String name, Boolean isAdult, Integer age, String band) {
        return new HouseholdManualMemberService.UpsertRequest(null, name, null, age, isAdult, null, band);
    }

    private static void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
