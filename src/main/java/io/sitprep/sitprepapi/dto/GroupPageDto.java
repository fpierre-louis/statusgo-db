package io.sitprep.sitprepapi.dto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sitprep.sitprepapi.constant.GroupPermission;
import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.service.HouseholdEventService;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What {@code GET /api/groups/{groupId}} may say, per viewer — an ALLOWLIST
 * (BE-06, B4 2026-10-14). It replaces B2's denylist
 * ({@code RELATIONSHIP_ONLY_FIELDS}), which stripped named fields from the
 * whole serialized entity: every new column on {@link Group} shipped to every
 * caller until somebody remembered to name it. Here a key ships only when a
 * scope lists it, and {@code GroupPageDtoScopeTest} fails the build when the
 * entity grows a key no set below classifies.
 *
 * <p>The wire shape for a key that does ship is unchanged (it is still the
 * entity's own serialization), so the dozen frontend readers of this route
 * keep working.</p>
 *
 * <h2>Scopes</h2>
 * <ul>
 *   <li><b>STRANGER</b> — the preview: identity, purpose, public agency
 *       facts, a count. Nothing about people or place. Organizations only.</li>
 *   <li><b>PENDING</b> — the stranger's view, plus the requester's own
 *       pending row and {@code viewerStatus} (D3; added by the resource).</li>
 *   <li><b>HOUSEHOLD_STRANGER / HOUSEHOLD_PENDING</b> — anyone outside a
 *       household. A household has no preview ({@code /preview} answers 404,
 *       JN-04 / D4), so this route says no more than that: the id, the type
 *       and the {@code viewer} block — no family name, owner name, note, size
 *       or dates. A pending caller also gets their own request back, as on
 *       PENDING (B4 review, 2026-10-14).</li>
 *   <li><b>MEMBER</b> (an organization) — the page: address, check-in state,
 *       links, plan. No pending list, no join code, no billing, no
 *       {@code lastUpdatedBy} (D6).</li>
 *   <li><b>HOUSEHOLD</b> — any member of a household: unchanged from before
 *       (roster, pending, code and the household's readiness record).</li>
 *   <li><b>ADMIN</b> — owner and admins of an organization: also pending,
 *       the join code and the seat cap. Billing is never on this route: the
 *       owner reads it from {@code /api/billing/status}.</li>
 * </ul>
 *
 * <h2>The roster email lists</h2>
 *
 * D6: a plain organization member gets no co-member email. The lists
 * ({@link #ROSTER_EMAILS}) ship to a household's members and to an
 * organization's owner and admins only; a plain member reads the roster from
 * the member view's {@code userId} / {@code role} rows (F3, B4b 2026-10-14).
 */
public final class GroupPageDto {

    private GroupPageDto() {}

    public enum Scope {
        STRANGER, PENDING, HOUSEHOLD_STRANGER, HOUSEHOLD_PENDING, MEMBER, HOUSEHOLD, ADMIN;

        /** A caller with a join request open: gets their own row and {@code viewerStatus}. */
        public boolean isPending() {
            return this == PENDING || this == HOUSEHOLD_PENDING;
        }
    }

    /** What anyone signed in may read: the same level as {@code /preview}. */
    public static final Set<String> PUBLIC = Set.of(
            "groupId", "groupName", "groupType", "description", "privacy",
            "ownerName", "memberCount", "createdAt", "updatedAt",
            "logoImageUrl", "coverImageUrl", "businessCategory", "websiteUrl",
            "planTier",
            // An agency's public footprint: its claimed service area and what
            // it is authorized to do. Already public before B4.
            "agencyAuthorized", "agencyCapabilities", "claimState",
            "jurisdictionZips", "jurisdictionType", "serviceAreaRadius",
            "jurisdictionLat", "jurisdictionLng", "jurisdictionRadiusMiles",
            // Added by the resource.
            "jurisdictionReady", "viewerAgencyPermissions", "viewer"
    );

    /**
     * All that someone outside a household reads: the id they already hold,
     * the type (so a client can route it), and their own {@code viewer} block.
     * A household has no public face — see the class doc.
     */
    public static final Set<String> HOUSEHOLD_OUTSIDER = Set.of(
            "groupId", "groupType", "viewer"
    );

    /** What every member adds (organizations and households). */
    public static final Set<String> MEMBER = Set.of(
            "address", "latitude", "longitude", "zipCode",
            "alert", "alertActivatedAt", "alertExpiresAt", "activeHazardType",
            "subGroupIDs", "parentGroupIDs", "version",
            // Added by the resource (B1 / PR-01).
            "effectivePlanTier", "planUnlocks"
    );

    /**
     * The roster as email lists: a household's members and an organization's
     * owner and admins. Never a plain organization member (D6).
     */
    public static final Set<String> ROSTER_EMAILS = Set.of(
            "memberEmails", "adminEmails", "ownerEmail"
    );

    /** What the owner and admins of an organization add. */
    public static final Set<String> ADMIN = Set.of(
            "pendingMemberEmails", "groupCode", "lastUpdatedBy",
            "maxSeats", "smsAllowanceMonthly"
    );

    /** A household's readiness record: its own members only (T-27). */
    public static final Set<String> HOUSEHOLD_ONLY = Set.of(
            "challengeProgress", "drillLog", "planLastConfirmedAt", "challengeLastShownWeek"
    );

    /**
     * Never on this route, for anyone: Stripe identity and status (the owner
     * reads {@code /api/billing/status}), platform staff's override (their
     * email and a free-text note), outreach state for unclaimed groups, the
     * agency contact of record, and the reminder counter.
     */
    public static final Set<String> NEVER = Set.of(
            "stripeCustomerId", "stripeSubscriptionId", "subscriptionStatus",
            "subscriptionOverrideTier", "subscriptionOverrideExpiresAt",
            "subscriptionOverrideReason", "subscriptionOverrideBy", "subscriptionOverrideAt",
            "ghostDemandSignal", "outreachOptOut", "lastOutreachDate", "outreachCount",
            "officialContactEmail", "checkInRemindersFired"
    );

    /** The keys a scope may carry. */
    public static Set<String> allowed(Scope scope) {
        if (scope == Scope.HOUSEHOLD_STRANGER || scope == Scope.HOUSEHOLD_PENDING) {
            return HOUSEHOLD_OUTSIDER;
        }
        Set<String> out = new HashSet<>(PUBLIC);
        switch (scope) {
            case STRANGER, PENDING -> { /* the preview only */ }
            case HOUSEHOLD_STRANGER, HOUSEHOLD_PENDING -> { /* returned above */ }
            case MEMBER -> {
                out.addAll(MEMBER);
            }
            case HOUSEHOLD, ADMIN -> {
                out.addAll(MEMBER);
                out.addAll(ROSTER_EMAILS);
                out.addAll(ADMIN);
                if (scope == Scope.HOUSEHOLD) out.addAll(HOUSEHOLD_ONLY);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** Which scope this caller reads {@code g} in. */
    public static Scope scopeOf(Group g, String caller) {
        GroupRole role = GroupRole.fromGroup(g, caller);
        boolean household = g != null && g.getGroupType() != null
                && HouseholdEventService.HOUSEHOLD_GROUP_TYPE.equalsIgnoreCase(g.getGroupType().trim());
        if (role == GroupRole.NONE) {
            boolean pending = containsIgnoreCase(g == null ? null : g.getPendingMemberEmails(), caller);
            if (household) return pending ? Scope.HOUSEHOLD_PENDING : Scope.HOUSEHOLD_STRANGER;
            return pending ? Scope.PENDING : Scope.STRANGER;
        }
        if (household) return Scope.HOUSEHOLD;
        return role.isAtLeastAdmin() ? Scope.ADMIN : Scope.MEMBER;
    }

    /** Drops every key the scope does not allow, in place. */
    public static ObjectNode project(ObjectNode node, Scope scope) {
        Set<String> keep = allowed(scope);
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        for (String n : names) if (!keep.contains(n)) node.remove(n);
        return node;
    }

    /**
     * The viewer block (BE-11): {@code {role, permissions}}, nested under
     * {@code viewer}. It is NOT a top-level {@code viewerRole}: the frontend's
     * {@code groupRoles.roleOf} returns {@code group.viewerRole} for ANY email
     * it is asked about, so a top-level field would label every row on the
     * per-row role pills (Members.js, ManageMembersModal, GroupRosterManager)
     * with the viewer's own role.
     *
     * <p>{@code role} is {@code OWNER | ADMIN | MEMBER | PENDING | NONE}, the
     * values {@code roleOf} returns; {@code permissions} are
     * {@link GroupPermission} names, the matrix the endpoints enforce.</p>
     */
    public static ObjectNode viewer(ObjectNode into, Group g, String caller) {
        GroupRole role = GroupRole.fromGroup(g, caller);
        String wire = role == GroupRole.NONE
                && containsIgnoreCase(g == null ? null : g.getPendingMemberEmails(), caller)
                ? "PENDING" : role.name();
        ObjectNode v = into.putObject("viewer");
        v.put("role", wire);
        ArrayNode perms = v.putArray("permissions");
        for (GroupPermission p : GroupPermission.values()) {
            if (role.has(p)) perms.add(p.name());
        }
        return v;
    }

    private static boolean containsIgnoreCase(List<String> list, String needle) {
        if (list == null || needle == null || needle.isBlank()) return false;
        String n = needle.trim().toLowerCase(Locale.ROOT);
        for (String s : list) if (s != null && s.trim().toLowerCase(Locale.ROOT).equals(n)) return true;
        return false;
    }
}
