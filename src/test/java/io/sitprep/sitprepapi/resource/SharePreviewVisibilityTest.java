package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.service.GroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A link crawler gets a group's name only when someone meant it to.
 *
 * <p>{@code GET /share/group/{groupId}} is public by design — it exists so
 * Facebook, Discord and iMessage can unfurl an invite. Until 2026-08-24 it
 * unfurled <em>any</em> id: households are Group rows, so pointing a bot
 * User-Agent at a household id returned "&lt;family name&gt; on SitPrep · 4
 * members" to third parties that cache and index what they fetch.</p>
 *
 * <p>An invite token is the demonstration of intent — whoever holds it was given
 * it. A bare group id is not, so it earns a name only for a group that is public
 * by its own setting and is not somebody's household.</p>
 */
class SharePreviewVisibilityTest {

    private static final String BOT = "facebookexternalhit/1.1";

    private GroupService groupService;
    private ShareResource resource;

    @BeforeEach
    void setUp() {
        groupService = mock(GroupService.class);
        resource = new ShareResource(
                groupService,
                mock(io.sitprep.sitprepapi.service.GroupInviteService.class),
                mock(io.sitprep.sitprepapi.service.PostService.class));
        // @Value field — no Spring context here, so set it directly.
        ReflectionTestUtils.setField(resource, "frontendBaseUrl", "https://sitprep.app");
    }

    private Group group(String id, String name, String type, String privacy) {
        Group g = new Group();
        g.setGroupId(id);
        g.setGroupName(name);
        g.setGroupType(type);
        g.setPrivacy(privacy);
        g.setMemberEmails(List.of("a@x.com", "b@x.com", "c@x.com", "d@x.com"));
        g.setDescription("Where we meet if something happens.");
        return g;
    }

    private String botPreviewFor(Group g) {
        when(groupService.getGroupByPublicId(g.getGroupId())).thenReturn(g);
        ResponseEntity<?> res = resource.shareGroup(g.getGroupId(), BOT);
        return String.valueOf(res.getBody());
    }

    @Test
    void aHouseholdIsNotUnfurled() {
        // CreateHouseholdGroup never sets privacy, so a household reads as
        // neither "Private" nor "public" — a !isPrivate test would have let
        // every one of them through. Households are excluded by type.
        String html = botPreviewFor(group("hh-1", "The Reyes household", "Household", null));
        assertFalse(html.contains("The Reyes household"), "household name reached a crawler");
        assertFalse(html.contains("4 members"), "household size reached a crawler");
        assertTrue(html.contains("Join a circle on SitPrep"));
    }

    @Test
    void aPrivateCircleIsNotUnfurled() {
        String html = botPreviewFor(group("grp-1", "Maple St Neighbors", "Neighborhood", "Private"));
        assertFalse(html.contains("Maple St Neighbors"));
        assertFalse(html.contains("Where we meet"));
    }

    @Test
    void aPublicCircleStillUnfurlsProperly() {
        // The marketing case this endpoint exists for. Closing it would be a
        // regression, not a fix.
        String html = botPreviewFor(group("grp-2", "Ogden Ready", "Neighborhood", "public"));
        assertTrue(html.contains("Ogden Ready"));
        assertTrue(html.contains("4 members"));
    }

    @Test
    void humansAreRedirectedRegardlessOfVisibility() {
        // No preview is rendered for a person; they land in the SPA, where the
        // sanitized GroupPreviewDto and the sign-in flow take over. So this
        // change cannot break any real invite flow.
        Group hh = group("hh-1", "The Reyes household", "Household", null);
        when(groupService.getGroupByPublicId("hh-1")).thenReturn(hh);
        ResponseEntity<?> res = resource.shareGroup("hh-1", "Mozilla/5.0 (iPhone)");
        assertEquals(302, res.getStatusCode().value());
        assertTrue(String.valueOf(res.getHeaders().getLocation()).contains("/joingroup?groupId=hh-1"));
    }
}
