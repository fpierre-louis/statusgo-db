package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.service.GroupMuteService;
import io.sitprep.sitprepapi.service.GroupPostReadReceiptService;
import io.sitprep.sitprepapi.service.GroupPostService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Read receipts, mute and quiet hours are members-only (B2 / BE-07).
 *
 * <p>{@code POST /groups/{id}/read} used to upsert a read row for any id, real
 * or not, and broadcast the caller's email to that group as a read receipt.
 * Mute and quiet hours wrote prefs for any id. A non-member now gets a 204
 * that does nothing; members are unchanged.</p>
 */
class GroupReadMuteScopeTest {

    private GroupResource resource;
    private GroupPostService posts;
    private GroupPostReadReceiptService receipts;
    private GroupMuteService mutes;

    @BeforeEach
    void setUp() {
        resource = new GroupResource();
        posts = mock(GroupPostService.class);
        receipts = mock(GroupPostReadReceiptService.class);
        mutes = mock(GroupMuteService.class);
        ReflectionTestUtils.setField(resource, "groupPostService", posts);
        ReflectionTestUtils.setField(resource, "groupPostReadReceiptService", receipts);
        ReflectionTestUtils.setField(resource, "groupMuteService", mutes);
        when(posts.canReadGroup("g1", "member@x.com")).thenReturn(true);
        when(posts.canReadGroup("g1", "stranger@x.com")).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void as(String email) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                email, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void aStrangersReadIsANoOpWithTheSame204() {
        as("stranger@x.com");
        assertEquals(HttpStatus.NO_CONTENT, resource.markGroupRead("g1").getStatusCode());
        verify(receipts, never()).markGroupRead(anyString(), anyString());
    }

    @Test
    void aMembersReadStillLands() {
        as("member@x.com");
        assertEquals(HttpStatus.NO_CONTENT, resource.markGroupRead("g1").getStatusCode());
        verify(receipts).markGroupRead("g1", "member@x.com");
    }

    @Test
    void aStrangersMuteAndQuietHoursWriteNothing() {
        as("stranger@x.com");
        assertEquals(HttpStatus.NO_CONTENT,
                resource.setGroupMute("g1", Map.of("mutedUntil", "indefinite")).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT,
                resource.setGroupQuietHours("g1", Map.of("start", 1320, "end", 420)).getStatusCode());
        verify(mutes, never()).setMute(anyString(), anyString(), any());
        verify(mutes, never()).setQuietHours(anyString(), anyString(), any(), any(), any());
        assertNull(resource.getGroupMute("g1").getBody().get("mutedUntil"));
        verify(mutes, never()).getMute(anyString(), anyString());
    }
}
