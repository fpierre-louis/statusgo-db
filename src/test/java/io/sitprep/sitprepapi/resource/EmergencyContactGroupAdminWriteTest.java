package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.EmergencyContactGroup;
import io.sitprep.sitprepapi.service.EmergencyContactGroupService;
import io.sitprep.sitprepapi.service.HouseholdAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Key contacts on the household plan (plan data consistency closeout,
 * 2026-10-21): the View Plan page offers "Edit" to the household's admins, so
 * the server has to let a non-author admin edit the household's contacts —
 * and must still refuse a plain member and an outsider.
 */
class EmergencyContactGroupAdminWriteTest {

    private static final String HH = "hh-1";
    private static final String AUTHOR = "author@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";

    private EmergencyContactGroupService groups;
    private HouseholdAccessService access;
    private EmergencyContactGroupResource resource;
    private EmergencyContactGroup group;

    @BeforeEach
    void setUp() {
        groups = mock(EmergencyContactGroupService.class);
        access = mock(HouseholdAccessService.class);
        resource = new EmergencyContactGroupResource(groups, access);
        group = new EmergencyContactGroup();
        group.setId(7L);
        group.setOwnerEmail(AUTHOR);
        group.setHouseholdId(HH);
        group.setName("Out-of-area contacts");
        group.setContacts(new ArrayList<>());
        when(groups.getGroupById(7L)).thenReturn(Optional.of(group));
        when(groups.updateGroup(eq(7L), any())).thenReturn(group);
        when(access.canWriteHousehold(ADMIN, HH)).thenReturn(true);
        when(access.canWriteHousehold(MEMBER, HH)).thenReturn(false);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    @DisplayName("a household admin who did not write the group may edit and delete it")
    void nonAuthorAdminMayWrite() {
        signIn(ADMIN);
        assertThat(resource.updateGroup(7L, new EmergencyContactGroup()).getStatusCode().value()).isEqualTo(200);
        resource.deleteGroup(7L);
        verify(groups).deleteGroup(7L);
    }

    @Test
    @DisplayName("the author still may, admin or not")
    void authorMayWrite() {
        signIn(AUTHOR);
        assertThat(resource.updateGroup(7L, new EmergencyContactGroup()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a plain member who did not write it may not")
    void memberMayNot() {
        signIn(MEMBER);
        assertThatThrownBy(() -> resource.updateGroup(7L, new EmergencyContactGroup()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> resource.deleteGroup(7L))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verify(groups, never()).deleteGroup(any());
    }

    @Test
    @DisplayName("a group with no household is author-only")
    void noHouseholdIsAuthorOnly() {
        group.setHouseholdId(null);
        signIn(ADMIN);
        assertThatThrownBy(() -> resource.updateGroup(7L, new EmergencyContactGroup()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }
}
