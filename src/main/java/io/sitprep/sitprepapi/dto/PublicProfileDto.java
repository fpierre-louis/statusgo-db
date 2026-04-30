package io.sitprep.sitprepapi.dto;

import io.sitprep.sitprepapi.domain.UserInfo;

import java.time.Instant;
import java.util.List;

/**
 * Wire shape for the public ProfilePage at {@code /profile/:userId}. Per
 * {@code docs/PROFILE_AND_FOLLOW.md} build-order step 1 this is read-only:
 * no follow / block / message wiring, no privacy gating yet, no helps-given
 * ledger. The DTO surfaces everything the FE needs to render the
 * "neighborly card" layout (cover + avatar overlap + name + verified badge
 * + bio + groups + posts) without a second round trip.
 *
 * <p>Honest scope. Future build-order steps will fold in:
 * <ul>
 *   <li>{@code viewerRelationship} (self/follower/followee/mutual/blocked) — step 3</li>
 *   <li>{@code helpsGiven} / {@code helpsReceived} — once the ledger ships (open question in spec)</li>
 *   <li>privacy-gated stub when caller isn't permitted to see — step 5</li>
 * </ul>
 *
 * <p>{@code postCount} and {@code circleCount} are tabular numerals on
 * the FE — only earned metrics, never decorative.
 */
public record PublicProfileDto(
        String userId,
        String email,
        String firstName,
        String lastName,
        String profileImageUrl,
        String coverImageUrl,
        String bio,
        // Verified-publisher tier — same fields as ProfileSummaryDto so
        // the FE renders the blue checkmark with one shared component.
        boolean verifiedPublisher,
        String verifiedPublisherKind,
        // Visibility setting. v1 vocab: public | circles | followers | private.
        // FE doesn't yet honor — exposed so a future filter can layer in.
        String profileVisibility,
        // Earned metrics for the trust row.
        int circleCount,
        int postCount,
        // Last seen — drives a quiet "Active recently" pill when within 7d.
        Instant lastActiveAt,
        // Public groups they're a member of (Household-type excluded —
        // households are personal, not a public trust signal).
        List<PublicGroupSummary> groups,
        // Public posts they've authored, newest first. Capped at 10 to
        // keep the payload lean; FE paginates the rest if/when needed.
        List<PublicPostSummary> posts,
        // Viewer's relationship to this profile, resolved server-side
        // from the verified caller email. Drives the Follow button
        // state on the FE per docs/PROFILE_AND_FOLLOW.md step 3.
        // Vocabulary: self | mutual | follower | followee | none |
        // blocked (the last added at step 5).
        String viewerRelationship,
        // Privacy gate result — true when the viewer is permitted to
        // see the FULL profile (groups + posts + bio + cover). False
        // when the target's profileVisibility blocks the viewer; the
        // FE renders a stub with the name + Follow CTA + "This profile
        // is private" message in that case. Per
        // docs/PROFILE_AND_FOLLOW.md build-order step 5.
        //
        // Block trumps everything: when either party has blocked the
        // other, the resource layer returns 404 and this DTO never
        // ships, so visible=false here only ever means a privacy gate.
        boolean visible
) {

    /**
     * Group summary surfaced on a public profile. Strictly the bits a
     * stranger needs to size up "is this person a real neighbor?" — name,
     * type ({@code Neighborhood}, {@code School}, {@code Business}, etc),
     * and rough size. No member emails / admin lists / alert state.
     */
    public record PublicGroupSummary(
            String groupId,
            String groupName,
            String groupType,
            Integer memberCount
    ) {}

    /**
     * Post summary surfaced on a public profile. Mirrors the community
     * feed card's minimum shape — title, kind, status, first image,
     * truncated description. {@code id} keys the link to the full post
     * surface ({@code /posts/{id}} or whatever the FE picks).
     */
    public record PublicPostSummary(
            Long id,
            String title,
            String kind,
            String description,
            String imageUrl,
            String status,
            Instant createdAt
    ) {}

    /**
     * Build the basic identity slice from a {@link UserInfo}. Caller must
     * supply the derived collections + counts via the canonical
     * constructor; this is a small convenience for the resource layer.
     */
    public static PublicProfileDto of(
            UserInfo u,
            int circleCount,
            int postCount,
            List<PublicGroupSummary> groups,
            List<PublicPostSummary> posts,
            String viewerRelationship
    ) {
        return new PublicProfileDto(
                u.getId(),
                u.getUserEmail(),
                u.getUserFirstName(),
                u.getUserLastName(),
                u.getProfileImageURL(),
                u.getCoverImageUrl(),
                u.getBio(),
                u.isVerifiedPublisher(),
                u.getVerifiedPublisherKind(),
                u.getProfileVisibility(),
                circleCount,
                postCount,
                u.getLastActiveAt(),
                groups,
                posts,
                viewerRelationship,
                /* visible */ true
        );
    }

    /**
     * Stub variant for the privacy-gated case — viewer isn't permitted
     * to see the full profile (target's {@code profileVisibility} is
     * tighter than the viewer's relationship satisfies). Surfaces only
     * the bits the FE needs to render the stub: name + avatar + Follow
     * CTA + the "This profile is private" message.
     *
     * <p>Bio / cover / groups / posts / counts / lastActiveAt are
     * deliberately zeroed/nulled — the whole point of the gate is to
     * not leak that data to a non-permitted viewer.</p>
     */
    public static PublicProfileDto stub(UserInfo u, String viewerRelationship) {
        return new PublicProfileDto(
                u.getId(),
                u.getUserEmail(),
                u.getUserFirstName(),
                u.getUserLastName(),
                u.getProfileImageURL(),
                /* coverImageUrl */ null,
                /* bio */ null,
                u.isVerifiedPublisher(),
                u.getVerifiedPublisherKind(),
                u.getProfileVisibility(),
                /* circleCount */ 0,
                /* postCount */ 0,
                /* lastActiveAt */ null,
                /* groups */ List.of(),
                /* posts */ List.of(),
                viewerRelationship,
                /* visible */ false
        );
    }
}
