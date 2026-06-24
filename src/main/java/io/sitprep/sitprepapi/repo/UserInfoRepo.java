package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    Optional<UserInfo> findByUserEmail(String email);

    Optional<UserInfo> findByUserEmailIgnoreCase(String email);

    /**
     * Typeahead user search for the InviteSheet. Excludes the caller and
     * matches against firstName/lastName/email prefix. Only
     * {@code searchable=true} rows surface — discovery is opt-in
     * (see {@code UserInfo.searchable}). Pagination drives the 10-result
     * cap at the service layer.
     *
     * <p>The {@code searchable IS NOT FALSE} predicate (vs. {@code IS TRUE})
     * keeps users null-valued during the column rollout visible — once
     * existing rows backfill to FALSE, the semantics converge to
     * "explicitly opt-in only".</p>
     */
    @Query("""
           SELECT u FROM UserInfo u
            WHERE u.searchable IS NOT FALSE
              AND LOWER(u.userEmail) <> LOWER(:viewerEmail)
              AND (
                LOWER(u.userFirstName) LIKE LOWER(CONCAT(:q, '%'))
                OR LOWER(u.userLastName) LIKE LOWER(CONCAT(:q, '%'))
                OR LOWER(u.userEmail) LIKE LOWER(CONCAT(:q, '%'))
              )
            ORDER BY LOWER(u.userLastName), LOWER(u.userFirstName)
           """)
    List<UserInfo> searchUsers(
            @Param("q") String q,
            @Param("viewerEmail") String viewerEmail,
            Pageable pageable);

    // ✅ NEW: stable identity lookup
    Optional<UserInfo> findByFirebaseUid(String firebaseUid);

    default Optional<UserInfo> findByUserEmailNormalized(String email) {
        return findByUserEmail(email == null ? null : email.toLowerCase());
    }

    List<UserInfo> findByUserEmailIn(List<String> emails);

    /** Users with no base household yet — assigned by HouseholdBackfillRunner. */
    List<UserInfo> findByBaseHouseholdIdIsNull();

    @Query("SELECT u.userEmail FROM UserInfo u JOIN u.joinedGroupIDs g WHERE g = :groupId")
    List<String> findEmailsByGroupId(@Param("groupId") String groupId);

    /**
     * All currently-verified publishers (city / county / state / newsroom
     * / utility / Red Cross). Caller refines with Haversine on
     * (latitude, longitude) for the radius filter — same pattern as
     * PostService.discoverCommunity. Cap-50 trim happens at the service
     * layer.
     */
    List<UserInfo> findByVerifiedPublisherTrue();

    // Geo-alert recipients (Phase 5 Slice C): users whose cached jurisdiction
    // zip is in the agency's claimed set, seen recently. The send path drops
    // tokenless users via NotificationService.
    List<UserInfo> findByLastKnownZipInAndLastKnownLocationAtAfter(
            java.util.Collection<String> zips, Instant since);

    Optional<UserInfo> findFirstByVerifiedPublisherGroupIdIgnoreCase(String groupId);

    /**
     * Every user who can receive a push (a non-empty FCM token) AND has
     * a known current location. {@code AlertDispatchService}'s severe-
     * alert fan-out loads this once per dispatch tick and refines it
     * with a Haversine radius filter in Java — the same load-candidates-
     * then-filter-in-service pattern as {@link #findByVerifiedPublisherTrue()}.
     *
     * <p>Bounded by design: only located, push-enabled users qualify.
     * A bounding-box pre-filter on (lastKnownLat, lastKnownLng) is the
     * natural next optimization once the user base outgrows a single
     * in-memory scan.</p>
     */
    @Query("SELECT u FROM UserInfo u " +
           "WHERE u.fcmtoken IS NOT NULL AND u.fcmtoken <> '' " +
           "AND u.lastKnownLat IS NOT NULL AND u.lastKnownLng IS NOT NULL")
    List<UserInfo> findPushablesWithLocation();

    @Query("SELECT u FROM UserInfo u " +
           "WHERE u.lastKnownLat BETWEEN :latMin AND :latMax " +
           "AND u.lastKnownLng BETWEEN :lngMin AND :lngMax " +
           "AND u.lastKnownLocationAt > :since")
    List<UserInfo> findInBoundingBox(
            @Param("latMin") double latMin,
            @Param("latMax") double latMax,
            @Param("lngMin") double lngMin,
            @Param("lngMax") double lngMax,
            @Param("since") Instant since);

    /**
     * Guest accounts that have entered the expiry warning window and
     * have not yet received the one-time reminder push.
     */
    @Query("SELECT u FROM UserInfo u " +
           "WHERE u.guestAccount = true " +
           "AND u.guestCreatedAt IS NOT NULL " +
           "AND u.guestExpiryReminderSentAt IS NULL " +
           "AND u.fcmtoken IS NOT NULL AND u.fcmtoken <> '' " +
           "AND u.guestCreatedAt <= :reminderCutoff " +
           "AND u.guestCreatedAt > :expiredCutoff")
    List<UserInfo> findGuestAccountsNeedingExpiryReminder(
            @Param("reminderCutoff") Instant reminderCutoff,
            @Param("expiredCutoff") Instant expiredCutoff,
            Pageable pageable);

    /**
     * Guest accounts whose 30-day TTL has fully elapsed. Drives
     * GuestAccountPurgeService — Firebase auto-deletes only the anonymous
     * auth user at 30 days, so without this the backend UserInfo + plan
     * data orphans forever. Converted users are excluded automatically
     * (conversion clears {@code guestAccount}).
     */
    @Query("SELECT u FROM UserInfo u " +
           "WHERE u.guestAccount = true " +
           "AND u.guestCreatedAt IS NOT NULL " +
           "AND u.guestCreatedAt <= :expiredCutoff")
    List<UserInfo> findExpiredGuestAccounts(
            @Param("expiredCutoff") Instant expiredCutoff,
            Pageable pageable);
}
