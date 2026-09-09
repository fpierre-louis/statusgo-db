package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.GroupInvite;
import io.sitprep.sitprepapi.dto.ResourceListingDto;
import io.sitprep.sitprepapi.service.GroupService;
import io.sitprep.sitprepapi.service.ResourceListingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    private io.sitprep.sitprepapi.service.GroupInviteService inviteService;
    private ResourceListingService resourceListingService;
    private ShareResource resource;

    @BeforeEach
    void setUp() {
        groupService = mock(GroupService.class);
        inviteService = mock(io.sitprep.sitprepapi.service.GroupInviteService.class);
        resourceListingService = mock(ResourceListingService.class);
        resource = new ShareResource(
                groupService,
                inviteService,
                mock(io.sitprep.sitprepapi.service.PostService.class),
                resourceListingService);
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

    @Test
    void tokenInviteRedirectPreservesTheToken() {
        Group group = group("grp-3", "Maple St Neighbors", "Neighborhood", "Private");
        GroupInvite invite = new GroupInvite();
        invite.setId("invite-123");
        invite.setGroupId(group.getGroupId());

        when(inviteService.validate("invite-123"))
                .thenReturn(new io.sitprep.sitprepapi.service.GroupInviteService.ValidationResult(
                        io.sitprep.sitprepapi.service.GroupInviteService.InviteState.OK,
                        invite));
        when(groupService.getGroupByPublicId(group.getGroupId())).thenReturn(group);

        ResponseEntity<?> res = resource.shareByInvite("invite-123", "Mozilla/5.0 (iPhone)");

        String location = String.valueOf(res.getHeaders().getLocation());
        assertEquals(302, res.getStatusCode().value());
        assertTrue(location.contains("/joingroup?groupId=grp-3"));
        assertTrue(location.contains("&invite=invite-123"));
    }

    @Test
    void householdInviteTokenCanUnfurlSafeHouseholdPreview() {
        Group household = group("hh-2", "The Reyes household", "Household", null);
        GroupInvite invite = new GroupInvite();
        invite.setId("hh-invite-123");
        invite.setGroupId(household.getGroupId());

        when(inviteService.previewHousehold("hh-invite-123"))
                .thenReturn(new io.sitprep.sitprepapi.service.GroupInviteService.HouseholdInvitePreview(
                        io.sitprep.sitprepapi.service.GroupInviteService.InviteState.OK,
                        invite,
                        household));

        ResponseEntity<?> res = resource.shareHousehold("hh-invite-123", BOT);
        String html = String.valueOf(res.getBody());

        assertEquals(200, res.getStatusCode().value());
        assertTrue(html.contains("The Reyes household"));
        assertTrue(html.contains("private household plan"));
        assertFalse(html.contains("Where we meet if something happens."));
        assertFalse(html.contains("a@x.com"));
    }

    @Test
    void householdInviteHumanRedirectsToJoinHouseholdRoute() {
        Group household = group("hh-2", "The Reyes household", "Household", null);
        GroupInvite invite = new GroupInvite();
        invite.setId("hh-invite-123");
        invite.setGroupId(household.getGroupId());

        when(inviteService.previewHousehold("hh-invite-123"))
                .thenReturn(new io.sitprep.sitprepapi.service.GroupInviteService.HouseholdInvitePreview(
                        io.sitprep.sitprepapi.service.GroupInviteService.InviteState.OK,
                        invite,
                        household));

        ResponseEntity<?> res = resource.shareHousehold("hh-invite-123", "Mozilla/5.0 (iPhone)");
        String location = String.valueOf(res.getHeaders().getLocation());

        assertEquals(302, res.getStatusCode().value());
        assertTrue(location.contains("/join/household/hh-invite-123"));
        assertFalse(location.contains("hh-2"));
    }

    @Test
    void guideShareCanUnfurlStaticPreview() {
        ResponseEntity<?> res = resource.shareGuide("wildfire-prep", BOT);
        String html = String.valueOf(res.getBody());

        assertEquals(200, res.getStatusCode().value());
        assertTrue(html.contains("Wildfire smoke + evacuation on SitPrep"));
        assertTrue(html.contains("Defensible space"));
        assertTrue(html.contains("https://sitprep.app/share/guide/wildfire-prep"));
        assertTrue(html.contains("https://sitprep.app/wildfire-prep"));
    }

    @Test
    void guideShareHumanRedirectsToCanonicalGuideRoute() {
        ResponseEntity<?> res = resource.shareGuide("earthquake-prep", "Mozilla/5.0 (iPhone)");
        String location = String.valueOf(res.getHeaders().getLocation());

        assertEquals(302, res.getStatusCode().value());
        assertEquals("https://sitprep.app/earthquake-prep", location);
    }

    @Test
    void guideShareLegacyPlaybookSlugRedirectsToCanonicalPlaybookRoute() {
        ResponseEntity<?> res = resource.shareGuide("power-outage-prep", "Mozilla/5.0 (iPhone)");
        String location = String.valueOf(res.getHeaders().getLocation());

        assertEquals(302, res.getStatusCode().value());
        assertEquals("https://sitprep.app/playbooks/power-outage", location);
    }

    @Test
    void unknownGuideSlugGetsGenericPreviewForBots() {
        ResponseEntity<?> res = resource.shareGuide("nope", BOT);
        String html = String.valueOf(res.getBody());

        assertEquals(404, res.getStatusCode().value());
        assertTrue(html.contains("SitPrep preparedness guides"));
        assertTrue(html.contains("https://sitprep.app/hazards"));
        assertFalse(html.contains("nope on SitPrep"));
    }

    @Test
    void approvedResourceShareCanUnfurlStaticPreview() {
        when(resourceListingService.findPublicPreview(42L))
                .thenReturn(Optional.of(new ResourceListingDto(
                        42L,
                        "Warming center",
                        "Open overnight during the cold snap.",
                        "warming-center",
                        null,
                        null,
                        "123 Main St",
                        "https://example.org/warming",
                        "OFFICIAL",
                        null,
                        Instant.parse("2026-01-01T00:00:00Z"))));

        ResponseEntity<?> res = resource.shareResource(42L, BOT);
        String html = String.valueOf(res.getBody());

        assertEquals(200, res.getStatusCode().value());
        assertTrue(html.contains("Warming center on SitPrep"));
        assertTrue(html.contains("Open overnight during the cold snap."));
        assertTrue(html.contains("123 Main St"));
        assertTrue(html.contains("https://sitprep.app/share/resource/42"));
        assertTrue(html.contains("https://sitprep.app/community/resources?resource=42"));
    }

    @Test
    void resourceShareHumanRedirectsToResourceBoardFocus() {
        ResponseEntity<?> res = resource.shareResource(42L, "Mozilla/5.0 (iPhone)");
        String location = String.valueOf(res.getHeaders().getLocation());

        assertEquals(302, res.getStatusCode().value());
        assertEquals("https://sitprep.app/community/resources?resource=42", location);
        verifyNoInteractions(resourceListingService);
    }

    @Test
    void missingResourceShareGetsGenericPreviewForBots() {
        when(resourceListingService.findPublicPreview(999L)).thenReturn(Optional.empty());

        ResponseEntity<?> res = resource.shareResource(999L, BOT);
        String html = String.valueOf(res.getBody());

        assertEquals(404, res.getStatusCode().value());
        assertTrue(html.contains("Community resource on SitPrep"));
        assertFalse(html.contains("999 on SitPrep"));
    }
}
