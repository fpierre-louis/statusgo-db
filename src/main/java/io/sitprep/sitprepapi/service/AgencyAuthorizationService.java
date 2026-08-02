package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.constant.GroupRole;
import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.domain.UserInfo;
import io.sitprep.sitprepapi.repo.UserInfoRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgencyAuthorizationService {

    public static final double MAX_RADIUS_MILES = 50.0;

    private final UserInfoRepo userInfoRepo;
    private final UserGeoService userGeoService;
    private final AgencyStaffService agencyStaffService;

    public AgencyAuthorizationService(UserInfoRepo userInfoRepo,
                                      UserGeoService userGeoService,
                                      AgencyStaffService agencyStaffService) {
        this.userInfoRepo = userInfoRepo;
        this.userGeoService = userGeoService;
        this.agencyStaffService = agencyStaffService;
    }

    public void requireAgencyPostingAllowed(Group agency, String callerEmail) {
        if (agency == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found");
        }
        if (!GroupRole.fromGroup(agency, callerEmail).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group admin or owner role required");
        }
        if (!agency.isAgencyAuthorized()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agency is not authorized to post");
        }
        if (!hasGeo(agency) && legacyZips(agency).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agency has no authorized jurisdiction");
        }
    }

    /**
     * Civic epic Slice 1 — READ gate for an agency's own surfaces (the civic
     * pending queue). Requires the caller to be at least an admin/owner of an
     * {@code agencyAuthorized} group (decision 6: "agency = any Group with
     * agencyAuthorized=true"). Deliberately does NOT require jurisdiction geo —
     * unlike {@link #requireAgencyPostingAllowed}, reading a queue needs no
     * posting geometry, so a freshly-authorized agency that hasn't set its
     * radius can still read its inbox.
     */
    public void requireAgencyAdmin(Group agency, String callerEmail) {
        if (agency == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found");
        }
        if (!GroupRole.fromGroup(agency, callerEmail).isAtLeastAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Group admin or owner role required");
        }
        if (!agency.isAgencyAuthorized()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an authorized agency");
        }
    }

    /**
     * READ gate for an agency's operational surfaces that STAFF may also see —
     * strictly wider than {@link #requireAgencyAdmin} by exactly one population:
     * a person with an {@code agency_staff} row for this group (the non-admin
     * employee who works the agency's queue; see
     * docs/lanes/AGENCY_STAFF_PHASE0_DESIGN.md). Staff is INDEPENDENT of group
     * role, so a staff member typically resolves to {@code MEMBER} or
     * {@code NONE} via {@link GroupRole#fromGroup} and would fail the admin gate.
     *
     * <p>Same shape as {@link #requireAgencyAdmin}: 404 when the group is
     * missing, 403 when the caller is neither admin/owner nor staff, and 403
     * when the group is not {@code agencyAuthorized}. Like its sibling it does
     * NOT require jurisdiction geo — reading a queue needs no posting geometry.
     *
     * <p><b>Deliberately read-only.</b> Use this for reads. The civic WRITE
     * paths (claim / release / merge / unmerge) stay on
     * {@link #requireAgencyAdmin} — widening those to staff is a separate owner
     * decision, not implied by read access.
     */
    public void requireAgencyStaffOrAdmin(Group agency, String callerEmail) {
        if (agency == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agency group not found");
        }
        boolean admin = GroupRole.fromGroup(agency, callerEmail).isAtLeastAdmin();
        // Short-circuit: only pay for the staff lookup when the role check fails.
        boolean staff = !admin
                && callerEmail != null && !callerEmail.isBlank()
                && agency.getGroupId() != null
                && agencyStaffService.isStaff(callerEmail, agency.getGroupId());
        if (!admin && !staff) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Agency staff, admin, or owner role required");
        }
        if (!agency.isAgencyAuthorized()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an authorized agency");
        }
    }

    /**
     * Users in an agency's jurisdiction to receive its alert — the LIFE-SAFETY
     * send path. Slice 2 unifies the coverage predicate to radius ∪ claimed-zip
     * (the same union the AgencyJurisdictionService resolver uses, applied in the
     * agency→users direction). Previously {@code hasGeo} short-circuited to
     * radius-ONLY, silently ignoring an agency that also claimed zips; the union
     * can only BROADEN the recipient set, never narrow it. Deduped by email.
     */
    public List<UserInfo> recipients(Group agency, Instant since) {
        Map<String, UserInfo> byEmail = new LinkedHashMap<>();
        if (hasGeo(agency)) {
            for (UserInfo u : userGeoService.findWithinRadiusMiles(
                    agency.getJurisdictionLat(),
                    agency.getJurisdictionLng(),
                    agency.getJurisdictionRadiusMiles(),
                    since)) {
                if (u != null && u.getUserEmail() != null) {
                    byEmail.putIfAbsent(u.getUserEmail().toLowerCase(), u);
                }
            }
        }
        Set<String> zips = legacyZips(agency);
        if (!zips.isEmpty()) {
            for (UserInfo u : userInfoRepo.findByLastKnownZipInAndLastKnownLocationAtAfter(zips, since)) {
                if (u != null && u.getUserEmail() != null) {
                    byEmail.putIfAbsent(u.getUserEmail().toLowerCase(), u);
                }
            }
        }
        return new ArrayList<>(byEmail.values());
    }

    public boolean hasGeo(Group agency) {
        return agency != null
                && GeoUtil.validLatLng(agency.getJurisdictionLat(), agency.getJurisdictionLng())
                && agency.getJurisdictionRadiusMiles() != null
                && Double.isFinite(agency.getJurisdictionRadiusMiles())
                && agency.getJurisdictionRadiusMiles() > 0.0
                && agency.getJurisdictionRadiusMiles() <= MAX_RADIUS_MILES;
    }

    public void requireValidGeo(Double lat, Double lng, Double radiusMiles) {
        if (!GeoUtil.validLatLng(lat, lng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid lat/lng required");
        }
        if (radiusMiles == null
                || !Double.isFinite(radiusMiles)
                || radiusMiles <= 0.0
                || radiusMiles > MAX_RADIUS_MILES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "radiusMiles must be > 0 and <= 50");
        }
    }

    private static Set<String> legacyZips(Group agency) {
        Set<String> out = new LinkedHashSet<>();
        if (agency == null || agency.getJurisdictionZips() == null) return out;
        for (String zip : agency.getJurisdictionZips()) {
            if (zip != null && !zip.isBlank()) out.add(zip.trim());
        }
        return out;
    }
}
