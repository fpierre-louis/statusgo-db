package io.sitprep.sitprepapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GroupSettingsRequest;
import io.sitprep.sitprepapi.exception.GlobalExceptionHandler;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.resource.GroupResource;
import io.sitprep.sitprepapi.websocket.WebSocketMessageSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AD-01: {@code PATCH /groups/{id}/settings} changes the settings sent and
 * nothing else.
 *
 * <p>The settings Save used to PUT a whole group read at page mount, so a typo
 * fix could end a check-in another admin had started (or restart an ended one
 * and push everyone again), revert roster changes, and undo a subgroup link
 * made on the same page. These pin each of those, plus the household guard.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupSettingsPatchTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;
    @Mock CheckInRequestService checkInRequestService;
    @Mock LiveActivityPushService liveActivities;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private GroupService service;

    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService,
                notificationService, checkInRequestService);
        service.setLiveActivities(liveActivities);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Group org(String id) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName("Grace Church");
        g.setGroupType("Church");
        g.setPrivacy("Private");
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(new ArrayList<>(List.of(OWNER, ADMIN)));
        g.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, MEMBER)));
        g.setPendingMemberEmails(new ArrayList<>(List.of("asker@x.com")));
        g.setSubGroupIDs(new ArrayList<>(List.of("youth")));
        g.setParentGroupIDs(new ArrayList<>(List.of("diocese")));
        g.setGroupCode("g-abc12345");
        g.setLastUpdatedBy(OWNER);
        when(groupRepo.findByGroupId(id)).thenReturn(Optional.of(g));
        return g;
    }

    private Group household(String id) {
        Group g = org(id);
        g.setGroupName("The Ruiz household");
        g.setGroupType("Household");
        g.setSubGroupIDs(new ArrayList<>());
        g.setParentGroupIDs(new ArrayList<>());
        return g;
    }

    private GroupSettingsRequest settings(String json) throws Exception {
        return GroupSettingsRequest.parse(mapper.readTree(json), GroupSettingsRequest.SETTINGS_KEYS);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    // ── the reasons the endpoint exists ──────────────────────────────────────

    @Test
    void aSaveDuringAnActiveCheckInLeavesItActive() throws Exception {
        Group g = org("g-1");
        Instant started = Instant.parse("2026-10-14T15:00:00Z");
        g.setAlert("Active");
        g.setAlertActivatedAt(started);
        g.setAlertExpiresAt(started.plusSeconds(48 * 3600));
        g.setCheckInRemindersFired(2);
        g.setLastUpdatedBy(ADMIN); // the admin who started it

        Group saved = service.updateSettings("g-1", settings("{\"description\":\"Sunday 9am and 11am\"}"), OWNER);

        assertThat(saved.getDescription()).isEqualTo("Sunday 9am and 11am");
        assertThat(saved.getAlert()).isEqualTo("Active");
        assertThat(saved.getAlertActivatedAt()).isEqualTo(started);
        assertThat(saved.getAlertExpiresAt()).isEqualTo(started.plusSeconds(48 * 3600));
        assertThat(saved.getCheckInRemindersFired()).isEqualTo(2);
        // The alert frame reads lastUpdatedBy as "who started the check-in".
        assertThat(saved.getLastUpdatedBy()).isEqualTo(ADMIN);
        verifyNoInteractions(notificationService, householdEventService, checkInRequestService, liveActivities);
        verify(ws, never()).sendGroupAlertStatus(anyString(), any());
    }

    @Test
    void aSaveAfterTheCheckInEndedDoesNotRestartIt() throws Exception {
        Group g = org("g-1");
        g.setAlert("Inactive");
        service.updateSettings("g-1", settings("{\"privacy\":\"public\"}"), ADMIN);
        assertThat(g.getAlert()).isEqualTo("Inactive");
        assertThat(g.getPrivacy()).isEqualTo("Public");
        verifyNoInteractions(notificationService, checkInRequestService);
    }

    @Test
    void rostersLinksCodeAndOwnerAreUntouched() throws Exception {
        Group g = org("g-1");
        Group saved = service.updateSettings("g-1", settings(
                "{\"address\":\"1 Main St, Lehi, UT 84043\",\"latitude\":40.39,\"longitude\":-111.85,"
                        + "\"zipCode\":\"84043\",\"groupType\":\"Religious Organization\","
                        + "\"businessCategory\":null,\"websiteUrl\":\"https://grace.example\"}"), ADMIN);

        assertThat(saved.getAddress()).isEqualTo("1 Main St, Lehi, UT 84043");
        assertThat(saved.getLatitude()).isEqualTo(40.39);
        assertThat(saved.getLongitude()).isEqualTo(-111.85);
        assertThat(saved.getGroupType()).isEqualTo("Religious Organization");
        assertThat(saved.getWebsiteUrl()).isEqualTo("https://grace.example");
        assertThat(saved.getOwnerEmail()).isEqualTo(OWNER);
        assertThat(saved.getAdminEmails()).containsExactly(OWNER, ADMIN);
        assertThat(saved.getMemberEmails()).containsExactly(OWNER, ADMIN, MEMBER);
        assertThat(saved.getPendingMemberEmails()).containsExactly("asker@x.com");
        assertThat(saved.getSubGroupIDs()).containsExactly("youth");
        assertThat(saved.getParentGroupIDs()).containsExactly("diocese");
        assertThat(saved.getGroupCode()).isEqualTo("g-abc12345");
        assertThat(saved.getGroupName()).isEqualTo("Grace Church");
    }

    @Test
    void onlyTheKeysSentChange() throws Exception {
        Group g = org("g-1");
        g.setDescription("Keep me");
        g.setAddress("Keep this too");
        service.updateSettings("g-1", settings("{\"zipCode\":\"84003\"}"), ADMIN);
        assertThat(g.getDescription()).isEqualTo("Keep me");
        assertThat(g.getAddress()).isEqualTo("Keep this too");
        assertThat(g.getZipCode()).isEqualTo("84003");
        // A blank value clears.
        service.updateSettings("g-1", settings("{\"description\":\"  \"}"), ADMIN);
        assertThat(g.getDescription()).isNull();
    }

    // ── the household privacy guard ──────────────────────────────────────────

    @Test
    void aHouseholdCantBeMadePublicOrRetyped() throws Exception {
        Group h = household("hh-1");
        assertStatus(() -> call(() -> service.updateSettings("hh-1", settings("{\"privacy\":\"Public\"}"), OWNER)),
                HttpStatus.CONFLICT);
        assertStatus(() -> call(() -> service.updateSettings("hh-1", settings("{\"groupType\":\"Church\"}"), OWNER)),
                HttpStatus.CONFLICT);
        assertStatus(() -> call(() -> service.updateSettings("hh-1", settings("{\"websiteUrl\":\"x.com\"}"), OWNER)),
                HttpStatus.BAD_REQUEST);
        assertThat(h.getPrivacy()).isEqualTo("Private");
        assertThat(h.getGroupType()).isEqualTo("Household");
        verify(groupRepo, never()).save(any(Group.class));
        // Saying Private (or the same type) is fine.
        service.updateSettings("hh-1", settings("{\"privacy\":\"private\",\"groupType\":\"household\"}"), OWNER);
        assertThat(h.getPrivacy()).isEqualTo("Private");
        assertThat(h.getGroupType()).isEqualTo("Household");
    }

    @Test
    void anOrgCantBecomeAHousehold() throws Exception {
        org("g-1");
        assertStatus(() -> call(() -> service.updateSettings("g-1", settings("{\"groupType\":\"Household\"}"), OWNER)),
                HttpStatus.CONFLICT);
    }

    // ── gates and values ─────────────────────────────────────────────────────

    @Test
    void aPlainMemberIsRefused() throws Exception {
        org("g-1");
        assertStatus(() -> call(() -> service.updateSettings("g-1", settings("{\"description\":\"x\"}"), MEMBER)),
                HttpStatus.FORBIDDEN);
        verify(groupRepo, never()).save(any(Group.class));
    }

    @Test
    void badValuesAre400s() throws Exception {
        org("g-1");
        for (String body : List.of(
                "{\"privacy\":\"friends-only\"}",
                "{\"latitude\":40.1}",
                "{\"latitude\":140,\"longitude\":10}",
                "{\"websiteUrl\":\"javascript:alert(1)\"}",
                "{\"description\":\"" + "x".repeat(256) + "\"}")) {
            assertStatus(() -> call(() -> service.updateSettings("g-1", settings(body), OWNER)), HttpStatus.BAD_REQUEST);
        }
        verify(groupRepo, never()).save(any(Group.class));
    }

    @Test
    void householdDetailsWritesNameNoteAndPlaceOnly() throws Exception {
        Group h = household("hh-1");
        h.setAlert("Active");
        var req = GroupSettingsRequest.parse(mapper.readTree(
                "{\"groupName\":\"  The Ruiz family \",\"description\":\"Back door sticks\","
                        + "\"address\":\"9 Elm\",\"latitude\":40.0,\"longitude\":-111.0,\"zipCode\":\"84043\"}"),
                GroupSettingsRequest.HOUSEHOLD_DETAIL_KEYS);
        Group saved = service.updateHouseholdDetails("hh-1", req, OWNER);
        assertThat(saved.getGroupName()).isEqualTo("The Ruiz family");
        assertThat(saved.getDescription()).isEqualTo("Back door sticks");
        assertThat(saved.getAddress()).isEqualTo("9 Elm");
        assertThat(saved.getAlert()).isEqualTo("Active");
        assertThat(saved.getMemberEmails()).containsExactly(OWNER, ADMIN, MEMBER);
        // An org is told to use /settings.
        org("g-1");
        assertStatus(() -> call(() -> service.updateHouseholdDetails("g-1", req, OWNER)), HttpStatus.CONFLICT);
    }

    // ── through the endpoint ─────────────────────────────────────────────────

    @Test
    void aWholeGroupBodyIsRefusedWithTheKeysNamed() throws Exception {
        org("g-1");
        MockMvc mvc = mvc();
        signIn(OWNER);
        mvc.perform(patch("/api/groups/g-1/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\",\"alert\":\"Inactive\",\"memberEmails\":[]}"))
                .andExpect(status().isBadRequest());
        verify(groupRepo, never()).save(any(Group.class));
    }

    @Test
    void theEndpointAnswersTheCallersProjection() throws Exception {
        Group g = org("g-1");
        g.setAlert("Active");
        signIn(ADMIN);
        // Called directly, like GroupPageDtoScopeTest: standalone MockMvc's
        // converters render a returned JsonNode as a string.
        JsonNode page = resource().updateSettings("g-1", mapper.readTree("{\"description\":\"Doors at 8:45\"}"));
        assertThat(page.path("description").asText()).isEqualTo("Doors at 8:45");
        assertThat(page.path("alert").asText()).isEqualTo("Active");
        assertThat(page.path("viewer").path("role").asText()).isEqualTo("ADMIN");
    }

    @Test
    void namesAreNoLongerCheckedForUniqueness() {
        // JN-16 / D21: a name is not an identity, and the global check told
        // anyone who typed it that a family's household name existed.
        signIn(OWNER);
        var answer = resource().checkAvailability("The Ruiz household", null);
        assertThat(answer).doesNotContainKey("nameTaken").isEmpty();
        verifyNoInteractions(groupRepo);
    }

    private GroupResource resource() {
        GroupResource resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", service);
        ReflectionTestUtils.setField(resource, "objectMapper", mapper);
        return resource;
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(resource())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static void signIn(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /** Lets a checked-exception lambda run inside assertStatus. */
    private interface Call { void run() throws Exception; }

    private static void call(Call c) {
        try {
            c.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
