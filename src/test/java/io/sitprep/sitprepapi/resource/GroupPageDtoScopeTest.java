package io.sitprep.sitprepapi.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.constant.AgencyCapability;
import io.sitprep.sitprepapi.domain.DrillCompletion;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.dto.GroupPageDto;
import io.sitprep.sitprepapi.service.GroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-06 (B4, 2026-10-14): {@code GET /api/groups/{id}} is an allowlist per
 * viewer. A plain ORGANIZATION member gets the page, not the admin's view: no
 * join code, no pending list, no billing, no {@code lastUpdatedBy}. Admins add
 * pending and the code; nobody gets Stripe ids or staff notes here (the owner
 * reads {@code /billing/status}). Every scope carries the nested
 * {@code viewer {role, permissions}} and never a top-level {@code viewerRole}.
 */
class GroupPageDtoScopeTest {

    private static final String GROUP_ID = "org-1";
    private static final String OWNER = "owner@x.com";
    private static final String ADMIN = "admin@x.com";
    private static final String MEMBER = "member@x.com";
    private static final String PENDING = "pending@x.com";
    private static final String OTHER_PENDING = "asker@x.com";
    private static final String STRANGER = "stranger@x.com";

    private GroupService groupService;
    private GroupResource resource;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        groupService = mock(GroupService.class);
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(org());
        resource = new GroupResource();
        ReflectionTestUtils.setField(resource, "groupService", groupService);
        ReflectionTestUtils.setField(resource, "objectMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** An organization with every sensitive column set. */
    private static Group org() {
        Group g = new Group();
        g.setGroupId(GROUP_ID);
        g.setGroupName("Lehi Neighbors");
        g.setGroupType("Community");
        g.setPrivacy("Private");
        g.setDescription("Our street");
        g.setGroupCode("LEHI-42");
        g.setOwnerEmail(OWNER);
        g.setOwnerName("Olive Owner");
        g.setAdminEmails(new ArrayList<>(List.of(ADMIN)));
        g.setMemberEmails(new ArrayList<>(List.of(OWNER, ADMIN, MEMBER)));
        g.setPendingMemberEmails(new ArrayList<>(List.of(PENDING, OTHER_PENDING)));
        g.setMemberCount(1); // the drifting stored count
        g.setAddress("1 Main St, Lehi, UT 84043, USA");
        g.setLatitude(40.39);
        g.setLongitude(-111.85);
        g.setZipCode("84043");
        g.setAlert("Active");
        g.setAlertActivatedAt(Instant.parse("2026-10-14T12:00:00Z"));
        g.setLastUpdatedBy(ADMIN);
        g.setStripeCustomerId("cus_1");
        g.setStripeSubscriptionId("sub_1");
        g.setSubscriptionStatus("active");
        g.setSubscriptionOverrideBy("staff@sitprep.app");
        g.setSubscriptionOverrideReason("pilot");
        g.setOfficialContactEmail("clerk@lehi.gov");
        g.setCheckInRemindersFired(2);
        g.setSubGroupIDs(new ArrayList<>(List.of("sub-1")));
        g.setVersion(3L);
        return g;
    }

    private JsonNode read(String caller) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                caller, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        return resource.getGroupById(GROUP_ID);
    }

    private static final List<String> NEVER_FOR_ANYONE = List.of(
            "stripeCustomerId", "stripeSubscriptionId", "subscriptionStatus",
            "subscriptionOverrideBy", "subscriptionOverrideReason", "officialContactEmail",
            "checkInRemindersFired", "viewerRole");

    @Test
    void stranger_getsThePreviewAndNoEmailAtAll() {
        JsonNode n = read(STRANGER);
        assertEquals("Lehi Neighbors", n.get("groupName").asText());
        assertEquals(3, n.get("memberCount").asInt(), "the count is the list, not the drifting column");
        for (String f : List.of("memberEmails", "adminEmails", "ownerEmail", "pendingMemberEmails",
                "groupCode", "address", "latitude", "zipCode", "alert", "lastUpdatedBy",
                "subGroupIDs", "effectivePlanTier", "version")) {
            assertFalse(n.has(f), "stranger sees " + f);
        }
        for (String f : NEVER_FOR_ANYONE) assertFalse(n.has(f), "stranger sees " + f);
        assertFalse(n.toString().contains("@"), "an email survived: " + n);
        assertEquals("NONE", n.get("viewer").get("role").asText());
        assertEquals(0, n.get("viewer").get("permissions").size());
    }

    @Test
    void pending_getsThePreviewPlusOnlyTheirOwnRow() {
        JsonNode n = read(PENDING);
        assertEquals("PENDING", n.get("viewerStatus").asText());
        assertEquals("PENDING", n.get("viewer").get("role").asText());
        assertEquals(1, n.get("pendingMemberEmails").size());
        assertEquals(PENDING, n.get("pendingMemberEmails").get(0).asText());
        assertFalse(n.toString().contains(OTHER_PENDING), "a pending caller learned who else asked");
        for (String f : List.of("memberEmails", "groupCode", "address", "alert")) {
            assertFalse(n.has(f), "pending sees " + f);
        }
        for (String f : NEVER_FOR_ANYONE) assertFalse(n.has(f), "pending sees " + f);
    }

    @Test
    void plainOrgMember_getsThePage_notTheAdminView() {
        JsonNode n = read(MEMBER);
        // The page.
        assertEquals("Active", n.get("alert").asText());
        assertTrue(n.has("address"));
        assertTrue(n.has("subGroupIDs"));
        assertTrue(n.has("effectivePlanTier"));
        assertEquals("MEMBER", n.get("viewer").get("role").asText());
        assertEquals(0, n.get("viewer").get("permissions").size());
        // D6: none of the admin's view.
        for (String f : List.of("pendingMemberEmails", "groupCode", "lastUpdatedBy",
                "maxSeats", "smsAllowanceMonthly")) {
            assertFalse(n.has(f), "plain member sees " + f);
        }
        for (String f : NEVER_FOR_ANYONE) assertFalse(n.has(f), "plain member sees " + f);
        assertFalse(n.toString().contains(PENDING), "a requester's email reached a plain member");
        assertFalse(n.toString().contains("staff@sitprep.app"));
    }

    @Test
    void plainOrgMember_keepsTheRosterListsTheLivePageStillReads() {
        // TRANSITIONAL (GroupPageDto.MEMBER_ROSTER_TRANSITIONAL): MemberRail,
        // MembersSheet → Members.js and OrgGroupPage's roleOf read these for a
        // plain member until F3 moves them onto the member view's userId rows.
        // This test is the tripwire: when F3 lands, flip these to assertFalse.
        JsonNode n = read(MEMBER);
        assertEquals(3, n.get("memberEmails").size());
        assertEquals(ADMIN, n.get("adminEmails").get(0).asText());
        assertEquals(OWNER, n.get("ownerEmail").asText());
        // roleOf path 2 resolves the viewer from them; path 1 must stay unused.
        assertFalse(n.has("viewerRole"));
    }

    @Test
    void admin_addsPendingAndTheCode_butNoBilling() {
        JsonNode n = read(ADMIN);
        assertEquals(2, n.get("pendingMemberEmails").size());
        assertEquals("LEHI-42", n.get("groupCode").asText());
        assertEquals(ADMIN, n.get("lastUpdatedBy").asText());
        assertEquals("ADMIN", n.get("viewer").get("role").asText());
        Set<String> perms = texts(n.get("viewer").get("permissions"));
        assertTrue(perms.containsAll(Set.of("MANAGE_MEMBERS", "MANAGE_ALERTS", "EDIT_GROUP",
                "VIEW_ADMIN_DASHBOARD", "MANAGE_TASKS")));
        assertFalse(perms.contains("DELETE_GROUP"));
        assertFalse(perms.contains("TRANSFER_OWNERSHIP"));
        for (String f : NEVER_FOR_ANYONE) assertFalse(n.has(f), "admin sees " + f);
    }

    @Test
    void owner_isTheAdminViewWithEveryPermission_andBillingStaysOnBillingStatus() {
        JsonNode n = read(OWNER);
        assertEquals("OWNER", n.get("viewer").get("role").asText());
        assertEquals(8, n.get("viewer").get("permissions").size());
        assertTrue(n.has("pendingMemberEmails"));
        assertTrue(n.has("groupCode"));
        for (String f : NEVER_FOR_ANYONE) assertFalse(n.has(f), "owner sees " + f + " here");
    }

    @Test
    void householdMembers_areUnchanged_rosterCodeAndReadinessRecord() {
        Group hh = org();
        hh.setGroupType("Household");
        hh.setChallengeLastShownWeek("2026-W42");
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(hh);
        JsonNode n = read(MEMBER);
        assertTrue(n.has("memberEmails"));
        assertTrue(n.has("pendingMemberEmails"));
        assertTrue(n.has("groupCode"));
        assertEquals("2026-W42", n.get("challengeLastShownWeek").asText());
        for (String f : NEVER_FOR_ANYONE) assertFalse(n.has(f), "household member sees " + f);
    }

    @Test
    void anOrgMemberNeverSeesAHouseholdReadinessField() {
        Group g = org();
        g.setChallengeLastShownWeek("2026-W42");
        when(groupService.getGroupByPublicId(GROUP_ID)).thenReturn(g);
        assertFalse(read(ADMIN).has("challengeLastShownWeek"));
    }

    /**
     * The allowlist's guard: every key the entity serializes to must be
     * classified by one of GroupPageDto's sets. A new column on Group fails
     * here until someone decides who may read it, instead of shipping to
     * every caller the way the denylist let it.
     */
    @Test
    void everyKeyTheEntitySerializesIsClassified() {
        ObjectNode full = mapper.valueToTree(fullyPopulated());
        Set<String> classified = new HashSet<>();
        classified.addAll(GroupPageDto.PUBLIC);
        classified.addAll(GroupPageDto.MEMBER);
        classified.addAll(GroupPageDto.MEMBER_ROSTER_TRANSITIONAL);
        classified.addAll(GroupPageDto.ADMIN);
        classified.addAll(GroupPageDto.HOUSEHOLD_ONLY);
        classified.addAll(GroupPageDto.NEVER);
        Set<String> unclassified = new LinkedHashSet<>();
        full.fieldNames().forEachRemaining(k -> { if (!classified.contains(k)) unclassified.add(k); });
        assertTrue(unclassified.isEmpty(),
                "Group serializes keys GroupPageDto does not classify: " + unclassified);
        // And the sets don't overlap NEVER (a key can't be both shipped and banned).
        for (String k : GroupPageDto.NEVER) {
            for (GroupPageDto.Scope s : GroupPageDto.Scope.values()) {
                assertFalse(GroupPageDto.allowed(s).contains(k), k + " is NEVER but " + s + " allows it");
            }
        }
    }

    /** Every field set, so NON_NULL hides nothing from the guard. */
    private static Group fullyPopulated() {
        Group g = org();
        g.setActiveHazardType("wildfire");
        g.setAlertExpiresAt(Instant.now());
        g.setCreatedAt(Instant.now());
        g.setUpdatedAt(Instant.now());
        g.setParentGroupIDs(new ArrayList<>(List.of("p")));
        g.setPlanTier("GROUP");
        g.setMaxSeats(10);
        g.setSmsAllowanceMonthly(100);
        g.setLogoImageUrl("l");
        g.setCoverImageUrl("c");
        g.setBusinessCategory("b");
        g.setWebsiteUrl("w");
        g.setJurisdictionZips(new ArrayList<>(List.of("84043")));
        g.setJurisdictionType("city");
        g.setServiceAreaRadius(1.0);
        g.setJurisdictionLat(1.0);
        g.setJurisdictionLng(1.0);
        g.setJurisdictionRadiusMiles(1.0);
        g.setAgencyAuthorized(true);
        g.setAgencyCapabilities(new LinkedHashSet<>(Set.of(AgencyCapability.values()[0])));
        g.setClaimState("CLAIMED");
        g.setGhostDemandSignal(1);
        g.setOutreachOptOut(true);
        g.setLastOutreachDate(Instant.now());
        g.setOutreachCount(1);
        g.setSubscriptionOverrideTier("AGENCY");
        g.setSubscriptionOverrideExpiresAt(Instant.now());
        g.setSubscriptionOverrideAt(Instant.now());
        g.setPlanLastConfirmedAt(Instant.now());
        g.setChallengeLastShownWeek("w");
        g.setChallengeProgress(new HashMap<>(java.util.Map.of("k", true)));
        g.setDrillLog(new HashMap<>(java.util.Map.of("k", new DrillCompletion())));
        return g;
    }

    private static Set<String> texts(JsonNode arr) {
        Set<String> out = new HashSet<>();
        arr.forEach(x -> out.add(x.asText()));
        return out;
    }
}
