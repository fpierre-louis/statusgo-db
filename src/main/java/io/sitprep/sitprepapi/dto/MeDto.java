package io.sitprep.sitprepapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core /me payload — profile, household, groups, readiness, meta.
 *
 * Plans (mealPlan, evacuation, meetingPlaces, originLocations, contacts)
 * were dropped from this DTO and live behind {@code GET /api/me/{uid}/plans}
 * (see {@link MePlansDto}). The dashboard / nav / status surfaces don't
 * need them; only {@code me/plans/*} pages do.
 *
 * Readiness existence flags (which steps are done) still live here so the
 * dashboard ring renders from one round trip.
 */
public record MeDto(
        ProfileDto profile,
        HouseholdDto household,
        /**
         * Every household the user belongs to (base + additional), each
         * tagged with the viewer's role + an isBase flag. Powers the
         * HomeDashboard "Your households" section + multi-household nav.
         * The base household is ALSO surfaced in full as {@link #household()}
         * so existing single-household consumers keep working unchanged.
         */
        List<HouseholdSummary> households,
        GroupsDto groups,
        ReadinessDto readiness,
        /**
         * Opaque id of the user's most recent non-expired plan activation,
         * or null if they don't have one. Drives the Active Dashboard
         * auto-promote on /home (per docs/ECOSYSTEM_INTEGRATION.md step 5):
         * when this is non-null, HomeDashboard flips its hero to active
         * mode and links the user back to /deployedplan?activationId=...
         * The frontend doesn't need a separate poll — this rides on the
         * existing /api/me/{uid} payload.
         */
        String activeActivationId,
        MetaDto meta
) {

    public record ProfileDto(
            String userId,
            String firebaseUid,
            String email,
            String firstName,
            String lastName,
            String title,
            String phone,
            String address,
            String latitude,
            String longitude,
            String profileImageUrl,
            String subscription,
            SelfStatusDto selfStatus,
            /** Last time this user hit any authenticated endpoint. Null if never. */
            Instant lastActiveAt,
            /**
             * Last time this user completed the Readiness Assessment quiz
             * at /sitprep-quiz. Null if they haven't taken it. Used by the
             * frontend to decide whether to surface the quarterly nudge
             * banner on /home.
             */
            Instant lastAssessmentAt,
            /** First-run onboarding completed timestamp. Null until finished. */
            Instant onboardingCompletedAt,
            /** Terms/privacy accepted during onboarding. Null until accepted. */
            Instant onboardingTermsAcceptedAt,
            /** Location step completed during onboarding. Null until enabled. */
            Instant onboardingLocationEnabledAt,
            /** Notification step completed during onboarding. Null until enabled. */
            Instant onboardingNotificationsEnabledAt,
            // Public-profile fields — surfaced on MeDto so the editor at
            // /profile/edit can pre-populate without a second round trip.
            // Per docs/PROFILE_AND_FOLLOW.md build-order step 2.
            /** 200-char self-bio. Null when not set. */
            String bio,
            /** Free-form URL to the user's cover image. Null when not set. */
            String coverImageUrl,
            /** Who can see this profile. v1 vocab: public | circles | followers | private. Default "circles". */
            String profileVisibility,
            /**
             * Latest structured Readiness Assessment summary, parsed from
             * UserInfo.assessmentSummaryJson. Null when the user has not
             * completed the check on a client that sends the payload.
             */
            Map<String, Object> assessmentSummary
    ) {}

    public record SelfStatusDto(
            String value,
            String color,
            Instant updatedAt
    ) {}

    public record HouseholdDto(
            String groupId,
            String name,
            String address,
            String latitude,
            String longitude,
            String zipCode,
            int memberCount,
            int adminCount,
            DemographicDto demographic,
            Integer readinessPercent,
            /** "Active" when household alert mode is on, else null/idle. */
            String alert,
            /** Hazard type captured by the admin when activating, or null. */
            String activeHazardType,
            /** Up to 4 member avatars for the Home-base card stack. */
            List<MemberAvatar> memberPreview,
            /** Unread post count for the Home-base card (see GroupSummary). */
            int unreadCount
    ) {}

    /**
     * Lightweight per-household row for the "Your households" list. The
     * base household is rendered rich (full {@link HouseholdDto}); the
     * others render from this summary. {@code role} lets the FE show an
     * Admin/Member chip and gate edit affordances; {@code isBase} marks
     * the one that anchors the dashboard.
     */
    public record HouseholdSummary(
            String groupId,
            String name,
            int memberCount,
            int adminCount,
            /** "owner" | "admin" | "member" — the viewer's role here. */
            String role,
            /** True for the user's single base / main household. */
            boolean isBase,
            /** Per-household readiness; null in Phase 1 (computed later). */
            Integer readinessPercent,
            String alert,
            String activeHazardType
    ) {}

    public record DemographicDto(
            int adults,
            int kids,
            int infants,
            int dogs,
            int cats,
            int pets
    ) {}

    public record GroupsDto(
            List<GroupSummary> managed,
            List<GroupSummary> joined
    ) {}

    public record GroupSummary(
            String groupId,
            String name,
            String groupType,
            int memberCount,
            int pendingMemberCount,
            String role,
            String alert,
            /**
             * Hazard type the admin chose when activating the alert (e.g.
             * "hurricane", "wildfire", "earthquake"). Null when alert is
             * calm or the admin didn't specify. The frontend uses this to
             * pin the matching curated guide on /home + /ask without
             * keyword-matching the alert headline.
             */
            String activeHazardType,
            Instant updatedAt,
            /** Up to 4 member avatars for the circle-card stack (Direction 1). */
            List<MemberAvatar> memberPreview,
            /**
             * Posts in this group whose timestamp is newer than the viewer's
             * GroupReadState.lastReadAt. Zero when the viewer has no read
             * pointer yet — deliberate "start clean on rollout" choice so
             * existing users don't see a wall of stale unreads on day one.
             */
            int unreadCount
    ) {}

    /** A single member's avatar for the circle-card member stack. */
    public record MemberAvatar(String firstName, String profileImageUrl) {}

    public record ReadinessDto(
            int percentComplete,
            List<ReadinessStep> steps,
            /**
             * Per-pillar personal-task rollup. Phase 1 of BUSINESS_MODEL.md —
             * drives the My Readiness card on /home. Populated by
             * MeService from the user's Post rows where kind="task" and
             * groupId=null, grouped by their "pillar:X" tag.
             *
             * <p>The FE computes the displayed percent using
             * {@code completed / max(added, recommendedMin)} so a user
             * who's added one task and completed it doesn't read as
             * "100% Supplies." Denominator math lives FE-side where
             * the template catalog also lives — BE just counts.</p>
             *
             * <p>Stays null on the legacy MeDto shape so old FE builds
             * don't break.</p>
             */
            PillarRollup pillars
    ) {}

    public record ReadinessStep(
            String key,
            boolean done
    ) {}

    /**
     * Counts of added + completed personal preparedness tasks per
     * pillar. Each pillar's counts are independent — a user can have
     * 0 in supplies and 5 in family. Null pillar entries mean "no
     * tasks tagged with that pillar yet" — the FE treats null as
     * {added: 0, completed: 0}.
     */
    public record PillarRollup(
            PillarCounts supplies,
            PillarCounts plan,
            PillarCounts practice,
            PillarCounts family
    ) {}

    public record PillarCounts(
            int added,
            int completed
    ) {}

    public record MetaDto(
            Instant generatedAt,
            int version
    ) {}
}
