package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * BE-06 (G1): the admin write endpoints echoed the raw {@link Group} entity, Stripe
 * ids and ghost/outreach fields included. They now answer the same projection
 * {@code GET /groups/{id}} gives that caller, which keeps every field the shipped
 * clients merge out of these responses (groupName, alert*, logoImageUrl,
 * coverImageUrl, rosters, groupCode) and drops the NEVER set.
 */
class GroupAdminWriteProjectionTest {

    private static final String GROUP_ID = "grp-1";
    private static final String OWNER = "owner@x.com";

    private GroupService groupService;
    private GroupResource resource;
    private Group group;

    @BeforeEach
    void setUp() {
        group = new Group();
        group.setGroupId(GROUP_ID);
        group.setGroupName("Ridge Watch");
        group.setGroupType("Neighborhood");
        group.setGroupCode("RIDGE-1");
        group.setOwnerEmail(OWNER);
        group.setAdminEmails(List.of("admin@x.com"));
        group.setMemberEmails(List.of(OWNER, "admin@x.com", "m@x.com"));
        group.setLogoImageUrl("https://cdn/logo.png");
        group.setCoverImageUrl("https://cdn/cover.png");
        group.setStripeCustomerId("cus_123");
        group.setStripeSubscriptionId("sub_123");

        groupService = mock(GroupService.class);
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(group);
        when(groupService.setAlert(anyString(), anyBoolean(), anyString())).thenReturn(group);
        when(groupService.setLogo(anyString(), any())).thenReturn(group);
        when(groupService.setCover(anyString(), any())).thenReturn(group);
        when(groupService.renameGroup(anyString(), any(), anyString())).thenReturn(group);
        when(groupService.removeMember(anyString(), anyString(), anyString())).thenReturn(group);
        when(groupService.promoteMemberToAdmin(anyString(), anyString(), anyString())).thenReturn(group);
        when(groupService.removeAdmin(anyString(), anyString(), anyString())).thenReturn(group);
        when(groupService.transferOwnershipToMember(anyString(), anyString(), anyString())).thenReturn(group);

        resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", groupService);
        ReflectionTestUtils.setField(resource, "objectMapper", new ObjectMapper().findAndRegisterModules());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                OWNER, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void assertProjected(JsonNode n) {
        assertFalse(n.has("stripeCustomerId"), "raw entity leaked stripeCustomerId: " + n);
        assertFalse(n.has("stripeSubscriptionId"), "raw entity leaked stripeSubscriptionId");
        // What shipped clients merge out of these bodies is still there.
        assertEquals(GROUP_ID, n.get("groupId").asText());
        assertEquals("Ridge Watch", n.get("groupName").asText());
        assertEquals("https://cdn/logo.png", n.get("logoImageUrl").asText());
        assertEquals("https://cdn/cover.png", n.get("coverImageUrl").asText());
        assertTrue(n.has("memberEmails") && n.has("adminEmails") && n.has("groupCode"), "an owner keeps the rosters");
        assertEquals("OWNER", n.get("viewer").get("role").asText());
    }

    @Test
    void everyAdminWriteAnswersTheScopedProjection() {
        assertProjected(resource.setAlert(GROUP_ID, new GroupResource.SetAlertRequest(true)).getBody());
        assertProjected(resource.setLogo(GROUP_ID, Map.of("logoImageUrl", "https://cdn/logo.png")).getBody());
        assertProjected(resource.setCover(GROUP_ID, Map.of("coverImageUrl", "https://cdn/cover.png")).getBody());
        assertProjected(resource.renameGroup(GROUP_ID, Map.of("groupName", "Ridge Watch")).getBody());
        var who = new io.sitprep.sitprepapi.dto.EmailRequest("m@x.com");
        assertProjected(resource.removeMember(GROUP_ID, who).getBody());
        assertProjected(resource.addAdmin(GROUP_ID, who).getBody());
        assertProjected(resource.removeAdmin(GROUP_ID, who).getBody());
        assertProjected(resource.transferOwner(GROUP_ID, who).getBody());
    }
}
