package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-02 + BE-08: who an admin op may target.
 *
 * <p>Add-admin used to enrol any registered email as admin and member with no
 * consent; transfer went to any email; an admin could remove the owner or any
 * other admin; and removeAdmin(owner) threw a bare SecurityException that
 * rendered as a 500.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupAdminTargetTest {

    @Mock GroupRepo groupRepo;
    @Mock UserInfoRepo userInfoRepo;
    @Mock WebSocketMessageSender ws;
    @Mock HouseholdEventService householdEventService;
    @Mock NotificationService notificationService;
    @Mock CheckInRequestService checkInRequestService;

    private GroupService service;
    private Group g;

    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String ADMIN2 = "admin2@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String STRANGER = "stranger@x.com";

    @BeforeEach
    void setUp() {
        service = new GroupService(groupRepo, userInfoRepo, ws, householdEventService,
                notificationService, checkInRequestService);
        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        when(groupRepo.saveAndFlush(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        when(userInfoRepo.findByUserEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        g = new Group();
        g.setGroupId("g-1");
        g.setGroupName("Grace Church");
        g.setGroupType("Church");
        g.setOwnerEmail(OWNER);
        g.setAdminEmails(new ArrayList<>(List.of(OWNER, ADMIN, ADMIN2)));
        g.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, ADMIN2, MEMBER)));
        when(groupRepo.findByGroupId("g-1")).thenReturn(Optional.of(g));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void assertStatus(Runnable r, HttpStatus expected) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    // ---- BE-02 ----

    @Test
    void addAdmin_nonMember_is409_andNobodyIsEnrolled() {
        assertStatus(() -> service.promoteMemberToAdmin("g-1", STRANGER, ADMIN), HttpStatus.CONFLICT);
        assertThat(g.getMemberEmails()).doesNotContain(STRANGER);
        assertThat(g.getAdminEmails()).doesNotContain(STRANGER);
        verify(groupRepo, never()).save(any());
    }

    @Test
    void addAdmin_existingMember_isPromoted_withANormalizedEmail() {
        Group out = service.promoteMemberToAdmin("g-1", "  Member@X.com ", ADMIN);
        assertThat(out.getAdminEmails()).contains(MEMBER);
        assertThat(out.getMemberEmails()).containsExactly(OWNER, ADMIN, ADMIN2, MEMBER);
    }

    @Test
    void addAdmin_byAPlainMember_is403() {
        assertStatus(() -> service.promoteMemberToAdmin("g-1", MEMBER, MEMBER), HttpStatus.FORBIDDEN);
    }

    @Test
    void transfer_toNonMember_is409_andTheOwnerIsUnchanged() {
        assertStatus(() -> service.transferOwnershipToMember("g-1", STRANGER, OWNER), HttpStatus.CONFLICT);
        assertThat(g.getOwnerEmail()).isEqualTo(OWNER);
        verify(groupRepo, never()).save(any());
    }

    @Test
    void transfer_byAnAdmin_is403() {
        assertStatus(() -> service.transferOwnershipToMember("g-1", MEMBER, ADMIN), HttpStatus.FORBIDDEN);
    }

    @Test
    void transfer_toAMember_movesOwnership_andTellsThem() {
        Group out = service.transferOwnershipToMember("g-1", MEMBER, OWNER);
        assertThat(out.getOwnerEmail()).isEqualTo(MEMBER);
        assertThat(out.getAdminEmails()).contains(MEMBER);
        verify(notificationService).deliverPresenceAware(eq(MEMBER), eq("You're the owner now"),
                anyString(), anyString(), any(), eq("new_member"), eq("g-1"), anyString(), any(), any());
    }

    // ---- BE-08 ----

    @Test
    void removingTheOwner_is409() {
        assertStatus(() -> service.removeMember("g-1", OWNER, ADMIN), HttpStatus.CONFLICT);
        assertStatus(() -> service.removeMember("g-1", OWNER, OWNER), HttpStatus.CONFLICT);
        assertThat(g.getMemberEmails()).contains(OWNER);
    }

    @Test
    void anAdminRemovingAnotherAdmin_is403_butTheOwnerMay() {
        assertStatus(() -> service.removeMember("g-1", ADMIN2, ADMIN), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.removeAdmin("g-1", ADMIN2, ADMIN), HttpStatus.FORBIDDEN);
        assertThat(g.getAdminEmails()).contains(ADMIN2);

        Group out = service.removeMember("g-1", ADMIN2, OWNER);
        assertThat(out.getMemberEmails()).doesNotContain(ADMIN2);
        assertThat(out.getAdminEmails()).doesNotContain(ADMIN2);
    }

    @Test
    void anAdminMayStillRemoveAPlainMember_orStepDownThemselves() {
        assertThat(service.removeMember("g-1", MEMBER, ADMIN).getMemberEmails()).doesNotContain(MEMBER);
        assertThat(service.removeAdmin("g-1", ADMIN, ADMIN).getAdminEmails()).doesNotContain(ADMIN);
    }

    @Test
    void removeAdmin_ofTheOwner_overHttp_is403_not500() throws Exception {
        GroupResource resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(resource)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        mvc.perform(post("/api/groups/g-1/admins/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER + "\"}"))
                .andExpect(status().isForbidden());
        assertThat(g.getAdminEmails()).contains(OWNER);
    }
}
