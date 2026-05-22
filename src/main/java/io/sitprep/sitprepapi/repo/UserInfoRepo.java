package io.sitprep.sitprepapi.repo;

import io.sitprep.sitprepapi.domain.UserInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserInfoRepo extends JpaRepository<UserInfo, String> {

    Optional<UserInfo> findByUserEmail(String email);

    Optional<UserInfo> findByUserEmailIgnoreCase(String email);

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
}