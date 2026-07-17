package io.sitprep.sitprepapi.service;

import io.sitprep.sitprepapi.domain.Group;
import io.sitprep.sitprepapi.repo.GroupRepo;
import io.sitprep.sitprepapi.util.GeoUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 (Jurisdiction Foundations) — the single "which authorized agencies
 * cover this location?" resolver. See
 * {@code Status Now/docs/AGENCY_ONBOARDING_JURISDICTION_PLAN.md} (Phase 1) and
 * {@code sitprepapi 2/docs/CIVIC_EPIC_EXECUTION.md} (the civic operations lane
 * this feeds).
 *
 * <p>Before this service the app answered that question three different ways
 * that did not share a resolver — Haversine radius for alert recipients,
 * {@code jurisdictionZips} set-membership for the community co-sign, and a
 * free-text service-area substring for the civic tag-picker. This is the one
 * place that reconciles them, per <b>owner decision D1 (locked 2026-07-17):
 * "both, with roles."</b> It unions two coverage predicates:</p>
 *
 * <ul>
 *   <li><b>radius</b> — the point lies inside the agency's
 *       ({@code jurisdictionLat/Lng}, {@code jurisdictionRadiusMiles}) circle.
 *       Precise, for small/local agencies; the {@code <= 50 mi} cap
 *       ({@link AgencyAuthorizationService#MAX_RADIUS_MILES}) means it can't
 *       express a county or state, which is why the zip side exists.</li>
 *   <li><b>zip-set</b> — the point's zip is in the agency's claimed
 *       {@code jurisdictionZips}. Authoritative for the county/state tiers a
 *       radius can't cover.</li>
 * </ul>
 *
 * <p>The result is deduped by {@code groupId} and deterministically ordered
 * (zip matches first, then radius matches). <b>Overlap is intended</b>: a
 * report inside a city that is also inside its county resolves to <i>both</i>
 * agencies (civic epic owner decision 8). Callers must never dedup across
 * distinct agencies.</p>
 *
 * <p><b>Not wired yet.</b> This is the isolated Phase-1 core. Re-pointing the
 * three existing surfaces at it ({@code AgencyAuthorizationService.recipients},
 * {@code PostService.localAgencyForViewer}, and the civic tag-picker discovery)
 * is the follow-up wiring step, which touches the enforcement boundary and is
 * sequenced under one-writer coordination with the civic-build lane.</p>
 */
@Service
public class AgencyJurisdictionService {

    private final GroupRepo groupRepo;
    private final AgencyAuthorizationService agencyAuth;

    public AgencyJurisdictionService(GroupRepo groupRepo, AgencyAuthorizationService agencyAuth) {
        this.groupRepo = groupRepo;
        this.agencyAuth = agencyAuth;
    }

    /**
     * Every {@code agencyAuthorized} group whose jurisdiction covers the given
     * location, by radius OR claimed-zip membership (union). Either input may be
     * absent: a null/blank zip skips the zip side, an invalid lat/lng skips the
     * radius side. With neither usable input the result is empty.
     *
     * @param lat  report/viewer latitude (nullable)
     * @param lng  report/viewer longitude (nullable)
     * @param zip  report/viewer zip (nullable/blank tolerated)
     * @return deduped, deterministically-ordered covering agencies (never null)
     */
    public List<Group> agenciesCovering(Double lat, Double lng, String zip) {
        // Dedup by public groupId, preserving insertion order (zip side first
        // = higher-tier authoritative match wins the ordering).
        Map<String, Group> covering = new LinkedHashMap<>();

        String normalizedZip = normalizeZip(zip);
        if (normalizedZip != null) {
            for (Group agency : groupRepo.findByJurisdictionZip(normalizedZip)) {
                // findByJurisdictionZip does not itself filter agencyAuthorized;
                // in practice only super-admin provisioning sets zips, but gate
                // defensively so a stray zip on a non-authorized group can't leak.
                if (agency != null && agency.isAgencyAuthorized() && agency.getGroupId() != null) {
                    covering.putIfAbsent(agency.getGroupId(), agency);
                }
            }
        }

        if (GeoUtil.validLatLng(lat, lng)) {
            for (Group agency : groupRepo.findAuthorizedAgencies()) {
                if (agency == null || agency.getGroupId() == null) {
                    continue;
                }
                if (agencyAuth.hasGeo(agency)
                        && GeoUtil.haversineKm(lat, lng,
                                agency.getJurisdictionLat(), agency.getJurisdictionLng())
                           <= GeoUtil.milesToKm(agency.getJurisdictionRadiusMiles())) {
                    covering.putIfAbsent(agency.getGroupId(), agency);
                }
            }
        }

        return new ArrayList<>(covering.values());
    }

    /** Trim; treat null/blank as "no zip". No format enforcement (matches the
     *  rest of the BE, which stores whatever provisioning supplied). */
    private static String normalizeZip(String zip) {
        if (zip == null) {
            return null;
        }
        String trimmed = zip.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
