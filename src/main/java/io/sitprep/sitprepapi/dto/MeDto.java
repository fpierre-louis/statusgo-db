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
        /**
         * Opt-in public-profile preview. Populated only when the caller
         * passes {@code ?profile=<idOrEmail>} on {@code GET /api/me/{uid}}.
         * Lets the FE PublicProfilePage render on cold boot in one round
         * trip (no second {@code GET /api/users/profile/{idOrEmail}}).
         * Null when the param is absent OR when the lookup misses /
         * degrades — callers that don't pass {@code ?profile} never see
         * this field non-null and don't need to change. Per audit BE-12 /
         * P2-15.
         */
        PublicProfileDto profilePreview,
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
            String subscriptionPackage,
            String effectiveSubscriptionPackage,
            Instant subscriptionOverrideExpiresAt,
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
             * Discoverable in InviteSheet user search. Default {@code false} —
             * users must opt in via Profile settings before they surface in
             * name-prefix search results. Exact-email lookup still works
             * regardless. See {@code UserSearchResource} + docs/HOME_HOUSEHOLD_MERGE.md §5.
             */
            Boolean searchable,
            /**
             * Latest structured Readiness Assessment summary, parsed from
             * UserInfo.assessmentSummaryJson. Null when the user has not
             * completed the check on a client that sends the payload.
             */
            Map<String, Object> assessmentSummary,
            /**
             * Per-group map-visibility preference. Keys are group IDs; values are
             * one of {@code "always"}, {@code "check-in-only"}, or {@code "never"}.
             * A missing entry means the BE default applies (always for the user's
             * own household, check-in-only for member groups — locked 2026-06-02
             * per docs/MAP_SURFACES_REDESIGN_PLAN.md Phase 3). Driven by the FE
             * settings page at /account/map-visibility; updates flow through
             * {@code PATCH /userinfo/me/group-location-sharing}.
             */
            Map<String, String> groupLocationSharing,
            /**
             * Effective platform-console permissions for this user's email.
             * UI-gating only; backend admin endpoints re-check each permission.
             */
            List<String> platformPermissions,
            /**
             * Deterministic existence flags computed BEFORE DtoImages
             * normalization (audit BE-03 / P1-2). True iff the raw column
             * was non-blank AND resolved to a usable R2 object key. The FE
             * uses these to gate {@code <SafeImage>} rendering vs the
             * deterministic empty-hero fallback without waiting for an
             * image load/error to know what to draw.
             */
            boolean hasProfileImage,
            boolean hasCoverImage
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
            int unreadCount,
            /**
             * Timestamp of the most recent chat post in this household,
             * or {@code Group.updatedAt} when the household has no posts
             * yet. Drives the freshness meta ("Active 12m ago" /
             * "Quiet · 4d") on the Home-base card so it tracks real
             * conversation activity instead of the audit timestamp.
             * Never null — falls back to the group's createdAt at worst.
             */
            Instant lastActivityAt,
            /**
             * Viewer's mute deadline for this household — same
             * semantics as {@link GroupSummary#mutedUntil()}. The
             * household card on the Circles list uses this to render
             * the muted-bell pill + read state in the long-press
             * kebab. Null when not muted.
             */
            Instant mutedUntil,
            /** Quiet-hours window start. See {@link GroupSummary#quietStart()}. */
            Integer quietStart,
            /** Quiet-hours window end. See {@link GroupSummary#quietEnd()}. */
            Integer quietEnd,
            /** Quiet-hours IANA timezone. See {@link GroupSummary#quietTimezone()}. */
            String quietTimezone,
            /**
             * §4 of {@code docs/HOME_HOUSEHOLD_BEHAVIORAL_DESIGN.md} —
             * non-null means the household has opted into a recurring
             * ritual (currently always {@code "WEEKLY_SUN_19:00"} for
             * the weekly check-in). The FE reads this off the /me DTO
             * to render the row state without a separate fetch; the
             * full ritual entity is reachable via
             * {@code GET /api/households/{id}/rituals} when the FE
             * needs the id (for delete) or the timezone.
             */
            String weeklyCheckInScheduleSpec,
            /**
             * §4 R5 — non-null future instant means the ritual is
             * paused; the FE renders "Paused until ..." copy on the
             * home-card row in place of the scheduled-time. The
             * scheduler suppresses fires while this is in the future.
             * Null OR past means active.
             */
            Instant weeklyCheckInPausedUntil,
            /**
             * §4 R5 — IANA tz the ritual fires in. Null when no
             * ritual exists. Lets the picker UI render the current
             * "household time" and offer to change it, separately
             * from the viewer's device tz.
             */
            String weeklyCheckInTimezone,
            /**
             * Per-household weekly preparedness challenge completion log.
             * Keys are ISO week-year strings ("2026-W22"), values are
             * {@code true} when the household has marked that week's
             * drill done. Drives the FE {@code isThisWeekDone(householdId)}
             * and {@code getDrillsCompleted(householdId)} BE-backed
             * reads (see {@code src/me/challenges/challenges.js}).
             *
             * <p>Empty / null reads as "nothing done yet" — the FE
             * keeps a meCache fallback so offline writes can replay
             * on reconnect via {@code POST /api/households/{id}/challenges/{weekKey}/complete}.</p>
             */
            java.util.Map<String, Boolean> challengeProgress
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
            int teens,
            int kids,
            int infants,
            int dogs,
            int cats,
            int pets
    ) {}

    public record GroupsDto(
            List<GroupSummary> managed,
            List<GroupSummary> joined,
            List<GroupSummary> pending
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
            /**
             * True when this group has been stamped as an authorized agency.
             * Lets the client route agency owners/admins into the agency
             * workspace without inspecting backend-only rosters.
             */
            boolean agencyAuthorized,
            /**
             * Group record's audit timestamp — bumped on group-field
             * edits (name, alert toggled, etc.). Retained for legacy
             * sort consumers; prefer {@link #lastActivityAt} for any
             * "freshness" semantics, which tracks chat activity, not
             * group metadata.
             */
            Instant updatedAt,
            /** Up to 4 member avatars for the circle-card stack (Direction 1). */
            List<MemberAvatar> memberPreview,
            /**
             * Posts in this group whose timestamp is newer than the viewer's
             * GroupReadState.lastReadAt. Zero when the viewer has no read
             * pointer yet — deliberate "start clean on rollout" choice so
             * existing users don't see a wall of stale unreads on day one.
             */
            int unreadCount,
            /**
             * Timestamp of the most recent chat post in this circle, or
             * {@code Group.updatedAt} when the circle has no posts yet.
             * This is the "real" activity signal — drives the circle
             * card's "Active 12m ago" / "Quiet · 4d" meta line and the
             * Quiet filter chip. Falls back to createdAt at worst, so
             * never null on the wire.
             */
            Instant lastActivityAt,
            /**
             * Viewer's current notification-mute deadline for this
             * circle, or null when not muted. A far-future sentinel
             * (year 9999) means "muted indefinitely until I turn it
             * back on" — the FE renders that as "Muted" without a
             * deadline rather than "Muted until 9999-12-31".
             * Populated from {@code GroupMutePref}; absent rows read
             * as null. Past timestamps also surface as null (already
             * expired); enforcement uses the same check at dispatch.
             */
            Instant mutedUntil,
            /**
             * Daily quiet-hours window start, minutes from midnight
             * in {@link #quietTimezone} (0..1439). Null when the
             * window isn't configured. {@code quietStart > quietEnd}
             * means the window crosses midnight (e.g. 22:00→07:00
             * stores as 1320/420). Quiet hours and mute coexist;
             * dispatch is suppressed when either is active.
             */
            Integer quietStart,
            /** Daily quiet-hours window end. Same units as {@link #quietStart}. */
            Integer quietEnd,
            /**
             * IANA timezone for {@link #quietStart} / {@link #quietEnd}
             * (e.g. {@code "America/New_York"}). Null when the window
             * isn't configured. The FE captures this from
             * {@code Intl.DateTimeFormat().resolvedOptions().timeZone}
             * on save so the window follows the user when they travel
             * without a re-save.
             */
            String quietTimezone
    ) {}

    // MemberAvatar (the circle-card member-stack identity) is now the shared
    // top-level io.sitprep.sitprepapi.dto.MemberAvatar — same package, so the
    // unqualified references above still resolve. Unified 2026-06-11.

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
