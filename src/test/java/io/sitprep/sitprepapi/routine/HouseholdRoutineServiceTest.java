package io.sitprep.sitprepapi.routine;

import io.sitprep.sitprepapi.constant.HouseholdBand;
import io.sitprep.sitprepapi.domain.EmergencyContact;
import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.domain.EmergencyContactType;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.HouseholdManualMember;
import io.sitprep.sitprepapi.domain.HouseholdMemberBand;
import io.sitprep.sitprepapi.domain.MeetingPlace;
import io.sitprep.sitprepapi.domain.MeetingPlaceTier;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.HouseholdRoutineDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.OptionDto;
import io.sitprep.sitprepapi.dto.HouseholdRoutineDtos.RoutineOfferDto;
import io.sitprep.sitprepapi.exception.GlobalExceptionHandler;
import io.sitprep.sitprepapi.practice.PracticeAvailabilityService;
import io.sitprep.sitprepapi.practice.PracticeCatalog;
import io.sitprep.sitprepapi.practice.PracticeContentControlRepo;
import io.sitprep.sitprepapi.practice.PracticeRoutineTestSupport;
import io.sitprep.sitprepapi.repo.EmergencyContactGroupRepo;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.HouseholdManualMemberRepo;
import io.sitprep.sitprepapi.repo.HouseholdMemberBandRepo;
import io.sitprep.sitprepapi.repo.MeetingPlaceRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.service.ActivationPlanUpdateBroadcastService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EXEC-R1: Save to plan. Real availability + catalog over a fixture ROUTINE, an in-memory routine
 * table, and standalone MockMvc with the real exception handler so the status codes and the
 * ROUTINE_INVALID / STALE_WRITE codes are the ones a client sees.
 */
class HouseholdRoutineServiceTest {

    private static final String HH = "hh-1";
    private static final String KEY = "fixture-routine";
    private static final String OWNER = "owner@example.com";
    private static final String ADMIN = "admin@example.com";
    private static final String MEMBER = "mate@example.com";
    private static final String TEEN = "teen@example.com";
    private static final String NOBODY = "nobody@example.com";
    private static final String OUTSIDER = "stranger@example.com";

    private final List<HouseholdRoutine> table = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    private Group household;
    private MeetingPlaceRepo meetingPlaceRepo;
    private EmergencyContactGroupRepo contactGroupRepo;
    private HouseholdManualMemberRepo manualRepo;
    private HouseholdMemberBandRepo bandRepo;
    private UserInfoRepo userInfoRepo;
    private ActivationPlanUpdateBroadcastService broadcast;
    private HouseholdRoutineService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        household = new Group();
        household.setGroupId(HH);
        household.setGroupType("Household");
        household.setOwnerEmail(OWNER);
        household.setAdminEmails(new ArrayList<>(List.of(OWNER, ADMIN)));
        household.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, MEMBER, TEEN, NOBODY)));
        GroupRepo groups = mock(GroupRepo.class);
        when(groups.findByGroupId(HH)).thenReturn(Optional.of(household));
        when(groups.findByGroupId(anyString())).thenAnswer(inv -> HH.equals(inv.getArgument(0)) ? Optional.of(household) : Optional.empty());

        PracticeCatalog catalog = PracticeRoutineTestSupport.catalogWithFixtureRoutine(KEY, "reconnect");
        PracticeContentControlRepo controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        PracticeAvailabilityService availability = PracticeRoutineTestSupport.availability(catalog, controls, true,
                Clock.fixed(now, ZoneOffset.UTC));

        meetingPlaceRepo = mock(MeetingPlaceRepo.class);
        when(meetingPlaceRepo.findByHouseholdId(HH)).thenReturn(List.of(
                place(1, "Basement storage room", MeetingPlaceTier.INDOOR_SAFE_ROOM),
                place(2, "Mailbox", MeetingPlaceTier.OUTSIDE_HOME),
                place(3, "Hall closet", MeetingPlaceTier.INDOOR_SAFE_ROOM)));
        contactGroupRepo = mock(EmergencyContactGroupRepo.class);
        EmergencyContactGroup cg = new EmergencyContactGroup();
        cg.setId(1L);
        cg.setContacts(new ArrayList<>(List.of(contact(10, "Rosa", EmergencyContactType.OUT_OF_AREA),
                contact(11, "Dr. Lee", EmergencyContactType.MEDICAL), contact(12, "Neighbor Ned", EmergencyContactType.LOCAL))));
        when(contactGroupRepo.findByHouseholdId(HH)).thenReturn(List.of(cg));

        manualRepo = mock(HouseholdManualMemberRepo.class);
        when(manualRepo.findByHouseholdIdOrderByCreatedAtAsc(HH)).thenReturn(List.of(
                manual("m-1", "Grandpa Joe", true, null, null),
                manual("m-2", "Maya Kid", false, 9, null),
                manual("m-3", "Tom Teen", false, 15, null),
                manual("m-4", "Baby Bo", false, 1, null),
                manual("m-5", "Nia Mislabeled", true, null, HouseholdBand.KID)));
        bandRepo = mock(HouseholdMemberBandRepo.class);
        HouseholdMemberBand teenBand = new HouseholdMemberBand();
        teenBand.setHouseholdId(HH);
        teenBand.setUserEmail(TEEN);
        teenBand.setBand(HouseholdBand.TEEN);
        when(bandRepo.findByHouseholdId(HH)).thenReturn(List.of(teenBand));
        userInfoRepo = mock(UserInfoRepo.class);
        when(userInfoRepo.findByUserEmailIn(anyList())).thenReturn(List.of(
                user("u-1", OWNER, "Olive Owner"), user("u-2", ADMIN, "Adam"), user("u-3", MEMBER, "Sam"),
                user("u-4", TEEN, "Tia"), user("u-5", NOBODY, "  ")));

        HouseholdRoutineRepo repo = inMemoryRepo();
        service = new HouseholdRoutineService(repo, new HouseholdAccessService(groups), availability, catalog, groups,
                meetingPlaceRepo, contactGroupRepo, manualRepo, bandRepo, userInfoRepo, Clock.fixed(now, ZoneOffset.UTC));
        broadcast = mock(ActivationPlanUpdateBroadcastService.class);
        service.setPlanBroadcast(broadcast);
        mvc = MockMvcBuilders.standaloneSetup(new HouseholdRoutineResource(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON)).build();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // -- fixtures ------------------------------------------------------------

    private static MeetingPlace place(long id, String name, MeetingPlaceTier tier) {
        MeetingPlace p = new MeetingPlace();
        p.setId(id);
        p.setName(name);
        p.setMeetingTier(tier);
        return p;
    }

    private static EmergencyContact contact(long id, String name, EmergencyContactType type) {
        EmergencyContact c = new EmergencyContact();
        c.setId(id);
        c.setName(name);
        c.setContactType(type);
        return c;
    }

    private static HouseholdManualMember manual(String id, String name, boolean adult, Integer age, HouseholdBand band) {
        HouseholdManualMember m = new HouseholdManualMember();
        m.setId(id);
        m.setHouseholdId(HH);
        m.setName(name);
        m.setIsAdult(adult);
        m.setAge(age);
        m.setBand(band);
        return m;
    }

    private static UserInfo user(String id, String email, String first) {
        UserInfo u = new UserInfo();
        u.setId(id);
        u.setUserEmail(email);
        u.setUserFirstName(first);
        return u;
    }

    private HouseholdRoutineRepo inMemoryRepo() {
        HouseholdRoutineRepo repo = mock(HouseholdRoutineRepo.class);
        when(repo.saveAndFlush(any())).thenAnswer(inv -> {
            HouseholdRoutine r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(ids.getAndIncrement());
                table.add(r);
            }
            return r;
        });
        when(repo.findByHouseholdIdAndRoutineKey(anyString(), anyString())).thenAnswer(inv -> table.stream()
                .filter(r -> r.getHouseholdId().equals(inv.getArgument(0)) && r.getRoutineKey().equals(inv.getArgument(1)))
                .findFirst());
        when(repo.findByHouseholdId(anyString())).thenAnswer(inv -> table.stream()
                .filter(r -> r.getHouseholdId().equals(inv.getArgument(0))).toList());
        when(repo.deleteByHouseholdIdAndRoutineKey(anyString(), anyString())).thenAnswer(inv -> {
            long before = table.size();
            table.removeIf(r -> r.getHouseholdId().equals(inv.getArgument(0)) && r.getRoutineKey().equals(inv.getArgument(1)));
            return before - table.size();
        });
        return repo;
    }

    private void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private MockHttpServletRequestBuilder save(String body) {
        return put("/api/households/" + HH + "/routines/" + KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String slots) {
        return "{\"contentVersion\":1,\"slots\":" + slots + "}";
    }

    // -- offer: options come from the plan ------------------------------------

    @Test
    void theOfferResolvesEverySlotFromThePlan() {
        RoutineOfferDto offer = service.offer(HH, KEY, MEMBER);
        assertThat(offer.routineKey()).isEqualTo(KEY);
        assertThat(offer.contentVersion()).isEqualTo(1);
        assertThat(offer.ifText()).isEqualTo("If the fixture happens");
        assertThat(offer.canSave()).isFalse();
        assertThat(offer.saved()).isNull();
        assertThat(offer.slots()).extracting(s -> s.key()).containsExactly("who", "contact", "when", "place", "route");

        var places = offer.slots().get(3);
        assertThat(places.options()).extracting(OptionDto::value).containsExactly("Basement storage room", "Hall closet");
        assertThat(places.options()).extracting(OptionDto::refId).containsExactly("place:1", "place:3");
        assertThat(places.allowText()).isTrue();
        assertThat(places.fallback()).isEqualTo("our safe room");

        var contacts = offer.slots().get(1);
        assertThat(contacts.options()).extracting(OptionDto::value).containsExactly("Rosa");
        assertThat(contacts.options().get(0).refId()).isEqualTo("contact:10");

        var when = offer.slots().get(2);
        assertThat(when.options()).extracting(OptionDto::value).containsExactly("right away", "after we are safe");
        assertThat(when.options()).extracting(OptionDto::refId).containsOnlyNulls();
        assertThat(when.allowText()).isFalse();

        assertThat(offer.slots().get(4).options()).isEmpty();
        assertThat(offer.slots().get(4).allowText()).isTrue();
    }

    @Test
    void adultOptionsAreAdultsOnlyPlusAGrownUp() {
        var who = service.offer(HH, KEY, OWNER).slots().get(0);
        assertThat(who.options()).extracting(OptionDto::value)
                .containsExactly("Olive", "Adam", "Sam", "Grandpa", "a grown-up");
        assertThat(who.options()).extracting(OptionDto::refId)
                .containsExactly("user:u-1", "user:u-2", "user:u-3", "manual:m-1", null);
        // Never a child, a teen account, an infant, a mislabeled minor, or a member with no first name.
        assertThat(who.options()).extracting(OptionDto::value)
                .doesNotContain("Maya", "Tom", "Baby", "Nia", "Tia");
    }

    @Test
    void petsAreNeverPeople() {
        // The roster the offer reads has no pet source at all.
        verify(meetingPlaceRepo, never()).findAll();
        assertThat(service.offer(HH, KEY, OWNER).slots().get(0).options()).extracting(OptionDto::value).doesNotContain("Rex");
    }

    @Test
    void theOfferTellsAnAdminTheyCanSave() {
        assertThat(service.offer(HH, KEY, ADMIN).canSave()).isTrue();
        assertThat(service.offer(HH, KEY, OWNER).canSave()).isTrue();
        assertThat(service.offer(HH, KEY, MEMBER).canSave()).isFalse();
    }

    @Test
    void anOutsiderCannotReadAndAnUnknownRoutineOrHouseholdIs404() throws Exception {
        as(OUTSIDER);
        mvc.perform(get("/api/households/" + HH + "/routines/" + KEY + "/offer")).andExpect(status().isForbidden());
        as(MEMBER);
        mvc.perform(get("/api/households/" + HH + "/routines/no-such-routine/offer")).andExpect(status().isNotFound());
        mvc.perform(get("/api/households/nope/routines/" + KEY + "/offer")).andExpect(status().isForbidden());
        mvc.perform(get("/api/households/" + HH + "/routines/" + KEY + "/offer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routineKey").value(KEY))
                .andExpect(jsonPath("$.slots[0].options[0].value").value("Olive"))
                .andExpect(jsonPath("$.canSave").value(false));
    }

    // -- save -------------------------------------------------------------------

    @Test
    void saveUpsertsRendersServerSideAndAnnounces() throws Exception {
        as(ADMIN);
        mvc.perform(save(body("{\"who\":{\"value\":\"Sam\",\"refId\":\"user:u-3\"},\"place\":{\"value\":\"Hall closet\",\"refId\":\"place:3\"},"
                        + "\"when\":{\"value\":\"right away\"},\"route\":{\"value\":\"  Ridge Rd  \"}}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routineKey").value(KEY))
                .andExpect(jsonPath("$.hazard").value("fixture"))
                .andExpect(jsonPath("$.title").value("Fixture routine"))
                .andExpect(jsonPath("$.ifText").value("If the fixture happens"))
                .andExpect(jsonPath("$.thenText").value("Sam calls our out-of-area contact right away; we go to Hall closet and take Ridge Rd."))
                .andExpect(jsonPath("$.contentVersion").value(1))
                .andExpect(jsonPath("$.slots[0].key").value("who"))
                .andExpect(jsonPath("$.slots[0].refId").value("user:u-3"))
                .andExpect(jsonPath("$.slots[3].value").value("Ridge Rd"));
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getCreatedByEmail()).isEqualTo(ADMIN);
        verify(broadcast).broadcastHouseholdPlanChangedAfterCommit(HH, "routines");

        // A second save is an update of the same row, not a second row.
        as(OWNER);
        mvc.perform(save(body("{\"who\":{\"value\":\"a grown-up\"}}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.thenText").value("A grown-up calls our out-of-area contact right away; we go to our safe room and take our backup route."));
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getCreatedByEmail()).isEqualTo(ADMIN);
        assertThat(table.get(0).getUpdatedByEmail()).isEqualTo(OWNER);
        assertThat(table.get(0).getSlotValues()).containsOnlyKeys("who");
    }

    @Test
    void anEmptySaveKeepsEveryFallback() throws Exception {
        as(OWNER);
        mvc.perform(save("{\"contentVersion\":1}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.thenText").value("A grown-up calls our out-of-area contact right away; we go to our safe room and take our backup route."))
                .andExpect(jsonPath("$.slots").isEmpty());
    }

    @Test
    void onlyAnAdminMaySave() throws Exception {
        as(MEMBER);
        mvc.perform(save(body("{}"))).andExpect(status().isForbidden());
        as(OUTSIDER);
        mvc.perform(save(body("{}"))).andExpect(status().isForbidden());
        assertThat(table).isEmpty();
        verify(broadcast, never()).broadcastHouseholdPlanChangedAfterCommit(anyString(), anyString());
    }

    @Test
    void unknownFieldsAreRefusedAtBothLevels() throws Exception {
        as(OWNER);
        for (String bad : List.of(
                "{\"contentVersion\":1,\"slots\":{},\"role\":\"CHECKER\"}",
                "{\"contentVersion\":1,\"slots\":{},\"runId\":4}",
                "{\"contentVersion\":1,\"slots\":{},\"turns\":[]}",
                "{\"contentVersion\":1,\"slots\":{},\"childName\":\"Maya\"}",
                "{\"contentVersion\":1,\"slots\":{\"who\":{\"value\":\"Sam\",\"role\":\"HEAD_COUNTER\"}}}",
                "{\"contentVersion\":1,\"slots\":{\"who\":{\"value\":\"Sam\",\"childId\":\"m-2\"}}}")) {
            mvc.perform(save(bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ROUTINE_INVALID"));
        }
        assertThat(table).isEmpty();
    }

    @Test
    void malformedBodiesAreRoutineInvalid() throws Exception {
        as(OWNER);
        for (String bad : List.of(
                "{}", "{\"contentVersion\":\"1\"}", "{\"contentVersion\":1,\"slots\":[]}",
                "{\"contentVersion\":1,\"slots\":{\"who\":\"Sam\"}}",
                "{\"contentVersion\":1,\"slots\":{\"who\":{\"value\":7}}}",
                "{\"contentVersion\":1,\"slots\":{\"who\":{\"value\":\"Sam\",\"refId\":7}}}",
                "{\"contentVersion\":1,\"slots\":{\"nobody\":{\"value\":\"Sam\"}}}")) {
            mvc.perform(save(bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ROUTINE_INVALID"));
        }
        mvc.perform(put("/api/households/" + HH + "/routines/" + KEY)).andExpect(status().isBadRequest());
        assertThat(table).isEmpty();
    }

    @Test
    void aRefIdFromAnotherHouseholdOrAnotherSlotIsRefused() throws Exception {
        as(OWNER);
        for (String slots : List.of(
                "{\"place\":{\"value\":\"Mailbox\",\"refId\":\"place:99\"}}",        // another household's place
                "{\"place\":{\"value\":\"Mailbox\",\"refId\":\"place:2\"}}",         // this household's, but the wrong tier
                "{\"contact\":{\"value\":\"Dr. Lee\",\"refId\":\"contact:11\"}}",    // wrong contact type
                "{\"who\":{\"value\":\"Maya\",\"refId\":\"manual:m-2\"}}",           // a child is not an option
                "{\"who\":{\"value\":\"Tia\",\"refId\":\"user:u-4\"}}",              // a teen account is not an option
                "{\"who\":{\"value\":\"Sam\",\"refId\":\"user:someone-else\"}}",
                "{\"who\":{\"value\":\"Olive\",\"refId\":\"user:u-3\"}}",            // refId names a different person
                "{\"route\":{\"value\":\"Ridge Rd\",\"refId\":\"place:1\"}}",        // TEXT takes no refId
                "{\"when\":{\"value\":\"right away\",\"refId\":\"place:1\"}}")) {    // CHOICE takes no refId
            mvc.perform(save(body(slots))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ROUTINE_INVALID"));
        }
        assertThat(table).isEmpty();
    }

    @Test
    void aChoiceOutsideItsOptionsIsRefused() throws Exception {
        as(OWNER);
        mvc.perform(save(body("{\"when\":{\"value\":\"whenever\"}}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ROUTINE_INVALID"));
        mvc.perform(save(body("{\"when\":{\"value\":\"after we are safe\"}}"))).andExpect(status().isOk());
    }

    @Test
    void valuesAreTrimmedBoundedAndFreeOfControlCharacters() throws Exception {
        as(OWNER);
        for (String value : List.of("   ", "", "x".repeat(81), "line\\nbreak", "tab\\there", "nul\\u0000", "sep\\u2028x", "bidi\\u202Ex", "zw\\u200Bx")) {
            mvc.perform(save(body("{\"route\":{\"value\":\"" + value + "\"}}"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ROUTINE_INVALID"));
        }
        mvc.perform(save(body("{\"route\":{\"value\":\"" + "x".repeat(80) + "\"}}"))).andExpect(status().isOk());
        // A single braces-bearing value cannot be re-expanded into another slot.
        mvc.perform(save(body("{\"route\":{\"value\":\"{who}\"}}"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.thenText").value(org.hamcrest.Matchers.endsWith("take {who}.")));
    }

    @Test
    void aTooLongSavedLineIsRefusedNotTruncated() throws Exception {
        as(OWNER);
        String slots = "{\"who\":{\"value\":\"" + "w".repeat(80) + "\"},\"contact\":{\"value\":\"" + "c".repeat(80)
                + "\"},\"place\":{\"value\":\"" + "p".repeat(80) + "\"},\"route\":{\"value\":\"" + "r".repeat(80) + "\"}}";
        mvc.perform(save(body(slots))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ROUTINE_INVALID"));
    }

    @Test
    void aStaleContentVersionIs409() throws Exception {
        as(OWNER);
        mvc.perform(save("{\"contentVersion\":2,\"slots\":{}}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STALE_WRITE"));
        assertThat(table).isEmpty();
    }

    @Test
    void anUnservableRoutineIs404ForSaveAndOffer() throws Exception {
        as(OWNER);
        mvc.perform(put("/api/households/" + HH + "/routines/no-such-routine").contentType(MediaType.APPLICATION_JSON)
                        .content(body("{}")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/households/nope/routines/" + KEY).contentType(MediaType.APPLICATION_JSON).content(body("{}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDraftRoutineIsNotServableWithoutThePreviewGate() {
        PracticeCatalog catalog = PracticeRoutineTestSupport.catalogWithFixtureRoutine(KEY, "reconnect");
        PracticeContentControlRepo controls = mock(PracticeContentControlRepo.class);
        when(controls.findAll()).thenReturn(List.of());
        when(controls.findById(any())).thenReturn(Optional.empty());
        GroupRepo groups = mock(GroupRepo.class);
        when(groups.findByGroupId(HH)).thenReturn(Optional.of(household));
        HouseholdRoutineService production = new HouseholdRoutineService(inMemoryRepo(), new HouseholdAccessService(groups),
                PracticeRoutineTestSupport.availability(catalog, controls, false, Clock.fixed(now, ZoneOffset.UTC)),
                catalog, groups, meetingPlaceRepo, contactGroupRepo, manualRepo, bandRepo, userInfoRepo, Clock.fixed(now, ZoneOffset.UTC));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> production.offer(HH, KEY, OWNER))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // -- the saved row, the plan list, delete ---------------------------------------

    @Test
    void theOfferShowsWhatWasSavedAndThePlanListsItByHazardThenTitle() throws Exception {
        as(OWNER);
        mvc.perform(save(body("{\"who\":{\"value\":\"Sam\",\"refId\":\"user:u-3\"}}"))).andExpect(status().isOk());
        HouseholdRoutineDto saved = service.offer(HH, KEY, MEMBER).saved();
        assertThat(saved.thenText()).startsWith("Sam calls");
        assertThat(service.listForPlan(HH)).extracting(HouseholdRoutineDto::routineKey).containsExactly(KEY);
        assertThat(service.listForPlan("other-household")).isEmpty();

        // Sorted by hazard, then title.
        for (var r : List.of(row("b-routine", "tornado", "Zed"), row("c-routine", "flood", "Mid"), row("d-routine", "flood", "alpha"))) {
            table.add(r);
        }
        assertThat(service.listForPlan(HH)).extracting(HouseholdRoutineDto::routineKey)
                .containsExactly(KEY, "d-routine", "c-routine", "b-routine");
    }

    private HouseholdRoutine row(String key, String hazard, String ifText) {
        HouseholdRoutine r = new HouseholdRoutine();
        r.setId(ids.getAndIncrement());
        r.setHouseholdId(HH);
        r.setRoutineKey(key);
        r.setContentVersion(1);
        r.setHazardKey(hazard);
        r.setIfText(ifText);
        r.setThenText("then");
        r.setUpdatedAt(now);
        return r;
    }

    @Test
    void aSavedLineStaysWhatTheHouseholdSavedEvenIfTheTemplateIsGone() {
        HouseholdRoutine r = row("retired-routine", "flood", "If water rises");
        table.add(r);
        HouseholdRoutineDto dto = service.listForPlan(HH).stream().filter(d -> d.routineKey().equals("retired-routine")).findFirst().orElseThrow();
        assertThat(dto.ifText()).isEqualTo("If water rises");
        assertThat(dto.thenText()).isEqualTo("then");
        assertThat(dto.title()).isEqualTo("If water rises");
    }

    @Test
    void deleteIs204AdminOnlyAndIdempotent() throws Exception {
        as(OWNER);
        mvc.perform(save(body("{}"))).andExpect(status().isOk());
        as(MEMBER);
        mvc.perform(delete("/api/households/" + HH + "/routines/" + KEY)).andExpect(status().isForbidden());
        assertThat(table).hasSize(1);

        as(ADMIN);
        mvc.perform(delete("/api/households/" + HH + "/routines/" + KEY)).andExpect(status().isNoContent());
        assertThat(table).isEmpty();
        verify(broadcast, org.mockito.Mockito.times(2)).broadcastHouseholdPlanChangedAfterCommit(eq(HH), eq("routines"));
        // Removing it again is still a 204, and announces nothing.
        mvc.perform(delete("/api/households/" + HH + "/routines/" + KEY)).andExpect(status().isNoContent());
        verify(broadcast, org.mockito.Mockito.times(2)).broadcastHouseholdPlanChangedAfterCommit(eq(HH), eq("routines"));
        mvc.perform(delete("/api/households/nope/routines/" + KEY)).andExpect(status().isForbidden());
    }

    @Test
    void theRoutesAreTheContractsAndSaveIsIdempotentMarked() throws Exception {
        var save = HouseholdRoutineResource.class.getDeclaredMethod("save", String.class, String.class, com.fasterxml.jackson.databind.JsonNode.class);
        assertThat(save.isAnnotationPresent(io.sitprep.sitprepapi.web.Idempotent.class)).isTrue();
        assertThat(save.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class).value()).containsExactly("/{routineKey}");
        assertThat(HouseholdRoutineResource.class.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value())
                .containsExactly("/api/households/{householdId}/routines");
    }
}
