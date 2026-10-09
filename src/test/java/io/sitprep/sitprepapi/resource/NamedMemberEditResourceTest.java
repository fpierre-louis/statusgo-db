package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.constant.PetSpecies;
import io.sitprep.sitprepapi.domain.Demographic;
import io.sitprep.sitprepapi.domain.EmergencySupportAssignment;
import io.sitprep.sitprepapi.domain.EmergencySupportProfile;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdPet;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Person;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.PersonKind;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.Pet;
import io.sitprep.sitprepapi.dto.HouseholdCompositionDto.PetKind;
import io.sitprep.sitprepapi.dto.HouseholdManualMemberDto;
import io.sitprep.sitprepapi.dto.HouseholdPetDto;
import io.sitprep.sitprepapi.repo.DemographicRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportAssignmentRepo;
import io.sitprep.sitprepapi.repo.EmergencySupportProfileRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdPetRepo;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.HouseholdCompositionService;
import io.sitprep.sitprepapi.service.HouseholdManualMemberService;
import io.sitprep.sitprepapi.service.HouseholdPetService;
import io.sitprep.sitprepapi.service.NotificationService;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Rename, remove the name, and remove from household — for somebody added by
 * hand and for a named pet (owner, 2026-10-09). Owner/admin only; "Remove
 * name" keeps the person counted as an unnamed placeholder, "Remove" lowers
 * the count. Over the real services and the H2 schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class NamedMemberEditResourceTest {

    @MockBean WebSocketMessageSender ws;
    @MockBean NotificationService notifications;

    @Autowired HouseholdManualMemberResource manualResource;
    @Autowired HouseholdPetResource petResource;
    @Autowired HouseholdCompositionService composition;
    @Autowired GroupRepo groups;
    @Autowired DemographicRepo demographics;
    @Autowired HouseholdManualMemberRepo manualRepo;
    @Autowired HouseholdPetRepo petRepo;
    @Autowired EmergencySupportProfileRepo supportProfiles;
    @Autowired EmergencySupportAssignmentRepo supportAssignments;
    @Autowired GroupService groupService;

    private String owner;
    private String admin;
    private String member;
    private Group hh;
    private HouseholdManualMember chris;
    private HouseholdPet mochi;

    @BeforeEach
    void setUp() {
        String sfx = UUID.randomUUID().toString().substring(0, 8);
        owner = "owner-" + sfx + "@x.com";
        admin = "admin-" + sfx + "@x.com";
        member = "member-" + sfx + "@x.com";
        hh = household(owner, List.of(owner, admin), owner, admin, member);
        chris = manual(hh.getGroupId(), "Chris", HouseholdBand.KID);
        mochi = pet(hh.getGroupId(), "Mochi", "dog");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── a person added by hand ───────────────────────────────────────────

    @Test
    void anOwnerRenames_trimmed_andTheBandDoesNotMove() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(owner);
        HouseholdManualMemberDto dto = manualResource.update(hh.getGroupId(), chris.getId(), rename("  Chris R  ")).getBody();

        assertThat(dto.name()).isEqualTo("Chris R");
        assertThat(manualRepo.findById(chris.getId()).orElseThrow().getName()).isEqualTo("Chris R");
        assertThat(row().getKids()).isEqualTo(1);
        verify(ws).sendHouseholdManualMemberUpdate(eq(hh.getGroupId()), any());
    }

    @Test
    void anAdminMayRenameToo() {
        as(admin);
        assertThat(manualResource.update(hh.getGroupId(), chris.getId(), rename("Chirs")).getBody().name())
                .isEqualTo("Chirs");
    }

    @Test
    void aBlankOrTooLongNameIs400_andNothingChanges() {
        as(owner);
        for (String bad : new String[]{"", "   ", "x".repeat(HouseholdManualMemberService.NAME_MAX + 1)}) {
            assertStatus(() -> manualResource.update(hh.getGroupId(), chris.getId(), rename(bad)), HttpStatus.BAD_REQUEST);
        }
        assertThat(manualRepo.findById(chris.getId()).orElseThrow().getName()).isEqualTo("Chris");
        // The ceiling itself is allowed.
        String max = "y".repeat(HouseholdManualMemberService.NAME_MAX);
        assertThat(manualResource.update(hh.getGroupId(), chris.getId(), rename(max)).getBody().name()).isEqualTo(max);
    }

    @Test
    void aPlainMemberIs403_onRenameRemoveNameAndRemove_andNothingChanges() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(member);
        assertStatus(() -> manualResource.update(hh.getGroupId(), chris.getId(), rename("Nope")), HttpStatus.FORBIDDEN);
        assertStatus(() -> manualResource.remove(hh.getGroupId(), chris.getId(), true), HttpStatus.FORBIDDEN);
        assertStatus(() -> manualResource.remove(hh.getGroupId(), chris.getId(), false), HttpStatus.FORBIDDEN);

        assertThat(manualRepo.findById(chris.getId()).orElseThrow().getName()).isEqualTo("Chris");
        assertThat(row().getKids()).isEqualTo(1);
    }

    @Test
    void aMemberMayStillAddByName() {
        as(member);
        HouseholdManualMemberDto added = manualResource.add(hh.getGroupId(),
                new HouseholdManualMemberService.UpsertRequest(null, "Ivy", null, null, null, null, "INFANT")).getBody();
        assertThat(added.name()).isEqualTo("Ivy");
    }

    @Test
    void someoneInAnotherHouseholdIs404() {
        Group other = household(owner, List.of(owner), owner);
        HouseholdManualMember sam = manual(other.getGroupId(), "Sam", HouseholdBand.KID);
        as(owner);
        assertStatus(() -> manualResource.update(hh.getGroupId(), sam.getId(), rename("X")), HttpStatus.NOT_FOUND);
        assertStatus(() -> manualResource.remove(hh.getGroupId(), sam.getId(), true), HttpStatus.NOT_FOUND);
        assertStatus(() -> manualResource.remove(hh.getGroupId(), sam.getId(), false), HttpStatus.NOT_FOUND);
        assertThat(manualRepo.findById(sam.getId())).isPresent();
    }

    @Test
    void removeName_keepsTheCount_andTheRowBecomesAPlaceholder() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(owner);

        assertThat(manualResource.remove(hh.getGroupId(), chris.getId(), true).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(manualRepo.findById(chris.getId())).isEmpty();
        assertThat(row().getKids()).isEqualTo(1);
        HouseholdCompositionDto c = composition.compose(hh.getGroupId(), owner);
        assertThat(c.people()).filteredOn(p -> p.kind() == PersonKind.MANUAL).isEmpty();
        assertThat(c.people()).filteredOn(p -> p.kind() == PersonKind.PLACEHOLDER)
                .extracting(Person::band).containsExactly(HouseholdBand.KID);
        assertThat(c.summary().people()).isEqualTo(4);
        verify(ws).sendHouseholdManualMemberDeletion(hh.getGroupId(), chris.getId());
    }

    @Test
    void removeName_withNoPlanRow_seedsOne_soThePersonStaysCounted() {
        as(owner);
        assertThat(demographics.findFirstByHouseholdIdOrderByIdDesc(hh.getGroupId())).isEmpty();

        manualResource.remove(hh.getGroupId(), chris.getId(), true);

        Demographic d = row();
        assertThat(d.getAdults()).isEqualTo(3);
        assertThat(d.getKids()).isEqualTo(1);
        assertThat(d.getDogs()).isEqualTo(1);
        HouseholdCompositionDto c = composition.compose(hh.getGroupId(), owner);
        assertThat(c.planned()).isTrue();
        assertThat(c.people()).filteredOn(p -> p.kind() == PersonKind.PLACEHOLDER)
                .extracting(Person::band).containsExactly(HouseholdBand.KID);
    }

    @Test
    void removeFromHousehold_lowersTheCount() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(owner);

        manualResource.remove(hh.getGroupId(), chris.getId(), false);

        assertThat(manualRepo.findById(chris.getId())).isEmpty();
        assertThat(row().getKids()).isZero();
        assertThat(composition.compose(hh.getGroupId(), owner).people())
                .noneMatch(p -> p.band() == HouseholdBand.KID);
    }

    @Test
    void theCompositionOffersRenameAndRemove_onlyToOwnersAndAdmins() {
        Person asOwner = manualRow(composition.compose(hh.getGroupId(), owner));
        assertThat(asOwner.capabilities().canRename()).isTrue();
        assertThat(asOwner.capabilities().canRemove()).isTrue();

        Person asMember = manualRow(composition.compose(hh.getGroupId(), member));
        assertThat(asMember.capabilities().canRename()).isFalse();
        assertThat(asMember.capabilities().canRemove()).isFalse();
    }

    // ── a named pet ──────────────────────────────────────────────────────

    @Test
    void anOwnerRenamesAPet_andTheHouseholdIsSignalled() {
        as(owner);
        HouseholdPetDto dto = petResource.update(hh.getGroupId(), mochi.getId(), petRename(" Mochi Jr ")).getBody();
        assertThat(dto.name()).isEqualTo("Mochi Jr");
        verify(ws, atLeastOnce()).sendHouseholdDemographic(eq(hh.getGroupId()), any());
    }

    @Test
    void aPetsBlankNameIs400() {
        as(owner);
        assertStatus(() -> petResource.update(hh.getGroupId(), mochi.getId(), petRename("  ")), HttpStatus.BAD_REQUEST);
        assertThat(petRepo.findById(mochi.getId()).orElseThrow().getName()).isEqualTo("Mochi");
    }

    @Test
    void aPlainMemberIs403_onEveryPetEdit() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(member);
        assertStatus(() -> petResource.update(hh.getGroupId(), mochi.getId(), petRename("Nope")), HttpStatus.FORBIDDEN);
        assertStatus(() -> petResource.remove(hh.getGroupId(), mochi.getId(), true), HttpStatus.FORBIDDEN);
        assertStatus(() -> petResource.remove(hh.getGroupId(), mochi.getId(), false), HttpStatus.FORBIDDEN);
        assertThat(petRepo.findById(mochi.getId())).isPresent();
        assertThat(row().getDogs()).isEqualTo(1);
    }

    @Test
    void aPetInAnotherHouseholdIs404() {
        Group other = household(owner, List.of(owner), owner);
        HouseholdPet rex = pet(other.getGroupId(), "Rex", "dog");
        as(owner);
        assertStatus(() -> petResource.update(hh.getGroupId(), rex.getId(), petRename("X")), HttpStatus.NOT_FOUND);
        assertStatus(() -> petResource.remove(hh.getGroupId(), rex.getId(), false), HttpStatus.NOT_FOUND);
        assertThat(petRepo.findById(rex.getId())).isPresent();
    }

    @Test
    void removingAPetsName_keepsTheDogCounted() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(owner);

        petResource.remove(hh.getGroupId(), mochi.getId(), true);

        assertThat(petRepo.findById(mochi.getId())).isEmpty();
        assertThat(row().getDogs()).isEqualTo(1);
        List<Pet> pets = composition.compose(hh.getGroupId(), owner).pets();
        assertThat(pets).hasSize(1);
        assertThat(pets.get(0).kind()).isEqualTo(PetKind.PLACEHOLDER);
        assertThat(pets.get(0).species()).isEqualTo(PetSpecies.DOG);
    }

    @Test
    void removingAPet_lowersTheDogs() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(owner);

        petResource.remove(hh.getGroupId(), mochi.getId(), false);

        assertThat(row().getDogs()).isZero();
        assertThat(composition.compose(hh.getGroupId(), owner).pets()).isEmpty();
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static void assertStatus(Executable call, HttpStatus expected) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    private static HouseholdManualMemberService.UpsertRequest rename(String name) {
        return new HouseholdManualMemberService.UpsertRequest(null, name, null, null, null, null, null);
    }

    private static HouseholdPetService.UpsertRequest petRename(String name) {
        return new HouseholdPetService.UpsertRequest(null, name, null, null, null);
    }

    private static Person manualRow(HouseholdCompositionDto c) {
        return c.people().stream().filter(p -> p.kind() == PersonKind.MANUAL).findFirst().orElseThrow();
    }

    private Demographic row() {
        return demographics.findFirstByHouseholdIdOrderByIdDesc(hh.getGroupId()).orElseThrow();
    }

    private static void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private Group household(String owner, List<String> admins, String... members) {
        Group g = new Group();
        g.setGroupId("hh-" + UUID.randomUUID());
        g.setGroupType("Household");
        g.setGroupName("Test household");
        g.setPrivacy("Private");
        g.setAlert("Not Active");
        g.setOwnerEmail(owner);
        g.setAdminEmails(new ArrayList<>(admins));
        g.setMemberEmails(new ArrayList<>(List.of(members)));
        g.setPendingMemberEmails(new ArrayList<>());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        return groups.save(g);
    }

    private void demo(String hid, int a, int t, int k, int i, int d, int c, int o) {
        Demographic x = new Demographic();
        x.setHouseholdId(hid);
        x.setOwnerEmail(owner);
        x.setAdults(a);
        x.setTeens(t);
        x.setKids(k);
        x.setInfants(i);
        x.setDogs(d);
        x.setCats(c);
        x.setPets(o);
        demographics.save(x);
    }

    private HouseholdManualMember manual(String hid, String name, HouseholdBand band) {
        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(UUID.randomUUID().toString());
        m.setHouseholdId(hid);
        m.setName(name);
        m.setIsAdult(band == HouseholdBand.ADULT);
        m.setBand(band);
        return manualRepo.save(m);
    }

    private HouseholdPet pet(String hid, String name, String species) {
        HouseholdPet p = new HouseholdPet();
        p.setId(UUID.randomUUID().toString());
        p.setHouseholdId(hid);
        p.setName(name);
        p.setSpecies(species);
        return petRepo.save(p);
    }

    // ── support needs leave with the person ──────────────────────────────

    @Test
    void removeFromHousehold_deletesTheirSupportNeedsAndThePlanForThem() {
        as(owner);
        supportFor("manual", chris.getId(), "someone@x.com");

        manualResource.remove(hh.getGroupId(), chris.getId(), false);

        assertThat(supportProfiles.findByHouseholdId(hh.getGroupId())).isEmpty();
        assertThat(supportAssignments.findByHouseholdId(hh.getGroupId())).isEmpty();
    }

    @Test
    void removeName_deletesTheirSupportNeedsToo_aPlaceholderHasNoOneToHangThemOn() {
        demo(hh.getGroupId(), 3, 0, 1, 0, 1, 0, 0);
        as(owner);
        supportFor("manual", chris.getId(), "someone@x.com");

        manualResource.remove(hh.getGroupId(), chris.getId(), true);

        assertThat(supportProfiles.findByHouseholdId(hh.getGroupId())).isEmpty();
        assertThat(supportAssignments.findByHouseholdId(hh.getGroupId())).isEmpty();
    }

    @Test
    void anAccountRemoved_takesTheirSupportNeedsAndTheirHelperRolesWithThem_othersStay() {
        supportFor("user", member, admin);          // member's own needs, admin helps
        supportFor("manual", chris.getId(), member); // member was Chris's helper
        supportFor("user", admin, owner);            // unrelated: stays

        groupService.removeMember(hh.getGroupId(), member);

        assertThat(supportProfiles.findByHouseholdId(hh.getGroupId()))
                .extracting(EmergencySupportProfile::getSubjectId)
                .containsExactlyInAnyOrder(chris.getId(), admin);
        assertThat(supportAssignments.findByHouseholdId(hh.getGroupId()))
                .extracting(EmergencySupportAssignment::getSubjectId)
                .containsExactly(admin);
    }

    private void supportFor(String type, String subjectId, String helperEmail) {
        EmergencySupportProfile p = new EmergencySupportProfile();
        p.setHouseholdId(hh.getGroupId());
        p.setSubjectType(type);
        p.setSubjectId(subjectId);
        p.setNeedsEvacuationAssistance(true);
        supportProfiles.save(p);
        EmergencySupportAssignment a = new EmergencySupportAssignment();
        a.setHouseholdId(hh.getGroupId());
        a.setSubjectType(type);
        a.setSubjectId(subjectId);
        a.setRole(EmergencySupportAssignment.Role.PRIMARY);
        a.setHelperType(EmergencySupportAssignment.HelperType.MEMBER);
        a.setHelperUserEmail(helperEmail);
        supportAssignments.save(a);
    }

}
